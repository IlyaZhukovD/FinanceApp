package app.finance.core.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Начисление процентов по вкладу с днём начисления (F4.6, D-31).
 *
 * Эталон — выписка Т-Банка по вкладу 11,5% годовых, открытому 6 августа 2026 на 6 месяцев:
 * 6 сен +1 049,39 ₽, 6 окт +1 025,45 ₽, 6 ноя +1 069,66 ₽, к 6 фев 2027 — 113 821,86 ₽.
 * Расхождение в копейку возможно: банк округляет начисление за каждый день.
 */
class DepositInterestTest {

    private val deposits = Category(
        id = "deposits",
        section = CategorySection.ASSET,
        name = "Вклады",
        color = "#336699",
        balancePolicy = AssetBalancePolicy.FIXED_RATE,
        createdAtUtc = 0,
        updatedAtUtc = 0,
    )

    private fun account(interestDay: Int?) = AssetAccount(
        id = "t",
        categoryId = deposits.id,
        name = "Вклад в Т",
        openedPeriod = Period(2026, 8),
        annualRateBp = 1150,
        termMonths = 6,
        interestDay = interestDay,
        createdAtUtc = 0,
        updatedAtUtc = 0,
    )

    private fun snapshot(period: Period, minor: Long) = AssetSnapshot(
        id = "s-$period",
        accountId = "t",
        period = period,
        balance = Money(minor, "RUB"),
        createdAtUtc = 0,
        updatedAtUtc = 0,
    )

    private fun valueAt(period: Period, interestDay: Int?, vararg snapshots: AssetSnapshot): AccountBalance =
        AssetBalances.resolve(period, listOf(account(interestDay)), listOf(deposits), snapshots.toList()).single()

    @Test
    fun `первое начисление совпадает с банком — 31 день`() {
        val sep = valueAt(Period(2026, 9), 6, snapshot(Period(2026, 8), 10_744_084))
        assertEquals(Money(104_939, "RUB"), sep.accruedInterest)
        assertEquals(Money(10_849_023, "RUB"), sep.balance)
        assertEquals(BalanceSource.PROJECTED, sep.source)
    }

    @Test
    fun `месяц из 30 дней даёт меньше процентов, чем из 31`() {
        val oct = valueAt(Period(2026, 10), 6, snapshot(Period(2026, 8), 10_744_084))
        assertEquals(Money(102_546, "RUB"), oct.accruedInterest) // банк: 1 025,45
    }

    @Test
    fun `прогноз на конец срока сходится с банком до копейки`() {
        val feb = valueAt(Period(2027, 2), 6, snapshot(Period(2026, 8), 10_744_084))
        assertEquals(Money(11_382_187, "RUB"), feb.balance) // банк: 113 821,86
        val mar = valueAt(Period(2027, 3), 6, snapshot(Period(2026, 8), 10_744_084))
        assertEquals(feb.balance, mar.balance)
        assertEquals(true, mar.isMatured)
    }

    @Test
    fun `введённый остаток важнее расчёта, прогноз идёт от него`() {
        val nov = valueAt(
            Period(2026, 11),
            6,
            snapshot(Period(2026, 8), 10_744_084),
            snapshot(Period(2026, 10), 10_951_568),
        )
        assertEquals(Money(106_965, "RUB"), nov.accruedInterest) // банк: 1 069,66
    }

    @Test
    fun `без дня начисления расчёт прежний — ставка на 12`() {
        val sep = valueAt(Period(2026, 9), null, snapshot(Period(2026, 8), 10_744_000))
        assertEquals(Money(102_963, "RUB"), sep.accruedInterest)
    }

    @Test
    fun `день начисления позже конца месяца переносится на последний день`() {
        assertEquals(LocalDate.of(2027, 2, 28), AssetBalances.creditDate(Period(2027, 2), 31))
        assertEquals(LocalDate.of(2028, 2, 29), AssetBalances.creditDate(Period(2028, 2), 30))
        // 31 янв -> 28 фев: 28 дней
        val interest = AssetBalances.dailyInterest(Money(36_500_000, "RUB"), 1000, 31, Period(2027, 2))
        assertEquals(Money(280_000, "RUB"), interest)
    }

