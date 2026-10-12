package app.finance.core.analytics

import app.finance.core.model.AssetSnapshot
import app.finance.core.model.Money
import app.finance.core.model.Period
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden-тесты серии капитала (F6.1.6).
 *
 * Проверяются два правила, которые легко нарушить молча:
 * пропуск месяца — gap, а не ноль (SPEC.md §5), и прирост считается только от строго
 * предыдущего месяца `M − 1` (F4.1), поэтому после gap он не определён.
 */
class NetWorthSeriesTest {

    private val range = PeriodRange(from = Period(2025, 12), to = Period(2026, 3))

    private val snapshots = listOf(
        snapshot("card", Period(2025, 12), balance = 10_000, contribution = 2_000),
        snapshot("broker", Period(2025, 12), balance = 20_000),
        snapshot("card", Period(2026, 1), balance = 30_000, contribution = 5_000),
        snapshot("broker", Period(2026, 1), balance = 20_000),
        // Февраль пропущен намеренно.
        snapshot("card", Period(2026, 3), balance = 33_000),
        snapshot("broker", Period(2026, 3), balance = 20_000),
    )

    private fun monthly() = NetWorthSeries.monthly(snapshots, range)

    @Test
    fun `капитал месяца - сумма балансов всех счетов`() {
        val points = monthly()

        assertEquals(rub(30_000), points[0].netWorth)
        assertEquals(rub(50_000), points[1].netWorth)
        assertEquals(rub(53_000), points[3].netWorth)
    }

    @Test
    fun `взносы месяца суммируются, а их отсутствие даёт ноль`() {
        val points = monthly()

        assertEquals(rub(2_000), points[0].contributions)
        assertEquals(rub(5_000), points[1].contributions)
        assertEquals(rub(0), points[3].contributions)
    }

    @Test
    fun `прирост равен дельте капитала минус взносы`() {
        val points = monthly()

        // Первый месяц диапазона: сравнивать не с чем.
        assertNull(points[0].gain)
        // (50 000 − 30 000) − 5 000 = 15 000.
        assertEquals(rub(15_000), points[1].gain)
    }

    @Test
    fun `пропущенный месяц - gap без значений, а не нулевой капитал`() {
        val february = monthly()[2]

        assertTrue(february.isGap)
        assertNull(february.netWorth)
        assertNull(february.contributions)
        assertNull(february.gain)
    }

    @Test
    fun `после gap прирост не определён, даже если более ранний месяц известен`() {
        val march = monthly()[3]

        assertEquals(rub(53_000), march.netWorth)
        assertNull(march.gain, "Иначе прирост охватил бы два месяца и выглядел бы как мартовский")
    }

    @Test
    fun `чужая валюта и удалённые снапшоты не попадают в серию`() {
        val single = PeriodRange(from = Period(2026, 1), to = Period(2026, 1))
        val noisy = snapshots + listOf(
            snapshot("card", Period(2026, 1), balance = 99_999, currency = "USD"),
            snapshot("card", Period(2026, 1), balance = 99_999, deleted = true),
            snapshot("card", Period(2026, 2), balance = 99_999),
        )

        val points = NetWorthSeries.monthly(noisy, single)

        assertEquals(1, points.size)
        assertEquals(rub(50_000), points.single().netWorth)
    }

    @Test
    fun `отрицательный баланс кредита уменьшает капитал`() {
        val withCredit = listOf(
            snapshot("card", Period(2026, 1), balance = 30_000),
            snapshot("credit", Period(2026, 1), balance = -5_000),
        )

        val points = NetWorthSeries.monthly(
            withCredit,
            PeriodRange(from = Period(2026, 1), to = Period(2026, 1)),
        )

        assertEquals(rub(25_000), points.single().netWorth)
    }

    private fun snapshot(
        accountId: String,
        period: Period,
        balance: Long,
        contribution: Long? = null,
        currency: String = Money.DEFAULT_CURRENCY,
        deleted: Boolean = false,
    ): AssetSnapshot = AssetSnapshot(
        id = "$accountId-${period.key}",
        accountId = accountId,
        period = period,
        balance = Money(balance, currency),
        contribution = contribution?.let { Money(it, currency) },
        createdAtUtc = 0L,
        updatedAtUtc = 0L,
        deletedAtUtc = if (deleted) 1L else null,
    )
}
