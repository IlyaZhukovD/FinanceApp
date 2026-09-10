package app.finance.core.domain

import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import app.finance.core.model.RevisionOp
import app.finance.core.model.RevisionSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверяет главное требование продукта: одна ячейка на категорию и месяц,
 * суммы аккумулируются, комментарии сливаются (SPEC.md F1.5, D-02, D-03).
 */
class RecordAmountTest {

    private val fx = DomainFixture()
    private val jan = Period(2026, 1)
    private val feb = Period(2026, 2)

    private fun input(
        categoryId: String,
        period: Period = feb,
        amount: Money = Money(1_000),
        comment: String? = null,
        section: LedgerSection = LedgerSection.EXPENSE,
        key: String? = null,
        source: RevisionSource = RevisionSource.MANUAL,
    ) = AmountInput(
        section = section,
        categoryId = categoryId,
        period = period,
        amount = amount,
        comment = comment,
        source = source,
        idempotencyKey = key,
    )

    @Test
    fun `первый ввод создаёт ячейку с суммой и комментарием`() = runTest {
        val food = fx.category("food", "Продукты")

        val result = fx.recordAmount(input(food.id, amount = Money(32_400), comment = "Большая закупка"))

        val created = assertIs<RecordAmountResult.Created>(result)
        assertEquals(Money(32_400), created.cell.amount)
        assertEquals("Большая закупка", created.cell.comment)
        assertEquals(feb, created.cell.period)
        assertEquals(LedgerSection.EXPENSE, created.cell.section)
        assertEquals(1, created.cell.rowVersion)
        assertEquals(RevisionOp.CREATE, created.revision.op)
        assertEquals(Money(32_400), created.revision.delta)
        assertNull(created.revision.amountBefore)
        assertEquals(1, fx.ledger.cells.size)
    }

    @Test
    fun `повторный ввод в ту же категорию и месяц аккумулируется, а не дублирует строку`() = runTest {
        val food = fx.category("food", "Продукты")

        fx.recordAmount(input(food.id, amount = Money(10_000)))
        val second = fx.recordAmount(input(food.id, amount = Money(4_500)))

        val accumulated = assertIs<RecordAmountResult.Accumulated>(second)
        assertEquals(1, fx.ledger.cells.size, "Ячейка должна остаться одна")
        assertEquals(Money(14_500), accumulated.cell.amount)
        assertEquals(2, accumulated.cell.rowVersion)
        assertEquals(RevisionOp.ADD, accumulated.revision.op)
        assertEquals(Money(10_000), accumulated.revision.amountBefore)
        assertEquals(Money(14_500), accumulated.revision.amountAfter)
        assertEquals(Money(4_500), accumulated.revision.delta)
    }

    @Test
    fun `несколько вводов за месяц накапливаются последовательно`() = runTest {
        val transport = fx.category("transport", "Транспорт")

        fx.recordAmount(input(transport.id, amount = Money(1_000)))
        fx.recordAmount(input(transport.id, amount = Money(2_500)))
        val third = fx.recordAmount(input(transport.id, amount = Money(500)))

        assertEquals(Money(4_000), third.cell.amount)
        assertEquals(3, third.cell.rowVersion)
        assertEquals(1, fx.ledger.cells.size)
        assertEquals(3, fx.ledger.revisions.size, "Каждый ввод оставляет запись в журнале")
    }

    @Test
    fun `другой месяц даёт отдельную ячейку`() = runTest {
        val food = fx.category("food", "Продукты")

        fx.recordAmount(input(food.id, period = jan, amount = Money(10_000)))
        fx.recordAmount(input(food.id, period = feb, amount = Money(20_000)))

        assertEquals(2, fx.ledger.cells.size)
        assertEquals(Money(10_000), fx.ledger.findCell(food.id, jan)?.amount)
        assertEquals(Money(20_000), fx.ledger.findCell(food.id, feb)?.amount)
    }

    @Test
    fun `новый комментарий дописывается к существующему`() = runTest {
        val food = fx.category("food", "Продукты")

        fx.recordAmount(input(food.id, amount = Money(10_000), comment = "Большая закупка"))
        val second = fx.recordAmount(input(food.id, amount = Money(2_000), comment = "Продукты на дачу"))

        assertEquals("Большая закупка\nПродукты на дачу", second.cell.comment)
        assertEquals("Большая закупка", second.revision.commentBefore)
        assertEquals("Большая закупка\nПродукты на дачу", second.revision.commentAfter)
    }

