package app.finance.core.data

import app.cash.sqldelight.db.SqlDriver
import app.finance.core.data.db.FinanceDatabase
import app.finance.core.domain.TimeProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Повод бэкапа (SPEC.md F7.3). */
enum class BackupTrigger { APP_CLOSE, TIMER, MANUAL, PRE_RESTORE }

/** Запись журнала бэкапов — строка в `Finance-manifest.json` (D-30). */
data class BackupEntry(
    val fileName: String,
    val createdAtUtc: Long,
    val trigger: String,
    val sha256: String,
    val sizeBytes: Long,
    val schemaVersion: Long,
    val appVersion: String,
    val status: String,
    val error: String? = null,
) {
    val isOk: Boolean get() = status == STATUS_OK

    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_FAILED = "FAILED"
    }
}

sealed interface BackupResult {
    data class Created(val entry: BackupEntry, val pruned: Int) : BackupResult
    data object Unchanged : BackupResult
    data class Failed(val message: String) : BackupResult
}

/** Снапшот не годится для восстановления. */
class BackupRestoreException(message: String) : Exception(message)

/**
 * Локальные бэкапы в каталоге `backups/` рядом с базой (SPEC.md F7, M6; транспорт
 * `LOCAL_FOLDER`, D-30).
 *
 * Снапшот — `VACUUM INTO` во временный файл, затем проверка (`PRAGMA integrity_check`,
 * версия схемы) и только после неё — переименование в `Finance-<UTC>-v<схема>.db`, файл
 * `.sha256` и запись в манифест. Если данные не менялись с последнего бэкапа (тот же
 * sha256 содержимого), новый файл не создаётся (F7.4). Прунинг по [BackupRetention] — только
 * после успешной записи нового снапшота (F7.5).
 *
 * Журнал хранится в манифесте, а не в таблице базы: он переживает восстановление базы из
 * старого снапшота и читается без открытия базы.
 */
