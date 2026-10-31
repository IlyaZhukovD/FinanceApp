package app.finance.core.network

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Токен Яндекса. Хранится только в [SecretStore]. */
data class YandexToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtUtc: Long?,
    val login: String?,
)

/** Вход не удался: пользователь закрыл страницу, код устарел, приложение не зарегистрировано. */
class YandexAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Вход в Яндекс (docs/SYNC-YANDEX.md §3).
 *
 * Код авторизации с PKCE: секрет приложения в программе не нужен. Два способа:
 * - **через браузер с возвратом** — приложение на время входа слушает
 *   `http://127.0.0.1:53682/oauth`, куда Яндекс возвращает пользователя с кодом;
 * - **кодом вручную** — Яндекс показывает код на своей странице, пользователь вставляет его в
 *   приложение. Запасной путь, если адрес возврата на 127.0.0.1 не подошёл.
 *
 * Оба адреса должны быть указаны как Redirect URI в настройках приложения на oauth.yandex.ru.
 */
class YandexAuth(
    private val clientId: String,
    private val secrets: SecretStore,
    private val http: HttpClient = defaultHttpClient(),
    private val now: () -> Long = System::currentTimeMillis,
    /** Адреса Яндекс OAuth и профиля; меняются только в тестах на локальный сервер. */
    private val oauthBase: String = OAUTH,
    private val loginInfoUrl: String = "https://login.yandex.ru/info?format=json",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    /** Начатый вход: адрес для браузера и данные, нужные для обмена кода на токен. */
    class Pending internal constructor(
        val authorizeUrl: String,
        internal val verifier: String,
        internal val state: String,
        internal val redirectUri: String,
    )

    fun currentToken(): YandexToken? = secrets.read(SECRET_NAME)?.let { decode(it) }

    fun signOut() = secrets.delete(SECRET_NAME)

    /** Вход через браузер: ждёт возврата на 127.0.0.1 не дольше [timeoutMillis]. */
    suspend fun signInWithBrowser(openBrowser: (String) -> Unit, timeoutMillis: Long = LOGIN_TIMEOUT_MS): YandexToken {
        val pending = start(LOOPBACK_REDIRECT)
        val result = CompletableDeferred<Map<String, String>>()
        val server = try {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), LOOPBACK_PORT), 0)
        } catch (e: java.io.IOException) {
            throw YandexAuthException("Порт $LOOPBACK_PORT занят — войдите с помощью кода", e)
        }
        server.createContext(LOOPBACK_PATH) { exchange ->
            val query = parseQuery(exchange.requestURI.rawQuery.orEmpty())
            val ok = query["code"] != null
            val body = (if (ok) PAGE_OK else PAGE_FAIL).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            result.complete(query)
        }
        server.start()
        try {
            openBrowser(pending.authorizeUrl)
            val query = withTimeout(timeoutMillis) { result.await() }
            if (query["state"] != pending.state) throw YandexAuthException("Ответ Яндекса не относится к этому входу")
            val code = query["code"] ?: throw YandexAuthException(
                query["error_description"] ?: query["error"] ?: "Доступ не предоставлен",
            )
            return exchange(pending, code)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw YandexAuthException("Яндекс не вернул код — войдите с помощью кода", e)
        } finally {
            server.stop(0)
        }
    }

    /** Вход кодом: шаг 1 — адрес страницы, где Яндекс покажет код. */
    fun startWithCode(): Pending = start(VERIFICATION_REDIRECT)

    /** Вход кодом: шаг 2 — код, который пользователь вставил в приложение. */
    suspend fun finishWithCode(pending: Pending, code: String): YandexToken = exchange(pending, code.trim())

    private fun start(redirectUri: String): Pending {
        require(clientId.isNotBlank()) { "Не указан Client ID приложения Яндекса" }
        val verifier = randomToken(VERIFIER_BYTES)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        val state = randomToken(STATE_BYTES)
        val url = "$oauthBase/authorize?" + form(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "force_confirm" to "yes",
        )
        return Pending(url, verifier, state, redirectUri)
    }

    private suspend fun exchange(pending: Pending, code: String): YandexToken = withContext(Dispatchers.IO) {
        val body = form(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to clientId,
            "code_verifier" to pending.verifier,
        )
        val response = send(
            HttpRequest.newBuilder(URI.create("$oauthBase/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
        )
        val obj = runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        if (response.statusCode() != 200 || obj == null) {
            val reason = obj?.get("error_description")?.jsonPrimitive?.contentOrNull ?: obj?.get("error")?.jsonPrimitive?.contentOrNull
            throw YandexAuthException(
                when (obj?.get("error")?.jsonPrimitive?.contentOrNull) {
                    "invalid_grant", "bad_verification_code" -> "Код неверный или устарел — получите новый"
                    "invalid_client" -> "Яндекс не знает приложение с таким Client ID"
                    else -> "Яндекс отказал во входе: ${reason ?: "код ${response.statusCode()}"}"
                },
            )
        }
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: throw YandexAuthException("Яндекс не выдал токен")
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull
        val token = YandexToken(
            accessToken = access,
            refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull,
            expiresAtUtc = expiresIn?.let { now() + it * 1000 },
            login = fetchLogin(access),
        )
        secrets.write(SECRET_NAME, encode(token))
        token
    }

    /** Имя аккаунта для экрана настроек. Не критично: без него вход всё равно работает. */
    private fun fetchLogin(access: String): String? = runCatching {
        val response = send(
            HttpRequest.newBuilder(URI.create(loginInfoUrl))
                .header("Authorization", "OAuth $access")
                .GET()
                .build(),
        )
        if (response.statusCode() != 200) return null
        json.parseToJsonElement(response.body()).jsonObject["login"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun send(request: HttpRequest): HttpResponse<String> = try {
        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    } catch (e: java.io.IOException) {
        throw YandexAuthException("Нет связи с Яндексом", e)
    }

    private fun encode(token: YandexToken): String = buildJsonObject {
        put("access", token.accessToken)
        put("refresh", token.refreshToken)
        put("expiresAt", token.expiresAtUtc)
        put("login", token.login)
    }.toString()

    private fun decode(text: String): YandexToken? = runCatching {
        val o: JsonObject = json.parseToJsonElement(text).jsonObject
        YandexToken(
            accessToken = o["access"]!!.jsonPrimitive.contentOrNull!!,
            refreshToken = o["refresh"]?.jsonPrimitive?.contentOrNull,
            expiresAtUtc = o["expiresAt"]?.jsonPrimitive?.longOrNull,
            login = o["login"]?.jsonPrimitive?.contentOrNull,
        )
    }.getOrNull()

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    companion object {
        const val SECRET_NAME = "yandex-token"
        const val LOOPBACK_PORT = 53682
        private const val LOOPBACK_PATH = "/oauth"
        const val LOOPBACK_REDIRECT = "http://127.0.0.1:$LOOPBACK_PORT$LOOPBACK_PATH"
        const val VERIFICATION_REDIRECT = "https://oauth.yandex.ru/verification_code"
        private const val OAUTH = "https://oauth.yandex.ru"
        private const val LOGIN_TIMEOUT_MS = 5 * 60_000L
        private const val VERIFIER_BYTES = 48
        private const val STATE_BYTES = 16

        private const val PAGE_OK =
            "<html><body style='font-family:sans-serif;padding:40px'><h2>Готово</h2>" +
                "<p>Вход выполнен. Вернитесь в Finance — это окно можно закрыть.</p></body></html>"
        private const val PAGE_FAIL =
            "<html><body style='font-family:sans-serif;padding:40px'><h2>Вход не выполнен</h2>" +
                "<p>Вернитесь в Finance и попробуйте ещё раз.</p></body></html>"

        fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        internal fun form(vararg pairs: Pair<String, String>): String =
            pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

        internal fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        internal fun parseQuery(raw: String): Map<String, String> = raw.split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }
    }
}
