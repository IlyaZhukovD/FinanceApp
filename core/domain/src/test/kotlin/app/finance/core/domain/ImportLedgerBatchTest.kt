package app.finance.core.domain

import app.finance.core.model.CategorySection
import app.finance.core.model.ImportBatchStatus
import app.finance.core.model.ImportedFile
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import app.finance.core.model.RevisionOp
import app.finance.core.model.RevisionSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Импорт пакета строк и его отмена (docs/IMPORT-NOTION.md §3.5, §4, §7). */
class ImportLedgerBatchTest {

    private val fx = DomainFixture()
    private val batches = FakeImportBatchRepository()
    private val snapshotter = FakeSnapshotter()
    private val import = ImportLedgerBatch(fx.categories, fx.ledger, batches, snapshotter, fx.ids, fx.time, fx.transactions)
    private val undo = UndoImportBatch(fx.categories, fx.ledger, batches, fx.ids, fx.time, fx.transactions)

    private val jan = Period(2025, 1)
    private val feb = Period(2025, 2)
    private val file = ImportedFile("траты.zip", "sha", LedgerSection.EXPENSE)

    private fun row(
        key: String,
        amount: Long,
        target: CategoryTarget = CategoryTarget.New("на покушать"),
        period: Period = jan,
        comment: String? = null,
        section: LedgerSection = LedgerSection.EXPENSE,
    ) = ImportRowInput(key, section, target, period, Money(amount), comment)

    private fun request(vararg rows: ImportRowInput) = ImportRequest(listOf(file), rows.toList(), rowsTotal = rows.size)

    @Test
    fun `строки группируются в ячейки, на ячейку одна ревизия IMPORT`() = runTest {
        val report = import(
            request(
                row("r1", 1_000_000, comment = "магазин"),
                row("r2", 500_000, comment = "рынок"),
                row("r3", 200_000, period = feb),
                row("r4", 300_000, target = CategoryTarget.New("такси")),
            ),
        )

        assertEquals(4, report.rowsImported)
        assertEquals(3, report.cellsCreated)
        assertEquals(listOf("на покушать", "такси"), report.categoriesCreated.map { it.name })
        assertEquals(jan, report.firstPeriod[LedgerSection.EXPENSE])
        assertEquals(1, snapshotter.calls)
        assertEquals(1, fx.transactions.transactionsStarted)

        val food = report.categoriesCreated.first()
        val janCell = assertNotNull(fx.ledger.findCell(food.id, jan))
        assertEquals(Money(1_500_000), janCell.amount)
        assertEquals("магазин\nрынок", janCell.comment)
        val revisions = fx.ledger.revisionsOf(janCell.id)
        assertEquals(1, revisions.size)
        assertEquals(RevisionOp.IMPORT, revisions.single().op)
        assertEquals(RevisionSource.IMPORT, revisions.single().source)
        assertEquals(Money(1_500_000), revisions.single().delta)
        assertEquals(ImportBatchStatus.APPLIED, batches.batches().single().status)
    }

    @Test
    fun `существующая ячейка аккумулируется, существующая категория переиспользуется`() = runTest {
        val food = fx.category("food", "Продукты")
        fx.recordAmount(AmountInput(LedgerSection.EXPENSE, food.id, jan, Money(10_000), comment = "вручную"))

        val report = import(
            request(
                row("r1", 5_000, target = CategoryTarget.New("продукты"), comment = "из notion"),
                row("r2", 7_000, target = CategoryTarget.Existing(food.id), comment = "вручную"),
            ),
        )

        assertEquals(emptyList(), report.categoriesCreated)
        assertEquals(1, report.cellsUpdated)
        val cell = assertNotNull(fx.ledger.findCell(food.id, jan))
        assertEquals(Money(22_000), cell.amount)
        assertEquals("вручную\nиз notion", cell.comment)
    }