class LocalBackupService(
    private val driver: SqlDriver,
    val directory: Path,
    private val time: TimeProvider,
    private val appVersion: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val manifest: Path get() = directory.resolve(MANIFEST)
    private val json = Json { prettyPrint = true }
    private val lock = Any()

    fun journal(): List<BackupEntry> = synchronized(lock) { readManifest() }.sortedByDescending { it.createdAtUtc }

    /** Снапшоты, из которых можно восстановиться: успешные и с файлом на месте. */
    fun restorable(): List<BackupEntry> = journal().filter { it.isOk && Files.exists(directory.resolve(it.fileName)) }

    fun lastSuccessAtUtc(): Long? = journal().firstOrNull { it.isOk }?.createdAtUtc

    fun backup(trigger: BackupTrigger, force: Boolean = false): BackupResult = synchronized(lock) {
        val now = time.nowUtcMillis()
        val version = FinanceDatabase.Schema.version
        val name = "Finance-${STAMP.format(Instant.ofEpochMilli(now))}-v$version.db"
        val temp = directory.resolve("$name.tmp")
        try {
            Files.createDirectories(directory)
            Files.deleteIfExists(temp)
            val path = temp.toAbsolutePath().toString().replace("'", "''")
            driver.execute(identifier = null, sql = "VACUUM INTO '$path'", parameters = 0)
            val check = inspect(temp)
            if (check.integrity != "ok") error("проверка целостности снапшота: ${check.integrity}")
            val sha = sha256(temp)
            val entries = readManifest()
            val last = entries.filter { it.isOk && Files.exists(directory.resolve(it.fileName)) }.maxByOrNull { it.createdAtUtc }
            if (!force && last != null && last.sha256 == sha) {
                Files.deleteIfExists(temp)
                return BackupResult.Unchanged
            }
            var target = directory.resolve(name)
            var n = 1
            while (Files.exists(target)) target = directory.resolve(name.removeSuffix(".db") + "-${n++}.db")
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            Files.writeString(directory.resolve("${target.fileName}.sha256"), "$sha  ${target.fileName}\n")
            val entry = BackupEntry(
                fileName = target.fileName.toString(),
                createdAtUtc = now,
                trigger = trigger.name,
                sha256 = sha,
                sizeBytes = Files.size(target),
                schemaVersion = version,
                appVersion = appVersion,
                status = BackupEntry.STATUS_OK,
            )
            val pruned = prune(entries + entry)
            BackupResult.Created(entry, pruned)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            val message = e.message ?: e.javaClass.simpleName
            runCatching {
                writeManifest(
                    readManifest() + BackupEntry(name, now, trigger.name, "", 0, version, appVersion, BackupEntry.STATUS_FAILED, message),
                )
            }
            BackupResult.Failed(message)
        }
    }

    /**
     * Проверка снапшота перед восстановлением (F7.7): checksum, целостность, версия схемы
     * не новее приложения. Возвращает путь к копии, готовой встать на место базы.
     */
    fun prepareRestore(entry: BackupEntry, databasePath: Path): Path {
        val file = directory.resolve(entry.fileName)
        if (!Files.exists(file)) throw BackupRestoreException("Файл снапшота не найден: ${entry.fileName}")
        if (entry.sha256.isNotEmpty() && sha256(file) != entry.sha256) {
            throw BackupRestoreException("Снапшот повреждён: контрольная сумма не совпадает")
        }
        val check = inspect(file)
        if (check.integrity != "ok") throw BackupRestoreException("Снапшот не прошёл проверку целостности")
        if (check.userVersion > FinanceDatabase.Schema.version) {
            throw BackupRestoreException("Снапшот сделан более новой версией Finance — обновите приложение")
        }
        val staged = databasePath.resolveSibling("${databasePath.fileName}.restore")
        Files.copy(file, staged, StandardCopyOption.REPLACE_EXISTING)
        return staged
    }

    // ── Прунинг ──────────────────────────────────────────────────────────────────

    private fun prune(entries: List<BackupEntry>): Int {
        val ok = entries.filter { it.isOk && Files.exists(directory.resolve(it.fileName)) }
        val keep = BackupRetention.keep(ok.map { it.fileName to it.createdAtUtc }, zone)
        var removed = 0
        val remaining = entries.filter { entry ->
            if (!entry.isOk || entry.fileName in keep) return@filter true
            runCatching {
                Files.deleteIfExists(directory.resolve(entry.fileName))
                Files.deleteIfExists(directory.resolve("${entry.fileName}.sha256"))
            }
            removed++
            false
        }
        // Неудачные попытки в журнале — только последние: иначе он растёт без предела.
        val failures = remaining.filter { !it.isOk }.sortedByDescending { it.createdAtUtc }.drop(MAX_FAILED).toSet()
        writeManifest(remaining - failures)
        removed += pruneLabeled()
        return removed
    }

    /** Служебные снимки перед синхронизацией: хранятся последние [KEEP_PRE_SYNC]. */
    private fun pruneLabeled(): Int {
        val files = Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().matches(PRE_SYNC) }.toList()
        }.sortedByDescending { it.fileName.toString() }
        files.drop(KEEP_PRE_SYNC).forEach { Files.deleteIfExists(it) }
        return (files.size - KEEP_PRE_SYNC).coerceAtLeast(0)
    }

    // ── Файлы ────────────────────────────────────────────────────────────────────

    private data class Inspection(val integrity: String, val userVersion: Long)

    private fun inspect(file: Path): Inspection =
        DriverManager.getConnection("jdbc:sqlite:file:${file.toAbsolutePath()}?mode=ro").use { connection ->
            connection.createStatement().use { st ->
                val integrity = st.executeQuery("PRAGMA integrity_check").use { rs -> if (rs.next()) rs.getString(1) else "?" }
                val version = st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                Inspection(integrity, version)
            }
        }

    private fun readManifest(): List<BackupEntry> = runCatching {
        if (!Files.exists(manifest)) return emptyList()
        val root = Json.parseToJsonElement(Files.readString(manifest)).jsonObject
        root["backups"]!!.jsonArray.map { item ->
            val o = item.jsonObject
            fun s(key: String) = o[key]?.jsonPrimitive?.contentOrNull
            BackupEntry(
                fileName = s("file")!!,
                createdAtUtc = o["created_at_utc"]!!.jsonPrimitive.long,
                trigger = s("trigger") ?: "?",
                sha256 = s("sha256").orEmpty(),
                sizeBytes = o["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0,
                schemaVersion = o["schema_version"]?.jsonPrimitive?.longOrNull ?: 0,
                appVersion = s("app_version").orEmpty(),
                status = s("status") ?: BackupEntry.STATUS_OK,
                error = s("error"),
            )
        }
    }.getOrDefault(emptyList())

    private fun writeManifest(entries: List<BackupEntry>) {
        val sorted = entries.sortedBy { it.createdAtUtc }
        val latest = sorted.lastOrNull { it.isOk }
        val root: JsonObject = buildJsonObject {
            put("format", 1)
            put("latest", latest?.fileName)
            put("schema_version", latest?.schemaVersion)
            put("app_version", latest?.appVersion)
            put("created_at_utc", latest?.createdAtUtc)
            put("sha256", latest?.sha256)
            put(
                "backups",
                buildJsonArray {
                    sorted.forEach { e ->
                        add(
                            buildJsonObject {
                                put("file", e.fileName)
                                put("created_at_utc", e.createdAtUtc)
                                put("trigger", e.trigger)
                                put("sha256", e.sha256)
                                put("size_bytes", e.sizeBytes)
                                put("schema_version", e.schemaVersion)
                                put("app_version", e.appVersion)
                                put("status", e.status)
                                put("error", e.error)
                            },
                        )
                    }
                },
            )
        }
        val temp = directory.resolve("$MANIFEST.tmp")
        Files.writeString(temp, json.encodeToString(JsonObject.serializer(), root))
        Files.move(temp, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        latest?.let { Files.writeString(directory.resolve(LATEST), it.fileName + "\n") }
    }

    companion object {
        const val MANIFEST = "Finance-manifest.json"
        const val LATEST = "LATEST"
        private const val MAX_FAILED = 20
        private const val KEEP_PRE_SYNC = 10
        private val PRE_SYNC = Regex("""Finance-.*-pre-sync(-\d+)?\.db""")
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        fun sha256(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Подмена базы снапшотом (F7.7). Вызывается, когда база ЗАКРЫТА: текущий файл уходит в
         * карантин `finance.db.before-restore`, на его место атомарно встаёт подготовленная копия.
         * При ошибке карантин возвращается на место.
         */
        fun swapIn(staged: Path, databasePath: Path) {
            val quarantine = databasePath.resolveSibling("${databasePath.fileName}.before-restore")
            val wal = databasePath.resolveSibling("${databasePath.fileName}-wal")
            val shm = databasePath.resolveSibling("${databasePath.fileName}-shm")
            Files.deleteIfExists(quarantine)
            if (Files.exists(databasePath)) Files.move(databasePath, quarantine, StandardCopyOption.ATOMIC_MOVE)
            try {
                Files.deleteIfExists(wal)
                Files.deleteIfExists(shm)
                Files.move(staged, databasePath, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                if (Files.exists(quarantine)) Files.move(quarantine, databasePath, StandardCopyOption.REPLACE_EXISTING)
                throw e
            }
        }
    }
}

/**
 * Какие бэкапы хранить (SPEC.md F7.5): последний за каждый из 7 последних дней, 4 недель,
 * 6 месяцев и 3 лет, в которые были бэкапы; не меньше 3 самых свежих в любом случае.
 */
object BackupRetention {
    const val DAYS = 7
    const val WEEKS = 4
    const val MONTHS = 6
    const val YEARS = 3
    const val MIN_KEEP = 3

    fun keep(backups: List<Pair<String, Long>>, zone: ZoneId): Set<String> {
        val newest = backups.sortedByDescending { it.second }
        fun date(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        fun bucket(limit: Int, key: (LocalDate) -> Any): List<String> =
            newest.distinctBy { key(date(it.second)) }.take(limit).map { it.first }
        return buildSet {
            addAll(newest.take(MIN_KEEP).map { it.first })
            addAll(bucket(DAYS) { it })
            addAll(bucket(WEEKS) { it.get(IsoFields.WEEK_BASED_YEAR) * 100 + it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) })
            addAll(bucket(MONTHS) { it.year * 100 + it.monthValue })
            addAll(bucket(YEARS) { it.year })
        }
    }
}

/** Фабрика для `app:desktop`, которому типы драйвера SQLDelight не видны (как [snapshotter]). */
fun DatabaseSession.localBackups(directory: Path, time: TimeProvider, appVersion: String, zone: ZoneId): LocalBackupService =
    LocalBackupService(driver, directory, time, appVersion, zone)

/** Текущая версия схемы базы: её пишут пакеты синхронизации и бэкапы. */
val CURRENT_SCHEMA_VERSION: Long get() = FinanceDatabase.Schema.version
