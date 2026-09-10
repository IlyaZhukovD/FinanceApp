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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuplicatePeriodTest {

    private val fx = DomainFixture()
    private val jan = Period(2026, 1)
    private val feb = Period(2026, 2)

    private suspend fun record(
        categoryId: String,
        period: Period,
        amount: Long,
        comment: String? = null,
        section: LedgerSection = LedgerSection.EXPENSE,
    ) {
        fx.recordAmount(AmountInput(section, categoryId, period, Money(amount), comment))
    }

    @Test
    fun `копирует все ячейки месяца в пустой целевой месяц`() = runTest {
        fx.category("food", "Продукты")
        fx.category("rent", "Жильё")
        record("food", jan, 10_000)
        record("rent", jan, 25_000)

        val report = fx.duplicatePeriod(LedgerSection.EXPENSE, jan, feb)

        assertEquals(2, report.created)
        assertEquals(2, report.changed)
        assertEquals(0, report.skippedExisting)
        assertEquals(Money(10_000), fx.ledger.findCell("food", feb)?.amount)
        assertEquals(Money(25_000), fx.ledger.findCell("rent", feb)?.amount)
        assertEquals(2, fx.ledger.cellsForPeriod(LedgerSection.EXPENSE, feb).size)
    }

    @Test
    fun `комментарии по умолчанию не переносятся`() = runTest {
        fx.category("food", "Продукты")
        record("food", jan, 10_000, comment = "Январская закупка")

        fx.duplicatePeriod(LedgerSection.EXPENSE, jan, feb)

        assertNull(fx.ledger.findCell("food", feb)?.comment)
    }

    @Test
    fun `комментарии переносятся при явном флаге`() = runTest {
        fx.category("rent", "Жильё")
        record("rent", jan, 25_000, comment = "Аренда")

        fx.duplicatePeriod(LedgerSection.EXPENSE, jan, feb, DuplicateOptions(copyComments = true))

        assertEquals("Аренда", fx.ledger.findCell("rent", feb)?.comment)
    }

    @Test
    fun `SKIP по умолчанию не трогает уже заполненные категории`() = runTest {
        fx.category("food", "Продукты")
        fx.category("rent", "Жильё")
        record("food", jan, 10_000)
        record("rent", jan, 25_000)
        record("food", feb, 1_000, comment = "Уже ввёл в феврале")

        val report = fx.duplicatePeriod(LedgerSection.EXPENSE, jan, feb)

        assertEquals(1, report.created, "Скопирована только незаполненная категория")
        assertEquals(1, report.skippedExisting)
        assertEquals(Money(1_000), fx.ledger.findCell("food", feb)?.amount, "Существующая сумма не изменена")
        assertEquals("Уже ввёл в феврале", fx.ledger.findCell("food", feb)?.comment)
    }

    @Test
    fun `ADD прибавляет скопированную сумму к существующей`() = runTest {
        fx.category("food", "Продукты")
        record("food", jan, 10_000)
        record("food", feb, 1_500)

        val report = fx.duplicatePeriod(
            LedgerSection.EXPENSE, jan, feb, DuplicateOptions(conflictPolicy = ConflictPolicy.ADD),
        )

        assertEquals(1, report.added)
        assertEquals(Money(11_500), fx.ledger.findCell("food", feb)?.amount)
    }

    @Test
    fun `OVERWRITE заменяет сумму, но сохраняет комментарий целевой ячейки`() = runTest {
        fx.category("food", "Продукты")
        record("food", jan, 10_000, comment = "Январский")
        record("food", feb, 1_500, comment = "Февральский")

        val report = fx.duplicatePeriod(
            LedgerSection.EXPENSE, jan, feb, DuplicateOptions(conflictPolicy = ConflictPolicy.OVERWRITE),
        )

        assertEquals(1, report.overwritten)
        val cell = fx.ledger.findCell("food", feb)
        assertEquals(Money(10_000), cell?.amount)
        assertEquals("Февральский", cell?.comment, "Комментарий не должен уничтожаться без явного запроса")
    }

    @Test
    fun `OVERWRITE с переносом комментариев заменяет и комментарий`() = runTest {
        fx.category("food", "Продукты")
        record("food", jan, 10_000, comment = "Январский")
        record("food", feb, 1_500, comment = "Февральский")

        fx.duplicatePeriod(
            LedgerSection.EXPENSE,
            jan,
            feb,
            DuplicateOptions(conflictPolicy = ConflictPolicy.OVERWRITE, copyComments = true),
        )

        assertEquals("Январский", fx.ledger.findCell("food", feb)?.comment)
    }

    @Test
    fun `архивные категории пропускаются и попадают в отчёт`() = runTest {
        fx.category("food", "Продукты")
        fx.category("old", "Устаревшая")
        record("food", jan, 10_000)
        record("old", jan, 5_000)
        // Категория была активной в январе и архивирована позже — типичная ситуация.
        val archived = fx.categories.byId("old")!!
        fx.categories.put(archived.copy(isArchived = true))

        val report = fx.duplicatePeriod(LedgerSection.EXPENSE, jan, feb)

        assertEquals(1, report.created)
        assertEquals(1, report.skippedArchived)
        assertNull(fx.ledger.findCell("old", feb), "Архивная категория не переносится")
    }

    @Test
    fun `ревизии помечены DUPLICATE и хранят месяц-источник`() = runTest {
        fx.category("food", "Продукты")
        record("food", jan, 10_000)

        fx.duplicatePeriod(LedgerSection.EXPENSE, jan, feb)

        val revision = fx.ledger.revisions.single { it.op == RevisionOp.DUPLICATE }
        assertEquals(RevisionSource.DUPLICATE, revision.source)
        assertEquals(jan, revision.sourcePeriod)
        assertEquals(Money(10_000), revision.amountAfter)
        assertEquals(Money(10_000), revision.delta)
    }

    @Test
    fun `пустой месяц-источник ничего не создаёт`() = runTest {
        fx.category("food", "Продукты")

        val report = fx.duplicatePeriod(LedgerSection.EXPENSE, jan, feb)

        assertEquals(0, report.changed)
        assertTrue(fx.ledger.cells.isEmpty())
    }

    @Test
    fun `расходы и доходы копируются независимо`() = runTest {
        fx.category("food", "Продукты", section = CategorySection.EXPENSE)
        fx.category("salary", "Зарплата", section = CategorySection.INCOME)
        record("food", jan, 10_000)
        record("salary", jan, 250_000, section = LedgerSection.INCOME)

        fx.duplicatePeriod(LedgerSection.EXPENSE, jan, feb)

        assertEquals(1, fx.ledger.cellsForPeriod(LedgerSection.EXPENSE, feb).size)
        assertTrue(
            fx.ledger.cellsForPeriod(LedgerSection.INCOME, feb).isEmpty(),
            "Доходы не должны копироваться операцией над расходами",
        )
    }

    @Test
    fun `совпадающие источник и назначение отклоняются`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            fx.duplicatePeriod(LedgerSection.EXPENSE, jan, jan)
        }
    }

    @Test
    fun `копирование в прошлый месяц тоже работает`() = runTest {
        fx.category("food", "Продукты")
        record("food", feb, 10_000)

        val report = fx.duplicatePeriod(LedgerSection.EXPENSE, feb, jan)

        assertEquals(1, report.created)
        assertEquals(Money(10_000), fx.ledger.findCell("food", jan)?.amount)
    }

    @Test
    fun `вся операция выполняется в одной транзакции`() = runTest {
        fx.category("food", "Продукты")
        fx.category("rent", "Жильё")
        record("food", jan, 10_000)
        record("rent", jan, 25_000)

        val before = fx.transactions.transactionsStarted
        fx.duplicatePeriod(LedgerSection.EXPENSE, jan, feb)

        assertEquals(before + 1, fx.transactions.transactionsStarted)
    }

    @Test
    fun `сводка для пользователя перечисляет все исходы`() = runTest {
        fx.category("food", "Продукты")
        fx.category("rent", "Жильё")
        record("food", jan, 10_000)
        record("rent", jan, 25_000)
        record("rent", feb, 25_000)

        val report = fx.duplicatePeriod(LedgerSection.EXPENSE, jan, feb)
        val summary = report.summaryRu()

        assertTrue(summary.contains("Скопировано: 1"), summary)
        assertTrue(summary.contains("пропущено (уже заполнено): 1"), summary)
        assertEquals(feb, report.target)
    }
}