    @Test
    fun `повторный импорт не меняет сумм и не делает снапшот`() = runTest {
        import(request(row("r1", 1_000), row("r2", 2_000)))
        val again = import(request(row("r1", 1_000), row("r2", 2_000), row("r3", 4_000, period = feb)))

        assertEquals(1, again.rowsImported)
        assertEquals(2, again.rowsAlreadyImported)
        val food = fx.categories.bySection(CategorySection.EXPENSE).single()
        assertEquals(Money(3_000), fx.ledger.findCell(food.id, jan)?.amount)
        assertEquals(Money(4_000), fx.ledger.findCell(food.id, feb)?.amount)

        val third = import(request(row("r1", 1_000), row("r3", 4_000, period = feb)))
        assertNull(third.batch)
        assertEquals(2, third.rowsAlreadyImported)
        assertEquals(2, snapshotter.calls)
    }

    @Test
    fun `сумма ноль в ячейке — ячейка не создаётся`() = runTest {
        val report = import(request(row("r1", 1_000), row("r2", -1_000), row("r3", 500, period = feb)))
        assertEquals(2, report.rowsZeroSum)
        assertEquals(1, report.cellsCreated)
        assertEquals(1, fx.ledger.cells.size)
    }

    @Test
    fun `доходы и расходы в одном пакете`() = runTest {
        val salary = fx.category("salary", "Зарплата", section = CategorySection.INCOME)
        val report = import(
            request(
                row("e1", 1_000),
                row("i1", 18_647_200, target = CategoryTarget.Existing(salary.id), section = LedgerSection.INCOME, period = feb),
            ),
        )
        assertEquals(feb, report.firstPeriod[LedgerSection.INCOME])
        assertEquals(Money(18_647_200), fx.ledger.findCell(salary.id, feb)?.amount)
    }

    @Test
    fun `архивная категория и чужой раздел запрещены`() = runTest {
        val old = fx.category("old", "Старое", archived = true)
        assertFailsWith<IllegalArgumentException> { import(request(row("r1", 1_000, target = CategoryTarget.Existing(old.id)))) }
        val salary = fx.category("salary", "Зарплата", section = CategorySection.INCOME)
        assertFailsWith<IllegalArgumentException> { import(request(row("r1", 1_000, target = CategoryTarget.Existing(salary.id)))) }
    }

    @Test
    fun `отмена возвращает суммы, удаляет созданные ячейки и архивирует новые категории`() = runTest {
        val food = fx.category("food", "Продукты")
        fx.recordAmount(AmountInput(LedgerSection.EXPENSE, food.id, jan, Money(10_000), comment = "вручную"))
        val report = import(
            request(
                row("r1", 5_000, target = CategoryTarget.Existing(food.id), comment = "из notion"),
                row("r2", 3_000, target = CategoryTarget.New("такси")),
            ),
        )
        val batch = assertNotNull(report.batch)

        val result = undo(batch.id)

        assertEquals(1, result.cellsReverted)
        assertEquals(1, result.cellsRemoved)
        assertEquals(1, result.categoriesArchived)
        val cell = assertNotNull(fx.ledger.findCell(food.id, jan))
        assertEquals(Money(10_000), cell.amount)
        assertEquals("вручную", cell.comment)
        assertEquals(RevisionOp.UNDO, fx.ledger.revisionsOf(cell.id).last().op)
        val taxi = report.categoriesCreated.single()
        assertNull(fx.ledger.findCell(taxi.id, jan))
        assertTrue(fx.categories.byId(taxi.id)!!.isArchived)
        assertEquals(ImportBatchStatus.UNDONE, batches.batchById(batch.id)?.status)

        assertFailsWith<IllegalArgumentException> { undo(batch.id) }

        // После отмены те же строки снова можно импортировать.
        val again = import(request(row("r1", 5_000, target = CategoryTarget.Existing(food.id))))
        assertEquals(1, again.rowsImported)
        assertEquals(Money(15_000), fx.ledger.findCell(food.id, jan)?.amount)
    }

    @Test
    fun `отмена сохраняет ручные правки после импорта`() = runTest {
        val report = import(request(row("r1", 5_000)))
        val food = report.categoriesCreated.single()
        fx.recordAmount(AmountInput(LedgerSection.EXPENSE, food.id, jan, Money(2_000)))

        val result = undo(assertNotNull(report.batch).id)

        assertEquals(1, result.cellsReverted)
        assertEquals(0, result.categoriesArchived)
        assertEquals(Money(2_000), fx.ledger.findCell(food.id, jan)?.amount)
        assertTrue(fx.categories.byId(food.id)!!.isSelectable)
    }
}
