package app.finance.core.domain

import app.finance.core.model.AssetBalancePolicy
import app.finance.core.model.CategorySection
import app.finance.core.model.Money
import app.finance.core.model.Period
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Net Worth и разложение изменения на взносы и прирост (SPEC.md F4.1).
 *
 * Критерий приёмки M4: итог корректен, включая отрицательные балансы кредитов.
 */
class AssetSummaryTest {

    private val fx = DomainFixture()
    private val jan = Period(2026, 1)
    private val feb = Period(2026, 2)

    @Test
    fun `суммирует балансы месяца, включая отрицательный баланс кредита`() = runTest {
        fx.account("deposit", "Вклад")
        fx.account("credit", "Кредитка")
        fx.snapshot("deposit", feb, balanceMinor = 500_000)
        fx.snapshot("credit", feb, balanceMinor = -75_000)

        val rub = requireNotNull(fx.assetSummary(feb).of())

        // 5 000.00 − 750.00 = 4 250.00 ₽
        assertEquals(Money(425_000), rub.netWorth)
        assertEquals(2, rub.accountCount)
    }

    @Test
    fun `дельта и прирост считаются от прошлого месяца`() = runTest {
        fx.account("deposit", "Вклад")
        fx.account("credit", "Кредитка")
        fx.snapshot("deposit", jan, balanceMinor = 400_000)
        fx.snapshot("credit", jan, balanceMinor = -50_000)
        fx.snapshot("deposit", feb, balanceMinor = 500_000, contributionMinor = 50_000)
        fx.snapshot("credit", feb, balanceMinor = -75_000)

        val rub = requireNotNull(fx.assetSummary(feb).of())

        assertEquals(Money(350_000), rub.previousNetWorth)
        assertEquals(Money(425_000), rub.netWorth)
        assertEquals(Money(75_000), rub.delta)
        assertEquals(Money(50_000), rub.contributions)
        // прирост = Δ − взносы = 75 000 − 50 000 (F4.1)
        assertEquals(Money(25_000), rub.gain)
    }

    @Test
    fun `снятия уменьшают сумму взносов и оставляют нулевой прирост`() = runTest {
        fx.account("deposit", "Вклад")
        fx.snapshot("deposit", jan, balanceMinor = 130_000)
        fx.snapshot("deposit", feb, balanceMinor = 100_000, contributionMinor = -30_000)

        val rub = requireNotNull(fx.assetSummary(feb).of())

        assertEquals(Money(-30_000), rub.delta)
        assertEquals(Money(-30_000), rub.contributions)
        assertEquals(Money(0L), rub.gain)
    }

    @Test
    fun `новый счёт без снапшота в прошлом месяце не ломает расчёт`() = runTest {
        fx.account("old", "Старый вклад")
        fx.account("new", "Новый счёт")
        fx.snapshot("old", jan, balanceMinor = 100_000)
        fx.snapshot("old", feb, balanceMinor = 110_000)
        fx.snapshot("new", feb, balanceMinor = 50_000, contributionMinor = 50_000)

        val rub = requireNotNull(fx.assetSummary(feb).of())

        assertEquals(Money(100_000), rub.previousNetWorth)
        assertEquals(Money(160_000), rub.netWorth)
        assertEquals(Money(60_000), rub.delta)
        assertEquals(Money(50_000), rub.contributions)
        assertEquals(Money(10_000), rub.gain)
        assertEquals(2, rub.accountCount)
    }

    @Test
    fun `без прошлого месяца дельта и прирост отсутствуют, а не равны нулю`() = runTest {
        fx.account("deposit", "Вклад")
        fx.snapshot("deposit", feb, balanceMinor = 100_000)

        val rub = requireNotNull(fx.assetSummary(feb).of())

        assertEquals(Money(100_000), rub.netWorth)
        assertNull(rub.previousNetWorth, "Прошлый месяц пуст — сравнивать не с чем")
        assertNull(rub.delta)
        assertNull(rub.gain)
    }

    @Test
    fun `валюты не смешиваются, основная идёт первой`() = runTest {
        fx.account("rub", "Вклад")
        fx.account("usd", "Брокер USD", currency = "USD")
        fx.snapshot("usd", feb, balanceMinor = 2_000, currency = "USD")
        fx.snapshot("rub", feb, balanceMinor = 100_000)

        val totals = fx.assetSummary(feb)

        assertTrue(totals.hasMultipleCurrencies)
        assertEquals(2, totals.totals.size)
        assertEquals(Money.DEFAULT_CURRENCY, totals.totals.first().currency)
        assertEquals(Money(100_000), requireNotNull(totals.of()).netWorth)
        assertEquals(Money(2_000, "USD"), requireNotNull(totals.of("USD")).netWorth)
        assertNull(totals.of("EUR"), "Валют без снапшотов в итогах нет")
    }

    @Test
    fun `пустой месяц не даёт итогов`() = runTest {
        fx.account("deposit", "Вклад")

        val totals = fx.assetSummary(feb)

        assertTrue(totals.isEmpty)
        assertTrue(totals.totals.isEmpty())
        assertNull(totals.of())
        assertEquals(feb, totals.period)
    }

    @Test
    fun `значение месяца переносится по правилу категории`() = runTest {
        // D-21: актив без роста переносится и учитывается в итогах, а стоимость актива
        // с ручной оценкой переносится черновиком и не считается до подтверждения.
        fx.category(
            "cash",
            "Наличные",
            section = CategorySection.ASSET,
            balancePolicy = AssetBalancePolicy.FLAT,
        )
        fx.category(
            "shares",
            "Паи",
            section = CategorySection.ASSET,
            balancePolicy = AssetBalancePolicy.MANUAL_VALUATION,
        )
        fx.account("cash", "Наличные", categoryId = "cash")
        fx.account("shares", "Паи", categoryId = "shares")
        fx.snapshot("cash", jan, balanceMinor = 100_000)
        fx.snapshot("shares", jan, balanceMinor = 250_000)

        val carried = requireNotNull(fx.assetSummary(feb).of())

        assertEquals(Money(100_000), carried.netWorth, "Перенос обычного актива учитывается")
        assertEquals(1, carried.accountCount, "Черновик ручной оценки в итоги не входит")
        assertEquals(2, requireNotNull(fx.assetSummary(jan).of()).accountCount, "В январе оба значения введены")
    }

    @Test
    fun `взносы без явного значения не меняют сумму`() = runTest {
        fx.account("deposit", "Вклад")
        fx.snapshot("deposit", jan, balanceMinor = 100_000)
        fx.snapshot("deposit", feb, balanceMinor = 120_000)

        val rub = requireNotNull(fx.assetSummary(feb).of())

        assertEquals(Money(0L), rub.contributions)
        // Весь рост признан приростом, потому что взносов не заявлено.
        assertEquals(Money(20_000), rub.gain)
    }
}
