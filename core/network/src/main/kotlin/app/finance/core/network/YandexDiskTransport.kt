package app.finance.core.network

import app.finance.core.sync.SyncTransport
import app.finance.core.sync.SyncTransportException
import app.finance.core.sync.SyncTransportException.Kind
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Хранилище синхронизации в папке приложения на Яндекс.Диске (docs/SYNC-YANDEX.md §3).
 *
 * Доступ только к `Приложения/Finance` (`app:/`): права «Доступ к папке приложения на Диске».
 * Загрузка — в два шага: ссылка из `/resources/upload`, затем PUT по ней (ссылка живёт
 * 30 минут, поэтому берётся на каждый файл заново). Сборка для разработки работает с
 * подпапкой `dev` (D-28), чтобы эксперименты не попадали на другие компьютеры.
 *
 * @param token токен из [YandexAuth]; null — пользователь не вошёл.
 */
class YandexDiskTransport(
    private val token: () -> String?,
    private val root: String = ROOT,
    private val http: HttpClient = YandexAuth.defaultHttpClient(),
    /** Адрес API; меняется только в тестах на локальный сервер. */
    private val api: String = API,
) : SyncTransport {

    private val json = Json { ignoreUnknownKeys = true }
    private val knownFolders = mutableSetOf<String>()

    override suspend fun read(path: String): String? = io {
        val link = request("GET", "$api/resources/download?path=${q(full(path))}")
        when (link.statusCode()) {
            404 -> null
            200 -> {
                val href = href(link.body())
                val file = send(HttpRequest.newBuilder(URI.create(href)).timeout(TIMEOUT).GET().build())
                check(file, "скачивание файла")
                file.body()
            }
            else -> fail(link, "ссылка на скачивание")
        }
    }

    override suspend fun write(path: String, content: String): Unit = io {
        ensureFolders(path.substringBeforeLast('/', missingDelimiterValue = ""))
        val link = request("GET", "$api/resources/upload?path=${q(full(path))}&overwrite=true")
        if (link.statusCode() != 200) fail(link, "ссылка на загрузку")
        val upload = send(
            HttpRequest.newBuilder(URI.create(href(link.body())))
                .timeout(TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8))
                .build(),
        )
        if (upload.statusCode() !in listOf(200, 201, 202)) fail(upload, "загрузка файла")
    }

    override suspend fun list(path: String): List<String>? = io {
        val names = mutableListOf<String>()
        var offset = 0
        while (true) {
            val response = request(
                "GET",
                "$api/resources?path=${q(full(path))}&limit=$PAGE&offset=$offset&fields=_embedded.items.name,_embedded.total",
            )
            if (response.statusCode() == 404) return@io null
            if (response.statusCode() != 200) fail(response, "список файлов")
            val embedded = json.parseToJsonElement(response.body()).jsonObject["_embedded"]?.jsonObject ?: break
            val items = embedded["items"]?.jsonArray.orEmpty()
            items.mapNotNullTo(names) { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            val total = embedded["total"]?.jsonPrimitive?.int ?: names.size
            offset += items.size
            if (items.isEmpty() || offset >= total) break
        }
        names
    }

    override suspend fun delete(path: String): Unit = io {
        val response = request("DELETE", "$api/resources?path=${q(full(path))}&permanently=true")
        when (response.statusCode()) {
            204, 404 -> Unit
            // Папки удаляются асинхронно: ждём окончания, иначе удаление догонит новые файлы.
            202 -> waitOperation(href(response.body()))
            else -> fail(response, "удаление")
        }
        knownFolders.removeIf { it == path || it.startsWith("$path/") }
    }

    private suspend fun waitOperation(href: String) {
        repeat(OPERATION_POLLS) {
            val status = send(authorized(HttpRequest.newBuilder(URI.create(href)).GET()).build())
            if (status.statusCode() == 200) {
                when (json.parseToJsonElement(status.body()).jsonObject["status"]?.jsonPrimitive?.contentOrNull) {
                    "success" -> return
                    "failed" -> throw SyncTransportException(Kind.OTHER, "Яндекс.Диск не смог удалить файлы")
                }
            }
            delay(OPERATION_POLL_MS)
        }
        throw SyncTransportException(Kind.NETWORK, "Яндекс.Диск долго удаляет файлы — повторите позже")
    }

    /** Создаёт папки по пути; 409 — папка уже есть. */
    private fun ensureFolders(path: String) {
        if (path.isEmpty()) return
        var current = ""
        for (part in path.split('/')) {
            current = if (current.isEmpty()) part else "$current/$part"
            if (current in knownFolders) continue
            val response = request("PUT", "$api/resources?path=${q(full(current))}")
            if (response.statusCode() !in listOf(201, 409)) fail(response, "создание папки")
            knownFolders += current
        }
    }

    private fun full(path: String): String = if (path.isEmpty()) root else "${root.trimEnd('/')}/$path"

    private fun request(method: String, url: String): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
        val withMethod = when (method) {
            "GET" -> builder.GET()
            "DELETE" -> builder.DELETE()
            else -> builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        return send(authorized(withMethod).build())
    }

    private fun authorized(builder: HttpRequest.Builder): HttpRequest.Builder {
        val value = token() ?: throw SyncTransportException(Kind.AUTH, "Войдите в Яндекс в настройках синхронизации")
        return builder.header("Authorization", "OAuth $value")
    }

    private fun send(request: HttpRequest): HttpResponse<String> = try {
        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    } catch (e: java.io.IOException) {
        throw SyncTransportException(Kind.NETWORK, "Нет связи с Яндекс.Диском", e)
    }

    private fun href(body: String): String =
        (json.parseToJsonElement(body) as? JsonObject)?.get("href")?.jsonPrimitive?.contentOrNull
            ?: throw SyncTransportException(Kind.OTHER, "Яндекс.Диск не вернул ссылку")

    private fun check(response: HttpResponse<String>, what: String) {
        if (response.statusCode() !in 200..299) fail(response, what)
    }

    private fun fail(response: HttpResponse<String>, what: String): Nothing {
        val code = response.statusCode()
        val kind = when (code) {
            401 -> Kind.AUTH
            403 -> if (response.body().contains("Unauthorized", ignoreCase = true)) Kind.AUTH else Kind.QUOTA
            429 -> Kind.RATE_LIMIT
            413, 423, 507 -> Kind.QUOTA
            in 500..599 -> Kind.NETWORK
            else -> Kind.OTHER
        }
        val message = when (kind) {
            Kind.AUTH -> "Вход в Яндекс устарел — войдите снова"
            Kind.RATE_LIMIT -> "Яндекс.Диск просит подождать — синхронизация повторится позже"
            Kind.QUOTA -> when (code) {
                507 -> "На Яндекс.Диске нет места"
                413 -> "Файл больше, чем разрешает тариф Яндекс.Диска"
                else -> "Яндекс.Диск временно запретил загрузку (лимит трафика)"
            }
            Kind.NETWORK -> "Яндекс.Диск недоступен ($code) — повторится позже"
            Kind.OTHER -> "Яндекс.Диск ответил $code на «$what»"
        }
        throw SyncTransportException(kind, message)
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun q(value: String) = YandexAuth.enc(value)

    companion object {
        const val ROOT = "app:/"
        const val DEV_ROOT = "app:/dev"
        private const val API = "https://cloud-api.yandex.net/v1/disk"
        private const val PAGE = 200
        private const val OPERATION_POLLS = 60
        private const val OPERATION_POLL_MS = 500L
        private val TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
