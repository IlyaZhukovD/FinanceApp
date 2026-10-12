package app.finance.core.analytics

import app.finance.core.model.Period
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Пресеты периодов (F6.1.4).
 *
 * Golden-значения зафиксированы вручную: диапазон `[from, to]` включительно и переход
 * через границу года — типичные источники ошибки «на единицу» (SPEC.md §5).
 */
class PeriodRangeTest {

    @Test
    fun `последние три месяца заканчиваются на якоре и включают его`() {
        val range = PeriodRange.lastMonths(count = 3, anchor = Period(2026, 2))

        assertEquals(Period(2025, 12), range.from)
        assertEquals(Period(2026, 2), range.to)
        assertEquals(
            listOf(Period(2025, 12), Period(2026, 1), Period(2026, 2)),
            range.months,
        )
        assertEquals(3, range.length)
    }

    @Test
    fun `последние двенадцать месяцев переходят через границу года`() {
        val range = PeriodRange.lastMonths(count = 12, anchor = Period(2026, 2))

        assertEquals(Period(2025, 3), range.from)
        assertEquals(12, range.length)
    }

    @Test
    fun `один месяц - это диапазон из единственного периода`() {
        val range = PeriodRange.lastMonths(count = 1, anchor = Period(2026, 2))

        assertEquals(Period(2026, 2), range.from)
        assertEquals(Period(2026, 2), range.to)
        assertEquals(1, range.length)
    }

    @Test
    fun `ytd считается от января текущего года до текущего месяца`() {
        val range = PeriodRange.yearToDate(Period(2026, 2))

        assertEquals(Period(2026, 1), range.from)
        assertEquals(Period(2026, 2), range.to)
        assertEquals(2, range.length)
    }

    @Test
    fun `прошлый год - полный календарный год`() {
        val range = PeriodRange.previousYear(Period(2026, 2))

        assertEquals(Period(2025, 1), range.from)
        assertEquals(Period(2025, 12), range.to)
        assertEquals(12, range.length)
    }

    @Test
    fun `contains включает границы и исключает всё вне диапазона`() {
        val range = PeriodRange(from = Period(2026, 1), to = Period(2026, 3))

        assertTrue(range.contains(Period(2026, 1)))
        assertTrue(range.contains(Period(2026, 2)))
        assertTrue(range.contains(Period(2026, 3)))
        assertFalse(range.contains(Period(2025, 12)))
        assertFalse(range.contains(Period(2026, 4)))
    }

    @Test
    fun `сдвиг назад даёт соседний период той же длины для сравнения A vs B`() {
        val current = PeriodRange(from = Period(2025, 12), to = Period(2026, 2))

        val previous = current.shiftedBackBy(3)

        assertEquals(PeriodRange(from = Period(2025, 9), to = Period(2025, 11)), previous)
        assertEquals(current.length, previous.length)
    }

    @Test
    fun `перепутанные границы диапазона отвергаются`() {
        assertFailsWith<IllegalArgumentException> {
            PeriodRange(from = Period(2026, 3), to = Period(2026, 1))
        }
    }

    @Test
    fun `неположительная длина пресета отвергается`() {
        assertFailsWith<IllegalArgumentException> {
            PeriodRange.lastMonths(count = 0, anchor = Period(2026, 2))
        }
    }
}