    @Test
    fun `отрезок через Новый год делится по годам — високосный на 366`() {
        // 15 дек 2027 -> 15 янв 2028: 17 дней / 365 + 14 дней / 366
        val interest = AssetBalances.dailyInterest(Money(100_000_000, "RUB"), 1000, 15, Period(2028, 1))
        assertEquals(Money(848_267, "RUB"), interest)
    }
}

/**
 * Вклад с выплатой процентов в конце срока (D-32).
 *
 * Эталон — «Сейв» в Яндекс Пэй: 107 102,03 ₽ под 14% на 3 месяца, открыт 8 августа 2026,
 * проценты простые и выплачиваются 8 ноября.
 */
class InterestAtMaturityTest {

    private val deposits = Category(
        id = "deposits",
        section = CategorySection.ASSET,
        name = "Вклады",
        color = "#336699",
        balancePolicy = AssetBalancePolicy.FIXED_RATE,
        createdAtUtc = 0,
        updatedAtUtc = 0,
    )

    private fun account(interestDay: Int? = 8) = AssetAccount(
        id = "y",
        categoryId = deposits.id,
        name = "Сейв",
        openedPeriod = Period(2026, 8),
        annualRateBp = 1400,
        termMonths = 3,
        interestDay = interestDay,
        capitalization = DepositCapitalization.AT_MATURITY,
        createdAtUtc = 0,
        updatedAtUtc = 0,
    )

    private val opening = AssetSnapshot(
        id = "s",
        accountId = "y",
        period = Period(2026, 8),
        balance = Money(10_710_203, "RUB"),
        createdAtUtc = 0,
        updatedAtUtc = 0,
    )

    private fun valueAt(period: Period, interestDay: Int? = 8, vararg extra: AssetSnapshot): AccountBalance =
        AssetBalances.resolve(period, listOf(account(interestDay)), listOf(deposits), listOf(opening) + extra).single()

    @Test
    fun `до конца срока сумма не растёт, а проценты копятся отдельно`() {
        val sep = valueAt(Period(2026, 9))
        assertEquals(Money(10_710_203, "RUB"), sep.balance)
        assertEquals(Money(127_349, "RUB"), sep.pendingInterest) // 31 день
        assertEquals(null, sep.accruedInterest)
        assertEquals(BalanceSource.PROJECTED, sep.source)

        val oct = valueAt(Period(2026, 10))
        assertEquals(Money(10_710_203, "RUB"), oct.balance)
        assertEquals(Money(250_589, "RUB"), oct.pendingInterest) // 61 день
    }

    @Test
    fun `в месяце окончания проценты прибавляются один раз — без капитализации`() {
        val nov = valueAt(Period(2026, 11))
        // 92 дня простого процента; с ежемесячной капитализацией вышло бы 110 926 ₽.
        assertEquals(Money(377_938, "RUB"), nov.accruedInterest)
        assertEquals(Money(11_088_141, "RUB"), nov.balance)
        assertEquals(null, nov.pendingInterest)
        assertEquals(false, nov.isMatured)
    }

    @Test
    fun `после срока сумма переносится, вклад предлагается закрыть`() {
        val dec = valueAt(Period(2026, 12))
        assertEquals(Money(11_088_141, "RUB"), dec.balance)
        assertEquals(BalanceSource.CARRIED, dec.source)
        assertEquals(true, dec.isMatured)
    }

    @Test
    fun `введённая в месяце окончания сумма важнее расчёта`() {
        val bank = AssetSnapshot(
            id = "nov",
            accountId = "y",
            period = Period(2026, 11),
            balance = Money(11_084_036, "RUB"),
            createdAtUtc = 0,
            updatedAtUtc = 0,
        )
        assertEquals(bank.balance, valueAt(Period(2026, 11), 8, bank).balance)
        assertEquals(bank.balance, valueAt(Period(2027, 1), 8, bank).balance)
    }

    @Test
    fun `без дня начисления процент считается по месяцам — ставка на 12`() {
        assertEquals(Money(124_952, "RUB"), valueAt(Period(2026, 9), null).pendingInterest)
        assertEquals(Money(374_857, "RUB"), valueAt(Period(2026, 11), null).accruedInterest)
    }

    @Test
    fun `выплата в конце срока без срока недопустима`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { account().copy(termMonths = null) }
    }
}
