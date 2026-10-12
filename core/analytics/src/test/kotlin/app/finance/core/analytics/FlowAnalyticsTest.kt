package app.finance.core.analytics

import app.finance.core.model.LedgerSection
import app.finance.core.model.Period
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden-тесты серий потоков (F6.1.1, F6.1.2, F6.1.3) — F6.4 требует, чтобы расчёты были
 * чистыми функциями с зафиксированными ожидаемыми значениями.
 *
 * Набор данных: расходы декабрь 2025 — февраль 2026 плюс «шум», который обязан
 * отфильтроваться (другая секция, другая валюта, удалённая ячейка, месяц вне диапазона).
 */
class FlowSeriesTest {

    private val range = PeriodRange(from = Period(2025, 12), to = Period(2026, 2))

    private val cells = listOf(
        cell("food", Period(2025, 12), 10_000),
        cell("food", Period(2026, 1), 20_000),
        cell("food", Period(2026, 2), 30_000),
        cell("transport", Period(2026, 1), 5_000),
        cell("food", Period(2025, 11), 99_999),
        cell("food", Period(2026, 2), 99_999, deleted = true),
        cell("food", Period(2026, 2), 99_999, currency = "USD"),
        cell("salary", Period(2026, 2), 99_999, section = LedgerSection.INCOME),
    )

    @Test
    fun `отсутствующий месяц потока - ноль, а не gap`() {
        val points = FlowSeries.monthlyTotals(cells, LedgerSection.EXPENSE, range)

        assertEquals(
            listOf(rub(10_000), rub(25_000), rub(30_000)),
            points.map { it.amount },
        )
        assertTrue(points.none { it.isGap }, "У потоков gap не бывает: ноль — legitimate значение")
    }

    @Test
    fun `итог диапазона игнорирует чужую секцию, валюту, удаление и месяцы вне диапазона`() {
        assertEquals(rub(65_000), FlowSeries.total(cells, LedgerSection.EXPENSE, range))
    }

    @Test
    fun `серии категорий идут по убыванию итога и имеют одинаковую длину`() {
        val series = FlowSeries.byCategory(cells, LedgerSection.EXPENSE, range)

        assertEquals(listOf("food", "transport"), series.map { it.categoryId })
        assertTrue(series.all { it.points.size == 3 })
        assertEquals(rub(60_000), series.first().total)
    }

    @Test
    fun `в серии категории пропущенный месяц заменяется нулём`() {
        val series = FlowSeries.byCategory(cells, LedgerSection.EXPENSE, range)
        val transport = series.first { it.categoryId == "transport" }

        assertEquals(
            listOf(rub(0), rub(5_000), rub(0)),
            transport.points.map { it.amount },
        )
    }

    @Test
    fun `доли округляются HALF_UP и в сумме дают сто процентов`() {
        val breakdown = Breakdown.of(cells, LedgerSection.EXPENSE, range)

        assertEquals(
            listOf(CategoryShare("food", rub(60_000), 92), CategoryShare("transport", rub(5_000), 8)),
            breakdown,
        )
        assertEquals(100, breakdown.sumOf { it.sharePercent ?: 0 })
    }

    @Test
    fun `нулевой итог не даёт доли вместо ложных нулей процентов`() {
        val breakdown = Breakdown.of(
            listOf(cell("food", Period(2025, 12), 0)),
            LedgerSection.EXPENSE,
            range,
        )

        assertEquals(1, breakdown.size)
        assertEquals(rub(0), breakdown.single().amount)
        assertNull(breakdown.single().sharePercent)
    }

    @Test
    fun `топ-N ограничивает список, а неположительное N даёт пустой`() {
        val breakdown = Breakdown.of(cells, LedgerSection.EXPENSE, range)

        assertEquals(listOf("food"), Breakdown.topN(breakdown, 1).map { it.categoryId })
        assertTrue(Breakdown.topN(breakdown, 0).isEmpty())
    }

    @Test
    fun `пустой срез даёт нули по всем месяцам диапазона`() {
        val points = FlowSeries.monthlyTotals(emptyList(), LedgerSection.EXPENSE, range)

        assertEquals(3, points.size)
        assertFalse(points.any { it.isGap })
        assertTrue(points.all { it.amount == rub(0) })
        assertTrue(FlowSeries.byCategory(emptyList(), LedgerSection.EXPENSE, range).isEmpty())
    }
}

/** Golden-тесты cash flow (F6.1.5). */
class CashFlowTest {

    private val range = PeriodRange(from = Period(2025, 12), to = Period(2026, 2))

    private val incomes = listOf(
        cell("salary", Period(2025, 12), 100_000, section = LedgerSection.INCOME),
        cell("salary", Period(2026, 1), 100_000, section = LedgerSection.INCOME),
        cell("salary", Period(2026, 2), 120_000, section = LedgerSection.INCOME),
    )

    private val expenses = listOf(
        cell("food", Period(2025, 12), 10_000),
        cell("food", Period(2026, 1), 25_000),
        cell("food", Period(2026, 2), 30_000),
    )

    @Test
    fun `дельта и норма сбережений по месяцам`() {
        val points = CashFlow.monthly(incomes, expenses, range)

        assertEquals(
            listOf(
                Triple(rub(90_000), 90, Period(2025, 12)),
                Triple(rub(75_000), 75, Period(2026, 1)),
                Triple(rub(90_000), 75, Period(2026, 2)),
            ),
            points.map { Triple(it.delta, it.savingsRatePercent, it.period) },
        )
    }

    @Test
    fun `итоги диапазона считают месяцы с профицитом`() {
        val summary = CashFlow.summary(incomes, expenses, range)

        assertEquals(rub(320_000), summary.income)
        assertEquals(rub(65_000), summary.expense)
        assertEquals(rub(255_000), summary.delta)
        // 255000 / 320000 = 79,6875% -> HALF_UP -> 80.
        assertEquals(80, summary.savingsRatePercent)
        assertEquals(3, summary.months)
        assertEquals(3, summary.surplusMonths)
    }

    @Test
    fun `расходы больше доходов дают отрицательную дельту и норму`() {
        val points = CashFlow.monthly(
            incomes = listOf(cell("salary", Period(2026, 2), 120_000, section = LedgerSection.INCOME)),
            expenses = listOf(cell("food", Period(2026, 2), 130_000)),
            range = PeriodRange(from = Period(2026, 2), to = Period(2026, 2)),
        )

        assertEquals(rub(-10_000), points.single().delta)
        // -10000 / 120000 = -8,33% -> HALF_UP -> -8.
        assertEquals(-8, points.single().savingsRatePercent)
    }

    @Test
    fun `без доходов норма сбережений не определена`() {
        val points = CashFlow.monthly(
            incomes = emptyList(),
            expenses = listOf(cell("food", Period(2026, 2), 5_000)),
            range = PeriodRange(from = Period(2026, 2), to = Period(2026, 2)),
        )

        assertEquals(rub(0), points.single().income)
        assertEquals(rub(-5_000), points.single().delta)
        assertNull(points.single().savingsRatePercent)
    }
}
