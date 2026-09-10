package app.finance.core.domain

import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import app.finance.core.model.RevisionOp
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditCellTest {

    private val fx = DomainFixture()
    private val feb = Period(2026, 2)

    /** Создаёт категорию и ячейку в феврале, возвращает ячейку. */
    private suspend fun cellWith(
        amount: Long,
        comment: String? = null,
        categoryId: String = "food",
    ): CategoryMonthCell {
        fx.category(categoryId, categoryId)
        val result = fx.recordAmount(
            AmountInput(LedgerSection.EXPENSE, categoryId, feb, Money(amount), comment),
        )
        return result.cell
    }

    @Test
    fun `правка суммы пишет SET_AMOUNT с дельтой и поднимает версию строки`() = runTest {
        val cell = cellWith(32_400)

        val result = fx.editCell(cell.id, amount = Money(30_000))

        assertEquals(Money(30_000), result.cell.amount)
        assertEquals(cell.rowVersion + 1, result.cell.rowVersion)
        val revision = result.revisions.single()
        assertEquals(RevisionOp.SET_AMOUNT, revision.op)
        assertEquals(Money(32_400), revision.amountBefore)
        assertEquals(Money(30_000), revision.amountAfter)
        assertEquals(Money(-2_400), revision.delta)
    }

    @Test
    fun `установка той же суммы не создаёт пустую ревизию`() = runTest {
        val cell = cellWith(10_000)

        val result = fx.editCell(cell.id, amount = Money(10_000))

        assertTrue(result.revisions.isEmpty())
        assertEquals(cell.rowVersion, result.cell.rowVersion)
        assertEquals(1, fx.ledger.revisions.size, "В журнале только ревизия создания")
    }

    @Test
    fun `правка комментария не трогает сумму`() = runTest {
        val cell = cellWith(25_000, comment = "Аренда")

        val result = fx.editCell(cell.id, comment = CommentChange.Set("Аренда + коммуналка"))

        assertEquals(Money(25_000), result.cell.amount)
        assertEquals("Аренда + коммуналка", result.cell.comment)
        val revision = result.revisions.single()
        assertEquals(RevisionOp.SET_COMMENT, revision.op)
        assertEquals("Аренда", revision.commentBefore)
        assertEquals("Аренда + коммуналка", revision.commentAfter)
    }

    @Test
    fun `комментарий можно очистить`() = runTest {
        val cell = cellWith(1_000, comment = "Лишний текст")

        val result = fx.editCell(cell.id, comment = CommentChange.Set("   "))

        assertNull(result.cell.comment)
        assertEquals(RevisionOp.SET_COMMENT, result.revisions.single().op)
    }

    @Test
    fun `Keep оставляет комментарий прежним`() = runTest {
        val cell = cellWith(1_000, comment = "Важно")

        val result = fx.editCell(cell.id, amount = Money(2_000), comment = CommentChange.Keep)

        assertEquals("Важно", result.cell.comment)
    }

    @Test
    fun `одновременная правка суммы и комментария даёт две ревизии`() = runTest {
        val cell = cellWith(10_000, comment = "Было")

        val result = fx.editCell(cell.id, amount = Money(12_000), comment = CommentChange.Set("Стало"))

        assertEquals(2, result.revisions.size, "Журнал обязан отразить каждое изменение")
        assertEquals(listOf(RevisionOp.SET_AMOUNT, RevisionOp.SET_COMMENT), result.revisions.map { it.op })
        assertEquals(cell.rowVersion + 2, result.cell.rowVersion)
        assertEquals(Money(12_000), result.cell.amount)
        assertEquals("Стало", result.cell.comment)

        // Ревизии обязаны быть записаны в хранилище, а не только возвращены: историю
        // ячейки и Undo читает экран именно из журнала (SPEC.md §7).
        val journalOps = fx.ledger.revisionsOf(cell.id).map { it.op }
        assertTrue(
            journalOps.containsAll(listOf(RevisionOp.SET_AMOUNT, RevisionOp.SET_COMMENT)),
            "Правка не попала в журнал: $journalOps",
        )
    }

    @Test
    fun `нулевая сумма отклоняется`() = runTest {
        val cell = cellWith(10_000)

        assertFailsWith<IllegalArgumentException> { fx.editCell(cell.id, amount = Money.ZERO) }
    }

    @Test
    fun `правка несуществующей ячейки отклоняется`() = runTest {
        assertFailsWith<IllegalArgumentException> { fx.editCell("no-such-cell", amount = Money(100)) }
    }

    @Test
    fun `удаление ставит tombstone и убирает ячейку из выборок`() = runTest {
        val cell = cellWith(10_000)

        val result = fx.deleteCell(cell.id)

        assertTrue(result.cell.isDeleted)
        assertEquals(RevisionOp.DELETE, result.revision.op)
        assertNull(fx.ledger.findCell(cell.categoryId, feb), "Ячейка не должна находиться")
        assertTrue(fx.ledger.cellsForPeriod(LedgerSection.EXPENSE, feb).isEmpty())
        assertEquals(1, fx.ledger.cells.size, "Строка остаётся в БД как tombstone")
    }

    @Test
    fun `после удаления ту же категорию и месяц можно завести заново`() = runTest {
        // Ключевой сценарий для частичного уникального индекса WHERE deleted_at IS NULL.
        val cell = cellWith(10_000)
        fx.deleteCell(cell.id)

        val recreated = fx.recordAmount(
            AmountInput(LedgerSection.EXPENSE, cell.categoryId, feb, Money(3_000)),
        )

        assertIs<RecordAmountResult.Created>(recreated)
        assertEquals(Money(3_000), recreated.cell.amount, "Новая ячейка не наследует старую сумму")
        assertEquals(2, fx.ledger.cells.size, "Tombstone сохранён для истории и синхронизации")
        assertEquals(1, fx.ledger.cellsForPeriod(LedgerSection.EXPENSE, feb).size)
    }

    @Test
    fun `правка удалённой ячейки отклоняется`() = runTest {
        val cell = cellWith(10_000)
        fx.deleteCell(cell.id)

        val error = assertFailsWith<IllegalArgumentException> { fx.editCell(cell.id, amount = Money(100)) }
        assertTrue(error.message.orEmpty().contains("удалена"), "Сообщение: ${error.message}")
    }

    @Test
    fun `восстановление возвращает ячейку в список и пишет UNDELETE`() = runTest {
        val cell = cellWith(10_000)
        fx.deleteCell(cell.id)

        val restored = fx.restoreCell(cell.id)

        assertTrue(!restored.cell.isDeleted)
        assertEquals(RevisionOp.UNDELETE, restored.revision.op)
        assertEquals(Money(10_000), fx.ledger.findCell(cell.categoryId, feb)?.amount)
    }

    @Test
    fun `двойное удаление и восстановление живой ячейки отклоняются`() = runTest {
        val cell = cellWith(10_000)

        assertFailsWith<IllegalArgumentException> { fx.restoreCell(cell.id) }
        fx.deleteCell(cell.id)
        assertFailsWith<IllegalArgumentException> { fx.deleteCell(cell.id) }
    }
}
