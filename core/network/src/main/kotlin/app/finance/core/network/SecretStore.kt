package app.finance.core.network

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * Хранилище секретов (SPEC.md §4, F7.10): токен Яндекса не попадает ни в базу, ни в бэкапы.
 *
 * - Windows — файл, зашифрованный DPAPI текущего пользователя (через PowerShell, без
 *   нативных библиотек): расшифровать его может только этот пользователь на этом компьютере;
 * - macOS — связка ключей (`security`);
 * - прочие системы — файл с правами только для владельца.
 */
interface SecretStore {
    fun read(name: String): String?

    fun write(name: String, value: String)

    fun delete(name: String)
}

/**
 * @param directory каталог данных приложения: у сборки для разработки он свой (D-28), поэтому
 *   и секреты свои.
 * @param namespace имя сервиса в связке ключей macOS; тоже различается для разработки.
 */
class PlatformSecretStore(
    private val directory: Path,
    private val namespace: String,
) : SecretStore {

    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val isWindows = os.contains("win")
    private val isMac = os.contains("mac") || os.contains("darwin")

    override fun read(name: String): String? = when {
        isMac -> run("security", "find-generic-password", "-a", ACCOUNT, "-s", service(name), "-w")?.trim()?.ifEmpty { null }
        isWindows -> fileOf(name).takeIf { Files.exists(it) }?.let { file ->
            val encrypted = Files.readString(file).trim()
            runWithInput(encrypted, "powershell", "-NoProfile", "-NonInteractive", "-Command", DPAPI_DECRYPT)?.trim()?.ifEmpty { null }
        }
        else -> fileOf(name).takeIf { Files.exists(it) }?.let { Files.readString(it).trim().ifEmpty { null } }
    }

    override fun write(name: String, value: String) {
        when {
            isMac -> {
                run("security", "add-generic-password", "-U", "-a", ACCOUNT, "-s", service(name), "-w", value)
                    ?: error("Не удалось сохранить секрет в связку ключей")
            }
            isWindows -> {
                val encrypted = runWithInput(value, "powershell", "-NoProfile", "-NonInteractive", "-Command", DPAPI_ENCRYPT)
                    ?.trim()?.ifEmpty { null } ?: error("Не удалось зашифровать секрет (DPAPI)")
                writeFile(fileOf(name), encrypted)
            }
            else -> writeFile(fileOf(name), value)
        }
    }

    override fun delete(name: String) {
        if (isMac) run("security", "delete-generic-password", "-a", ACCOUNT, "-s", service(name))
        Files.deleteIfExists(fileOf(name))
    }

    private fun service(name: String) = "$namespace.$name"

    private fun fileOf(name: String): Path = directory.resolve("secrets").resolve("$name.secret")

    private fun writeFile(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content, StandardCharsets.UTF_8)
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
    }

    private fun run(vararg command: String): String? = runWithInput(null, *command)

    /** Секрет передаётся через stdin, а не аргументом: так он не виден в списке процессов. */
    private fun runWithInput(input: String?, vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(false).start()
        process.outputStream.use { out -> if (input != null) out.write(input.toByteArray(StandardCharsets.UTF_8)) }
        val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() == 0) output else null
    }.getOrNull()

    private companion object {
        const val ACCOUNT = "finance"
        const val TIMEOUT_SECONDS = 20L

        const val DPAPI_ENCRYPT =
            "\$s=[Console]::In.ReadToEnd(); ConvertTo-SecureString \$s -AsPlainText -Force | ConvertFrom-SecureString"

        const val DPAPI_DECRYPT =
            "\$e=[Console]::In.ReadToEnd().Trim(); \$ss=ConvertTo-SecureString \$e; " +
                "[Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR(\$ss))"
    }
}

/** Секреты в памяти — для тестов. */
class MemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()

    override fun read(name: String): String? = values[name]

    override fun write(name: String, value: String) {
        values[name] = value
    }

    override fun delete(name: String) {
        values.remove(name)
    }
}