    @Test
    fun `ввод без комментария не затирает существующий`() = runTest {
        val food = fx.category("food", "Продукты")

        fx.recordAmount(input(food.id, amount = Money(10_000), comment = "Большая закупка"))
        val second = fx.recordAmount(input(food.id, amount = Money(2_000)))

        assertEquals("Большая закупка", second.cell.comment)
    }

    @Test
    fun `повтор того же комментария не дублируется`() = runTest {
        val rent = fx.category("rent", "Жильё")

        fx.recordAmount(input(rent.id, amount = Money(25_000), comment = "Аренда"))
        val second = fx.recordAmount(input(rent.id, amount = Money(1_000), comment = "Аренда"))

        assertEquals("Аренда", second.cell.comment)
        assertEquals(Money(26_000), second.cell.amount, "Сумма при этом всё равно аккумулируется")
    }

    @Test
    fun `отрицательная сумма работает как сторно`() = runTest {
        val food = fx.category("food", "Продукты")

        fx.recordAmount(input(food.id, amount = Money(32_400)))
        val correction = fx.recordAmount(input(food.id, amount = Money(-2_400)))

        assertEquals(Money(30_000), correction.cell.amount)
        assertEquals(Money(-2_400), correction.revision.delta)
    }

    @Test
    fun `архивная категория отклоняется`() = runTest {
        val old = fx.category("old", "Устаревшая", archived = true)

        val error = assertFailsWith<IllegalArgumentException> { fx.recordAmount(input(old.id)) }
        assertTrue(error.message.orEmpty().contains("архиве"), "Сообщение: ${error.message}")
        assertEquals(0, fx.ledger.cells.size)
    }

    @Test
    fun `категория другой секции отклоняется`() = runTest {
        val salary = fx.category("salary", "Зарплата", section = CategorySection.INCOME)

        val error = assertFailsWith<IllegalArgumentException> {
            fx.recordAmount(input(salary.id, section = LedgerSection.EXPENSE))
        }
        assertTrue(error.message.orEmpty().contains("секции"), "Сообщение: ${error.message}")
    }

    @Test
    fun `нулевая сумма отклоняется`() = runTest {
        val food = fx.category("food", "Продукты")

        assertFailsWith<IllegalArgumentException> { fx.recordAmount(input(food.id, amount = Money.ZERO)) }
        assertEquals(0, fx.ledger.cells.size)
    }

    @Test
    fun `несовпадение валют отклоняется, а не складывается молча`() = runTest {
        val food = fx.category("food", "Продукты")

        fx.recordAmount(input(food.id, amount = Money(10_000)))
        assertFailsWith<IllegalArgumentException> {
            fx.recordAmount(input(food.id, amount = Money(100, "USD")))
        }
        assertEquals(Money(10_000), fx.ledger.findCell(food.id, feb)?.amount)
    }

    @Test
    fun `ключ идемпотентности защищает от двойного начисления при повторном импорте`() = runTest {
        val food = fx.category("food", "Продукты")

        val first = fx.recordAmount(
            input(food.id, amount = Money(5_000), key = "notion-row-42", source = RevisionSource.IMPORT),
        )
        val repeat = fx.recordAmount(
            input(food.id, amount = Money(5_000), key = "notion-row-42", source = RevisionSource.IMPORT),
        )

        assertIs<RecordAmountResult.Created>(first)
        assertIs<RecordAmountResult.AlreadyApplied>(repeat)
        assertEquals(Money(5_000), fx.ledger.findCell(food.id, feb)?.amount, "Сумма не должна удвоиться")
        assertEquals(1, fx.ledger.revisions.size)
    }

    @Test
    fun `мутация и ревизия выполняются в одной транзакции`() = runTest {
        val food = fx.category("food", "Продукты")

        fx.recordAmount(input(food.id, amount = Money(1_000)))

        assertEquals(1, fx.transactions.transactionsStarted)
    }

    @Test
    fun `доходы идут в свою секцию и не смешиваются с расходами`() = runTest {
        val salary = fx.category("salary", "Зарплата", section = CategorySection.INCOME)

        val result = fx.recordAmount(
            input(salary.id, amount = Money(250_000), section = LedgerSection.INCOME),
        )

        assertEquals(LedgerSection.INCOME, result.cell.section)
        assertEquals(1, fx.ledger.cellsForPeriod(LedgerSection.INCOME, feb).size)
        assertEquals(0, fx.ledger.cellsForPeriod(LedgerSection.EXPENSE, feb).size)
    }
}
