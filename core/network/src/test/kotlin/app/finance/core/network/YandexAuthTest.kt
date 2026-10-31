package app.finance.core.network

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Вход: PKCE, возврат на 127.0.0.1, вход кодом, токен только в хранилище секретов. */
class YandexAuthTest {

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val base = "http://127.0.0.1:${server.address.port}"
    private var lastTokenForm: Map<String, String> = emptyMap()
    private var challenge: String? = null

    init {
        server.createContext("/token") { e ->
            lastTokenForm = YandexAuth.parseQuery(e.requestBody.readAllBytes().toString(StandardCharsets.UTF_8))
            val verifier = lastTokenForm["code_verifier"].orEmpty()
            val expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val ok = lastTokenForm["code"] == "1234567" && expected == challenge
            val body = if (ok) """{"access_token":"tok","refresh_token":"ref","expires_in":31536000,"token_type":"bearer"}"""
            else """{"error":"invalid_grant"}"""
            val bytes = body.toByteArray()
            e.sendResponseHeaders(if (ok) 200 else 400, bytes.size.toLong())
            e.responseBody.use { it.write(bytes) }
        }
        server.createContext("/info") { e ->
            val bytes = """{"login":"ilya"}""".toByteArray()
            e.sendResponseHeaders(200, bytes.size.toLong())
            e.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    private val secrets = MemorySecretStore()
    private val auth = YandexAuth("client-1", secrets, now = { 1_000L }, oauthBase = base, loginInfoUrl = "$base/info")

    @Test
    fun `вход через браузер с возвратом на 127_0_0_1`() = runBlocking {
        val token = auth.signInWithBrowser(openBrowser = { url ->
            val query = YandexAuth.parseQuery(URI.create(url).rawQuery)
            assertEquals("client-1", query["client_id"])
            assertEquals("S256", query["code_challenge_method"])
            assertEquals(YandexAuth.LOOPBACK_REDIRECT, query["redirect_uri"])
            challenge = query["code_challenge"]
            // «Браузер»: Яндекс возвращает пользователя на адрес приложения с кодом.
            thread {
                val back = "${YandexAuth.LOOPBACK_REDIRECT}?code=1234567&state=${YandexAuth.enc(query["state"]!!)}"
                HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(back)).GET().build(), HttpResponse.BodyHandlers.ofString())
            }
        }, timeoutMillis = 10_000)

        assertEquals("tok", token.accessToken)
        assertEquals("ilya", token.login)
        assertEquals(1_000L + 31_536_000_000L, token.expiresAtUtc)
        assertEquals("authorization_code", lastTokenForm["grant_type"])
        assertNull(lastTokenForm["client_secret"], "секрет приложения не нужен")
        assertEquals("tok", auth.currentToken()?.accessToken)
        assertTrue(secrets.read(YandexAuth.SECRET_NAME)!!.contains("tok"))

        auth.signOut()
        assertNull(auth.currentToken())
    }

    @Test
    fun `вход кодом и неверный код`() = runBlocking {
        val pending = auth.startWithCode()
        val query = YandexAuth.parseQuery(URI.create(pending.authorizeUrl).rawQuery)
        assertEquals(YandexAuth.VERIFICATION_REDIRECT, query["redirect_uri"])
        challenge = query["code_challenge"]

        val error = assertFailsWith<YandexAuthException> { auth.finishWithCode(pending, "0000000") }
        assertTrue(error.message!!.contains("устарел"))
        assertEquals("tok", auth.finishWithCode(pending, " 1234567 ").accessToken)
    }
}
