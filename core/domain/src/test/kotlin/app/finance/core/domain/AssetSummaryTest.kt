package app.finance.core.domain

import app.finance.core.model.AssetKind
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
        fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT)
        fx.account("credit", "Кредитка", kind = AssetKind.CREDIT)
        fx.snapshot("deposit", feb, balanceMinor = 500_000)
        fx.snapshot("credit", feb, balanceMinor = -75_000)

        val rub = requireNotNull(fx.assetSummary(feb).of())

        // 5 000.00 − 750.00 = 4 250.00 ₽
        assertEquals(Money(425_000), rub.netWorth)
        assertEquals(2, rub.accountCount)
    }

    @Test
    fun `дельта и прирост считаются от прошлого месяца`() = runTest {
        fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT)
        fx.account("credit", "Кредитка", kind = AssetKind.CREDIT)
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
        fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT)
        fx.snapshot("deposit", jan, balanceMinor = 130_000)
        fx.snapshot("deposit", feb, balanceMinor = 100_000, contributionMinor = -30_000)

        val rub = requireNotNull(fx.assetSummary(feb).of())

        assertEquals(Money(-30_000), rub.delta)
        assertEquals(Money(-30_000), rub.contributions)
        assertEquals(Money(0L), rub.gain)
    }

    @Test
    fun `новый счёт без снапшота в прошлом месяце не ломает расчёт`() = runTest {
        fx.account("old", "Старый вклад", kind = AssetKind.DEPOSIT)
        fx.account("new", "Новый счёт", kind = AssetKind.BROKERAGE)
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
        fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT)
        fx.snapshot("deposit", feb, balanceMinor = 100_000)

        val rub = requireNotNull(fx.assetSummary(feb).of())

        assertEquals(Money(100_000), rub.netWorth)
        assertNull(rub.previousNetWorth, "Прошлый месяц пуст — сравнивать не с чем")
        assertNull(rub.delta)
        assertNull(rub.gain)
    }

    @Test
    fun `валюты не смешиваются, основная идёт первой`() = runTest {
        fx.account("rub", "Вклад", kind = AssetKind.DEPOSIT)
        fx.account("usd", "Брокер USD", kind = AssetKind.BROKERAGE, currency = "USD")
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
        fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT)

        val totals = fx.assetSummary(feb)

        assertTrue(totals.isEmpty)
        assertTrue(totals.totals.isEmpty())
        assertNull(totals.of())
        assertEquals(feb, totals.period)
    }

    @Test
    fun `в месяце без снапшотов итогов нет - балансы не переносятся`() = runTest {
        // Переноса остатков нет: февраль не введён — значит итога за февраль тоже нет.
        // Для переноса существует «Дублировать прошлый месяц» (F5.3).
        fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT)
        fx.snapshot("deposit", jan, balanceMinor = 100_000)

        assertTrue(fx.assetSummary(feb).isEmpty)
        assertTrue(fx.assetSummary(jan).totals.isNotEmpty())
    }

    @Test
    fun `взносы без явного значения не меняют сумму`() = runTest {
        fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT)
        fx.snapshot("deposit", jan, balanceMinor = 100_000)
        fx.snapshot("deposit", feb, balanceMinor = 120_000)

        val rub = requireNotNull(fx.assetSummary(feb).of())

        assertEquals(Money(0L), rub.contributions)
        // Весь рост признан приростом, потому что взносов не заявлено.
        assertEquals(Money(20_000), rub.gain)
    }
}
