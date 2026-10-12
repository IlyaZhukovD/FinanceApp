package app.finance.core.analytics

import app.finance.core.model.LedgerSection
import app.finance.core.model.Period
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Golden-тесты статистики по категориям (F6.5). */
class StatsTest {

    private val range = PeriodRange(from = Period(2025, 12), to = Period(2026, 2))

    @Test
    fun `среднее, медиана и границы по месяцам с данными`() {
        val cells = listOf(
            cell("food", Period(2025, 12), 10_000),
            cell("food", Period(2026, 1), 20_000),
            cell("food", Period(2026, 2), 30_000),
            cell("transport", Period(2026, 1), 5_000),
        )

        val stats = Stats.byCategory(cells, LedgerSection.EXPENSE, range)

        assertEquals(listOf("food", "transport"), stats.map { it.categoryId })

        val food = stats.first()
        assertEquals(rub(60_000), food.total)
        assertEquals(3, food.monthCount)
        assertEquals(rub(20_000), food.mean)
        assertEquals(rub(20_000), food.median)
        assertEquals(rub(10_000), food.min)
        assertEquals(rub(30_000), food.max)

        // Категория встречалась один раз: нулевые месяцы в статистику не входят.
        val transport = stats.last()
        assertEquals(1, transport.monthCount)
        assertEquals(rub(5_000), transport.mean)
        assertEquals(rub(5_000), transport.median)
        assertEquals(rub(5_000), transport.min)
        assertEquals(rub(5_000), transport.max)
    }

    @Test
    fun `медиана чётного числа значений - среднее двух средних`() {
        val stats = Stats.byCategory(
            listOf(
                cell("food", Period(2025, 12), 10_000),
                cell("food", Period(2026, 2), 30_000),
            ),
            LedgerSection.EXPENSE,
            range,
        ).single()

        assertEquals(2, stats.monthCount)
        assertEquals(rub(20_000), stats.median)
    }

    @Test
    fun `половина копейки округляется HALF_UP`() {
        val stats = Stats.byCategory(
            listOf(
                cell("food", Period(2025, 12), 100),
                cell("food", Period(2026, 1), 101),
            ),
            LedgerSection.EXPENSE,
            range,
        ).single()

        // Медиана (100 + 101) / 2 = 100,5 -> HALF_UP -> 101.
        assertEquals(rub(101), stats.median)
    }

    @Test
    fun `среднее округляется HALF_UP до целой копейки`() {
        val stats = Stats.byCategory(
            listOf(
                cell("food", Period(2025, 12), 34),
                cell("food", Period(2026, 1), 33),
                cell("food", Period(2026, 2), 33),
            ),
            LedgerSection.EXPENSE,
            range,
        ).single()

        assertEquals(rub(100), stats.total)
        // 100 / 3 = 33,33 -> HALF_UP -> 33.
        assertEquals(rub(33), stats.mean)
    }
}

/** Golden-тесты MoM и YoY (F6.5). */
class TrendsTest {

    private val series = listOf(
        SeriesPoint(Period(2025, 2), rub(15_000)),
        SeriesPoint(Period(2026, 1), rub(25_000)),
        SeriesPoint(Period(2026, 2), rub(30_000)),
    )

    @Test
    fun `mom сравнивает с предыдущим месяцем`() {
        val change = Trends.mom(series, Period(2026, 2))

        assertEquals(rub(5_000), change.absolute)
        assertEquals(20, change.percent)
    }

    @Test
    fun `yoy сравнивает с тем же месяцем годом ранее`() {
        val change = Trends.yoy(series, Period(2026, 2))

        assertEquals(rub(15_000), change.absolute)
        assertEquals(100, change.percent)
    }

    @Test
    fun `нет базового месяца - изменение неизвестно, а не ноль`() {
        val change = Trends.mom(series, Period(2026, 1))

        assertTrue(change.isUnknown, "Декабря 2025 в серии нет, поэтому MoM не определён")
        assertNull(change.absolute)
        assertNull(change.percent)
    }

    @Test
    fun `нулевая база не даёт процента`() {
        val zeroBase = listOf(
            SeriesPoint(Period(2025, 12), rub(0)),
            SeriesPoint(Period(2026, 1), rub(25_000)),
        )

        val change = Trends.mom(zeroBase, Period(2026, 1))

        assertEquals(rub(25_000), change.absolute)
        assertNull(change.percent)
    }

    @Test
    fun `gap в серии делает изменение неизвестным`() {
        val withGap = listOf(
            SeriesPoint(Period(2026, 1), null),
            SeriesPoint(Period(2026, 2), rub(30_000)),
        )

        assertTrue(Trends.mom(withGap, Period(2026, 2)).isUnknown)
    }
}
