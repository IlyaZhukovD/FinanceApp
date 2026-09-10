package app.finance.core.model

import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PeriodTest {

    @Test
    fun `ключ периода и обратное преобразование`() {
        assertEquals(202_602, Period(2026, 2).key)
        assertEquals(Period(2026, 2), Period.ofKey(202_602))
        assertEquals(Period(2026, 12), Period.ofKey(202_612))
        assertEquals(Period(2000, 1), Period.ofKey(200_001))
    }

    @Test
    fun `недопустимые значения отклоняются`() {
        assertFailsWith<IllegalArgumentException> { Period(2026, 0) }
        assertFailsWith<IllegalArgumentException> { Period(2026, 13) }
        assertFailsWith<IllegalArgumentException> { Period(1999, 5) }
        assertFailsWith<IllegalArgumentException> { Period(2101, 5) }
        assertFailsWith<IllegalArgumentException> { Period.ofKey(-1) }
        assertFailsWith<IllegalArgumentException> { Period.ofKey(202_613) }
    }

    @Test
    fun `сдвиг на месяцы пересекает границу года в обе стороны`() {
        assertEquals(Period(2026, 1), Period(2025, 12).plusMonths(1))
        assertEquals(Period(2025, 12), Period(2026, 1).minusMonths(1))
        assertEquals(Period(2027, 2), Period(2026, 2).plusMonths(12))
        assertEquals(Period(2025, 2), Period(2026, 2).minusMonths(12))
        assertEquals(Period(2026, 2), Period(2026, 2).plusMonths(0))
        assertEquals(Period(2024, 11), Period(2026, 2).minusMonths(15))
    }

    @Test
    fun `monthsUntil считает расстояние со знаком`() {
        assertEquals(12, Period(2025, 2).monthsUntil(Period(2026, 2)))
        assertEquals(-12, Period(2026, 2).monthsUntil(Period(2025, 2)))
        assertEquals(0, Period(2026, 2).monthsUntil(Period(2026, 2)))
        assertEquals(1, Period(2026, 1).monthsUntil(Period(2026, 2)))
    }

    @Test
    fun `monthsTo включает границы и пуст для обратного диапазона`() {
        val range = Period(2025, 11).monthsTo(Period(2026, 2))
        assertEquals(
            listOf(Period(2025, 11), Period(2025, 12), Period(2026, 1), Period(2026, 2)),
            range,
        )
        assertEquals(listOf(Period(2026, 2)), Period(2026, 2).monthsTo(Period(2026, 2)))
        assertTrue(Period(2026, 2).monthsTo(Period(2025, 1)).isEmpty())
    }

    @Test
    fun `порядок и формат строки`() {
        assertTrue(Period(2025, 12) < Period(2026, 1))
        assertEquals("2026-02", Period(2026, 2).toString())
        assertEquals("2000-01", Period(2000, 1).toString())
    }

    @Test
    fun `withYear и withMonth сохраняют второе поле`() {
        assertEquals(Period(2030, 2), Period(2026, 2).withYear(2030))
        assertEquals(Period(2026, 9), Period(2026, 2).withMonth(9))
        assertFailsWith<IllegalArgumentException> { Period(2026, 2).withMonth(13) }
    }

    @Test
    fun `now использует инжектированные часы и зону`() {
        val zone = ZoneId.of("Europe/Moscow")
        val clock = Clock.fixed(Instant.parse("2026-02-15T21:30:00Z"), zone)
        assertEquals(Period(2026, 2), Period.now(clock, zone))

        // 23:30 UTC 28 февраля в Москве уже 1 марта — проверяем, что зона учитывается.
        val lateUtc = Clock.fixed(Instant.parse("2026-02-28T23:30:00Z"), zone)
        assertEquals(Period(2026, 3), Period.now(lateUtc, zone))
    }

    @Test
    fun `конвертация из java time YearMonth`() {
        assertEquals(Period(2026, 2), Period.of(YearMonth.of(2026, 2)))
    }
}
