package app.finance.core.network

import app.finance.core.sync.SyncTransportException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Транспорт против локального сервера, повторяющего протокол REST API Диска: ссылка на
 * загрузку и PUT по ней, ссылка на скачивание, список папки, 404, 409 на существующую папку,
 * асинхронное удаление и проверка токена.
 */
class YandexDiskTransportTest {

    private val files = sortedMapOf<String, String>()
    private val folders = sortedSetOf<String>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val base = "http://127.0.0.1:${server.address.port}"
    private var token: String? = "good"

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    private val transport = YandexDiskTransport(token = { token }, root = "app:/", api = "$base/v1/disk")

    private fun handle(e: HttpExchange) {
        val query = YandexAuth.parseQuery(e.requestURI.rawQuery.orEmpty())
        val path = query["path"]?.removePrefix("app:/")?.trimStart('/') ?: ""
        val route = e.requestURI.path
        if (route.startsWith("/v1/") && e.requestHeaders.getFirst("Authorization") != "OAuth good") return reply(e, 401, "{}")
        when {
            route == "/v1/disk/resources/upload" ->
                if (!folders.contains(path.substringBeforeLast('/', "")) && path.contains('/')) reply(e, 409, "{}")
                else reply(e, 200, """{"href":"$base/put?p=${YandexAuth.enc(path)}"}""")
            route == "/put" -> {
                files[URLDecoder.decode(query["p"]!!, StandardCharsets.UTF_8)] = e.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                reply(e, 201, "")
            }
            route == "/v1/disk/resources/download" ->
                if (path in files) reply(e, 200, """{"href":"$base/get?p=${YandexAuth.enc(path)}"}""") else reply(e, 404, "{}")
            route == "/get" -> reply(e, 200, files.getValue(query["p"]!!))
            route == "/v1/disk/resources" && e.requestMethod == "PUT" ->
                if (folders.add(path)) reply(e, 201, "{}") else reply(e, 409, "{}")
            route == "/v1/disk/resources" && e.requestMethod == "DELETE" -> {
                files.keys.removeIf { it == path || it.startsWith("$path/") }
                folders.removeIf { it == path || it.startsWith("$path/") }
                reply(e, 202, """{"href":"$base/v1/op"}""")
            }
            route == "/v1/op" -> reply(e, 200, """{"status":"success"}""")
            route == "/v1/disk/resources" -> {
                val prefix = if (path.isEmpty()) "" else "$path/"
                if (path.isNotEmpty() && path !in folders) return reply(e, 404, "{}")
                val names = (files.keys + folders).filter { it.startsWith(prefix) && it != path }
                    .map { it.removePrefix(prefix).substringBefore('/') }.distinct()
                val items = names.joinToString(",") { """{"name":"$it"}""" }
                reply(e, 200, """{"_embedded":{"items":[$items],"total":${names.size}}}""")
            }
            else -> reply(e, 400, "{}")
        }
    }

    private fun reply(e: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        e.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) e.responseBody.use { it.write(bytes) } else e.close()
    }

    @Test
    fun `запись создаёт папки, чтение и список работают, отсутствие — null`() = runTest {
        assertNull(transport.read("sync.json"))
        assertNull(transport.list("devices"))

        transport.write("devices/abc/changes-000001.jsonl", "строка 1\nстрока 2\n")
        transport.write("devices/abc/device.json", "{}")
        transport.write("sync.json", """{"format":1}""")

        assertEquals("строка 1\nстрока 2\n", transport.read("devices/abc/changes-000001.jsonl"))
        assertEquals(listOf("abc"), transport.list("devices"))
        assertEquals(setOf("changes-000001.jsonl", "device.json"), transport.list("devices/abc")!!.toSet())

        transport.delete("devices")
        assertNull(transport.list("devices"))
        assertNull(transport.read("devices/abc/device.json"))
        // После удаления папки запись снова создаёт её.
        transport.write("devices/abc/device.json", "{}")
        assertEquals("{}", transport.read("devices/abc/device.json"))
    }

    @Test
    fun `без токена и с неверным токеном — ошибка входа`() = runTest {
        token = null
        val missing = assertFailsWith<SyncTransportException> { transport.read("sync.json") }
        assertEquals(SyncTransportException.Kind.AUTH, missing.kind)
        token = "bad"
        val wrong = assertFailsWith<SyncTransportException> { transport.read("sync.json") }
        assertEquals(SyncTransportException.Kind.AUTH, wrong.kind)
        assertTrue(wrong.message!!.contains("войдите", ignoreCase = true))
    }

    @Test
    fun `сервер недоступен — сетевая ошибка`() = runTest {
        server.stop(0)
        val error = assertFailsWith<SyncTransportException> { transport.read("sync.json") }
        assertEquals(SyncTransportException.Kind.NETWORK, error.kind)
    }
}
