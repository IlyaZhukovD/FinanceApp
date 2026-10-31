package app.finance.core.data

import app.cash.sqldelight.db.SqlDriver
import app.finance.core.data.db.FinanceDatabase
import app.finance.core.data.db.Import_batch as BatchRow
import app.finance.core.domain.DatabaseSnapshotter
import app.finance.core.domain.IdGenerator
import app.finance.core.domain.ImportBatchRepository
import app.finance.core.domain.ImportRowLink
import app.finance.core.domain.TimeProvider
import app.finance.core.model.ImportBatch
import app.finance.core.model.ImportBatchStatus
import app.finance.core.model.ImportedFile
import app.finance.core.model.LedgerSection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Пакеты импорта на SQLite (docs/IMPORT-NOTION.md §5.1).
 *
 * Как и остальные репозитории `core:data`, работает синхронно внутри транзакции
 * (см. [SqlDelightTransactionRunner]).
 */
class SqlImportBatchRepository(
    private val database: FinanceDatabase,
    private val ids: IdGenerator,
    private val time: TimeProvider,
) : ImportBatchRepository {

    private val queries get() = database.importBatchQueries
    private val settings get() = database.appSettingQueries

    override suspend fun insertBatch(batch: ImportBatch) {
        queries.insertBatch(
            id = batch.id,
            source = batch.source,
            files = batch.files.joinToString(LINE) { listOf(it.sha256, it.section.name, it.name).joinToString(TAB) },
            rows_total = batch.rowsTotal.toLong(),
            rows_imported = batch.rowsImported.toLong(),
            cells_touched = batch.cellsTouched.toLong(),
            created_category_ids = batch.createdCategoryIds.joinToString(","),
            status = batch.status.name,
            created_at_utc = batch.createdAtUtc,
            updated_at_utc = batch.updatedAtUtc,
            row_version = batch.rowVersion.toLong(),
            deleted_at_utc = batch.deletedAtUtc,
        )
    }

    override suspend fun updateStatus(batchId: String, status: ImportBatchStatus, updatedAtUtc: Long) {
        queries.updateStatus(status = status.name, updated_at_utc = updatedAtUtc, id = batchId)
    }

    override suspend fun batchById(id: String): ImportBatch? =
        queries.selectBatchById(id).executeAsOneOrNull()?.toDomain()

    override suspend fun batches(): List<ImportBatch> =
        queries.selectBatches().executeAsList().map { it.toDomain() }

    override suspend fun importedRowKeys(keys: Collection<String>): Set<String> =
        // Лимит переменных SQLite (32 766) с запасом: пачки по 500 ключей.
        keys.distinct().chunked(CHUNK).flatMap { chunk ->
            queries.selectAppliedKeys(chunk).executeAsList()
        }.toSet()

    override suspend fun insertRowLinks(links: List<ImportRowLink>) {
        links.forEach { link ->
            queries.insertRow(
                id = ids.newId(),
                row_key = link.rowKey,
                batch_id = link.batchId,
                revision_id = link.revisionId,
                created_at_utc = link.createdAtUtc,
            )
        }
    }

    override suspend fun revisionIdsOfBatch(batchId: String): List<String> =
        queries.selectRevisionIdsOfBatch(batchId).executeAsList()

    override suspend fun savedCategoryMap(): Map<String, String> {
        val raw = settings.selectByKey(CATEGORY_MAP_KEY).executeAsOneOrNull() ?: return emptyMap()
        return raw.split(LINE).mapNotNull { line ->
            val parts = line.split(TAB)
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) parts[0] to parts[1] else null
        }.toMap()
    }

    override suspend fun saveCategoryMap(map: Map<String, String>) {
        val value = map.entries.joinToString(LINE) { (key, id) -> key.replace(TAB, " ").replace(LINE, " ") + TAB + id }
        settings.upsert(key = CATEGORY_MAP_KEY, value_ = value, updated_at_utc = time.nowUtcMillis())
    }

    private fun BatchRow.toDomain(): ImportBatch = ImportBatch(
        id = id,
        source = source,
        files = files.split(LINE).filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split(TAB, limit = 3)
            if (parts.size < 3) return@mapNotNull null
            ImportedFile(name = parts[2], sha256 = parts[0], section = LedgerSection.valueOf(parts[1]))
        },
        rowsTotal = rows_total.toInt(),
        rowsImported = rows_imported.toInt(),
        cellsTouched = cells_touched.toInt(),
        createdCategoryIds = created_category_ids.split(",").filter { it.isNotBlank() },
        status = ImportBatchStatus.valueOf(status),
        createdAtUtc = created_at_utc,
        updatedAtUtc = updated_at_utc,
        rowVersion = row_version.toInt(),
        deletedAtUtc = deleted_at_utc,
    )

    companion object {
        const val CATEGORY_MAP_KEY = "import.notion.categoryMap"
        private const val LINE = "\n"
        private const val TAB = "\t"
        private const val CHUNK = 500
    }
}

/**
 * Снапшот базы через `VACUUM INTO` (SPEC.md F7.1): согласованная копия без остановки
 * приложения. Файл кладётся в `backups/` рядом с базой — тем же каталогом позже
 * воспользуется полноценный бэкап (M6).
 *
 * Имя: `Finance-<UTC>-v<схема>-<метка>.db`, например `Finance-20261031T203000Z-v5-pre-import.db`.
 */
class SqliteSnapshotter(
    private val driver: SqlDriver,
    private val backupsDirectory: Path,
    private val time: TimeProvider,
) : DatabaseSnapshotter {

    override suspend fun snapshot(label: String): String {
        Files.createDirectories(backupsDirectory)
        val stamp = STAMP.format(Instant.ofEpochMilli(time.nowUtcMillis()))
        val version = FinanceDatabase.Schema.version
        var target = backupsDirectory.resolve("Finance-$stamp-v$version-$label.db")
        var n = 1
        while (Files.exists(target)) {
            target = backupsDirectory.resolve("Finance-$stamp-v$version-$label-${n++}.db")
        }
        val path = target.toAbsolutePath().toString().replace("'", "''")
        driver.execute(identifier = null, sql = "VACUUM INTO '$path'", parameters = 0)
        check(Files.size(target) > 0) { "Снапшот базы пустой: $target" }
        return target.toAbsolutePath().toString()
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}

/**
 * Снапшотер открытой базы. Фабрика нужна, чтобы `app:desktop` не зависел от типов драйвера
 * SQLDelight: драйвер — деталь `core:data`.
 */
fun DatabaseSession.snapshotter(backupsDirectory: Path, time: TimeProvider): DatabaseSnapshotter =
    SqliteSnapshotter(driver, backupsDirectory, time)
