package app.finance

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Запрет второго экземпляра приложения (D-36).
 *
 * Две копии Finance на одном каталоге данных писали бы в одну базу, файл настроек и папку
 * синхронизации одновременно, а бэкап при закрытии одной снимал бы базу, которую другая ещё
 * меняет. Поэтому первый экземпляр держит эксклюзивную блокировку файла `instance.lock`
 * в каталоге данных, а второй, не получив её, просит первый показать окно и завершается.
 *
 * Блокировку держит ОС: при любом завершении процесса, в том числе аварийном, она снимается
 * сама, «зависших» lock-файлов не бывает. Каталоги собранного приложения и запуска из
 * исходников разные (D-28), поэтому установленный Finance и `gradlew run` друг другу не мешают.
 *
 * Канал «покажи окно» — TCP-сокет только на 127.0.0.1; порт первый экземпляр пишет
 * в `instance.port`. Принимается одна фиксированная команда, остальное игнорируется.
 */
class SingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
) : AutoCloseable {

    @Volatile
    private var onActivate: () -> Unit = {}

    /** Обработчик просьбы второго экземпляра показать окно. Вызывается не из UI-потока. */
    fun onActivate(handler: () -> Unit) {
        onActivate = handler
    }

    private fun listen() {
        val thread = Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                client.use { socket ->
                    runCatching {
                        socket.soTimeout = IO_TIMEOUT_MS
                        val line = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                        if (line == ACTIVATE_COMMAND) onActivate()
                    }
                }
            }
        }, "finance-single-instance")
        thread.isDaemon = true
        thread.start()
    }

    override fun close() {
        runCatching { server.close() }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private const val LOCK_FILE = "instance.lock"
        private const val PORT_FILE = "instance.port"
        private const val ACTIVATE_COMMAND = "activate"
        private const val IO_TIMEOUT_MS = 2_000

        /**
         * Занимает каталог данных [directory].
         *
         * @return экземпляр-владелец, если приложение ещё не запущено; null — уже запущено,
         *   и запущенному отправлена просьба показать окно ([activated] сообщает, дошла ли она).
         */
        fun acquire(directory: Path, activated: (Boolean) -> Unit = {}): SingleInstance? {
            Files.createDirectories(directory)
            val channel = FileChannel.open(
                directory.resolve(LOCK_FILE),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // Блокировку держит этот же процесс (так бывает в тестах).
                null
            }
            if (lock == null) {
                channel.close()
                activated(requestActivation(directory))
                return null
            }
            val server = ServerSocket()
            return try {
                server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                Files.writeString(directory.resolve(PORT_FILE), server.localPort.toString())
                SingleInstance(channel, lock, server).also { it.listen() }
            } catch (e: IOException) {
                // Без канала активации запрет всё равно работает: важна блокировка.
                System.err.println("Finance: канал активации окна недоступен — ${e.message}")
                runCatching { server.close() }
                SingleInstance(channel, lock, ServerSocket())
            }
        }

        private fun requestActivation(directory: Path): Boolean = runCatching {
            val port = Files.readString(directory.resolve(PORT_FILE)).trim().toInt()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), IO_TIMEOUT_MS)
                socket.getOutputStream().write("$ACTIVATE_COMMAND\n".toByteArray(StandardCharsets.UTF_8))
                socket.getOutputStream().flush()
            }
            true
        }.getOrDefault(false)
    }
}
