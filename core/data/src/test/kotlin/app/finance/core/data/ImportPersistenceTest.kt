package app.finance.core.data

import app.finance.core.domain.CategoryTarget
import app.finance.core.domain.ImportLedgerBatch
import app.finance.core.domain.ImportRequest
import app.finance.core.domain.ImportRowInput
import app.finance.core.domain.UndoImportBatch
import app.finance.core.model.CategorySection
import app.finance.core.model.ImportBatchStatus
import app.finance.core.model.ImportedFile
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Импорт на настоящей SQLite: схема `import_batch`/`import_row`, внешние ключи, запрос
 * `IN ?` по ключам строк и снапшот `VACUUM INTO` (docs/IMPORT-NOTION.md §5).
 */
class ImportPersistenceTest {

    private val dir: Path = Files.createTempDirectory("finance-import-test")
    private val session = DatabaseFactory.openFile(dir.resolve("finance.db"))
    private val db = session.database
    private val time = FixedTime()
    private val ids = SeqIds()
    private val transactions = SqlDelightTransactionRunner(db)
    private val categories = SqlCategoryRepository(db)
    private val ledger = SqlLedgerRepository(db, ids)
    private val batches = SqlImportBatchRepository(db, ids, time)
    private val snapshotter = SqliteSnapshotter(session.driver, dir.resolve("backups"), time)
    private val import = ImportLedgerBatch(categories, ledger, batches, snapshotter, ids, time, transactions)
    private val undo = UndoImportBatch(categories, ledger, batches, ids, time, transactions)

    private val file = ImportedFile("notion-траты-2025.zip", "abc", LedgerSection.EXPENSE)

    @AfterTest
    fun tearDown() = session.close()

    private fun rows(count: Int) = (1..count).map { i ->
        ImportRowInput(
            key = "key-$i",
            section = LedgerSection.EXPENSE,
            target = CategoryTarget.New(if (i % 2 == 0) "на покушать" else "такси"),
            period = Period(2025, 1 + i % 12),
            amount = Money(i * 100L),
            comment = if (i % 3 == 0) "строка $i" else null,
        )
    }

    @Test
    fun `импорт, повтор и отмена на реальной базе`() = runTest {
        val input = rows(700) // больше одной пачки ключей в IN ?
        val report = import(ImportRequest(listOf(file), input, rowsTotal = 700))

        val batch = assertNotNull(report.batch)
        assertEquals(700, report.rowsImported)
        assertEquals(2, report.categoriesCreated.size)
        // Чётные и нечётные строки попадают в разные месяцы: 6 месяцев × 2 категории.
        assertEquals(12, report.cellsCreated)
        val backup = Path.of(assertNotNull(report.backupPath))
        assertTrue(backup.exists() && Files.size(backup) > 0)

        val stored = assertNotNull(batches.batchById(batch.id))
        assertEquals(listOf(file), stored.files)
        assertEquals(report.categoriesCreated.map { it.id }, stored.createdCategoryIds)
        assertEquals(700, batches.importedRowKeys(input.map { it.key }).size)

        suspend fun total(section: LedgerSection): Long =
            ledger.cellsInRange(section, Period(2025, 1), Period(2025, 12)).sumOf { it.amount.minor }
        val expected = (1..700).sumOf { it * 100L }
        assertEquals(expected, total(LedgerSection.EXPENSE))

        val again = import(ImportRequest(listOf(file), input, rowsTotal = 700))
        assertNull(again.batch)
        assertEquals(expected, total(LedgerSection.EXPENSE))

        val undone = undo(batch.id)
        assertEquals(12, undone.cellsRemoved)
        assertEquals(2, undone.categoriesArchived)
        assertEquals(0L, total(LedgerSection.EXPENSE))
        assertEquals(ImportBatchStatus.UNDONE, batches.batchById(batch.id)?.status)
        assertTrue(categories.bySection(CategorySection.EXPENSE).isEmpty())
        assertTrue(batches.importedRowKeys(input.map { it.key }).isEmpty())
    }

    @Test
    fun `сопоставление категорий запоминается`() = runTest {
        assertEquals(emptyMap(), batches.savedCategoryMap())
        val map = mapOf("EXPENSE:маме" to "cat-1", "EXPENSE:на дом" to "cat-2")
        batches.saveCategoryMap(map)
        assertEquals(map, batches.savedCategoryMap())
    }
}
