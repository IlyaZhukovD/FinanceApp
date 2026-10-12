package app.finance.feature.analytics

import app.finance.core.domain.DomainFixture
import app.finance.core.domain.LedgerRepository
import app.finance.core.model.AssetBalancePolicy
import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Тесты контроллера раздела «Аналитика» (SPEC.md F6).
 *
 * Расчёты здесь не проверяются — они покрыты golden-тестами `core:analytics`. Контроллер
 * отвечает за другое: выбрать правильный диапазон, разложить результат по строкам экрана
 * и не смешать потоки с остатками (F6.8).
 *
 * Как и в `AssetsControllerTest`, контроллер получает `TestScope` и
 * `StandardTestDispatcher(testScheduler)`: с `backgroundScope` виртуальное время не
 * продвигается и состояние остаётся незагруженным.
 */
class AnalyticsControllerTest {

    private val fx = DomainFixture()
    private val dec = Period(2025, 12)
    private val jan = Period(2026, 1)
    private val feb = Period(2026, 2)
    private val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
    private val zone: ZoneId = ZoneOffset.UTC

    private fun TestScope.controller(
        period: Period = feb,
        preset: RangePreset = RangePreset.LAST_3,
        ledger: LedgerRepository = fx.ledger,
    ): AnalyticsController = AnalyticsController(
        ledger = ledger,
        assets = fx.assets,
        categories = fx.categories,
        initialPeriod = period,
        clock = clock,
        zone = zone,
        scope = this,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    ).also { it.selectPreset(preset) }

    /** Прямая укладка ячеек: аналитика только читает, ревизии и outbox ей не нужны. */
    private fun putCell(
        categoryId: String,
        period: Period,
        minor: Long,
        section: LedgerSection = LedgerSection.EXPENSE,
        currency: String = Money.DEFAULT_CURRENCY,
    ) {
        val cell = CategoryMonthCell(
            id = "$categoryId-${period.key}",
            section = section,
            categoryId = categoryId,
            period = period,
            amount = Money(minor, currency),
            createdAtUtc = fx.time.now,
            updatedAtUtc = fx.time.now,
        )
        // В БД на пару (категория, месяц) разрешена одна живая ячейка независимо от валюты
        // (uq_category_month_active). Молчаливая перезапись в фейке скрыла бы эту ошибку
        // подготовки сценария, поэтому падение явное.
        check(fx.ledger.cells[cell.id] == null) {
            "Ячейка ${cell.id} уже есть: такая пара запрещена и в реальной базе"
        }
        fx.ledger.cells[cell.id] = cell
    }

    private fun seedFlows() {
        fx.category("food", "Еда", section = CategorySection.EXPENSE)
        fx.category("salary", "Зарплата", section = CategorySection.INCOME)
        putCell("food", dec, 10_000)
        putCell("food", jan, 20_000)
        putCell("food", feb, 30_000)
        putCell("salary", dec, 100_000, section = LedgerSection.INCOME)
        putCell("salary", jan, 100_000, section = LedgerSection.INCOME)
        putCell("salary", feb, 120_000, section = LedgerSection.INCOME)
    }

    @Test
    fun `загрузка считает итоги, cash flow и структуру расходов за диапазон`() = runTest {
        seedFlows()

        val controller = controller()
        controller.start()
        advanceUntilIdle()

        val state = controller.state.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(dec, state.range.from)
        assertEquals(feb, state.range.to)

        val summary = assertNotNull(state.summary)
        assertEquals(Money(320_000), summary.income)
        assertEquals(Money(60_000), summary.expense)
        assertEquals(Money(260_000), summary.delta)
        // 260 000 / 320 000 = 81,25% -> HALF_UP -> 81.
        assertEquals(81, summary.savingsRatePercent)
        assertEquals(3, summary.months)
        assertEquals(3, summary.surplusMonths)

        assertEquals(3, state.cashFlow.size)
        assertEquals(listOf(dec, jan, feb), state.cashFlow.map { it.period })

        val food = state.expenseRows.single()
        assertEquals("Еда", food.name)
        assertEquals(Money(60_000), food.amount)
        assertEquals(100, food.sharePercent)
        // Среднее по трём месяцам с данными: 60 000 / 3.
        assertEquals(Money(20_000), food.mean)

        val salary = state.incomeRows.single()
        assertEquals("Зарплата", salary.name)
        assertEquals(Money(320_000), salary.amount)
        assertNull(salary.mean, "Средний доход нерегулярен и намеренно не показывается")
    }

    @Test
    fun `MoM и YoY считаются по расходам на конец диапазона`() = runTest {
        seedFlows()

        val controller = controller()
        controller.start()
        advanceUntilIdle()

        val state = controller.state.value
        // Февраль 30 000 против января 20 000.
        assertEquals(Money(10_000), state.expenseMom?.absolute)
        assertEquals(50, state.expenseMom?.percent)
        // Февраля 2025 в данных нет: для потока это законный ноль, поэтому процент
        // не определён, а абсолютное изменение есть.
        assertEquals(Money(30_000), state.expenseYoy?.absolute)
        assertNull(state.expenseYoy?.percent)
    }

    @Test
    fun `смена пресета расширяет диапазон`() = runTest {
        seedFlows()

        val controller = controller()
        controller.start()
        advanceUntilIdle()
        assertEquals(3, controller.state.value.cashFlow.size)

        controller.selectPreset(RangePreset.YEAR_TO_DATE)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(RangePreset.YEAR_TO_DATE, state.preset)
        // YTD от февраля 2026 — это январь и февраль.
        assertEquals(listOf(jan, feb), state.cashFlow.map { it.period })
        assertEquals(Money(50_000), state.summary?.expense)
    }

    @Test
    fun `смена месяца сдвигает весь диапазон`() = runTest {
        seedFlows()

        val controller = controller()
        controller.start()
        advanceUntilIdle()

        controller.previousMonth()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(jan, state.anchor)
        assertEquals(listOf(Period(2025, 11), dec, jan), state.cashFlow.map { it.period })
        // Ноябрь пуст: для потока это ноль, а не отсутствие строки.
        assertEquals(Money(0), state.cashFlow.first().expense)
        // Декабрь 10 000 + январь 20 000 = 30 000.
        assertEquals(Money(30_000), state.summary?.expense)
    }

    @Test
    fun `месяц без остатков показан как gap, а не как нулевой капитал`() = runTest {
        // Категория с ручной оценкой (D-21): перенесённая стоимость остаётся черновиком и
        // в серию капитала не входит, поэтому месяц без ввода — это gap, а не ноль.
        fx.category(
            "banks",
            "Банки",
            section = CategorySection.ASSET,
            balancePolicy = AssetBalancePolicy.MANUAL_VALUATION,
        )
        fx.account("deposit", "Вклад", categoryId = "banks", openedPeriod = dec)
        fx.snapshot("deposit", jan, balanceMinor = 500_000)

        val controller = controller()
        controller.start()
        advanceUntilIdle()

        val rows = controller.state.value.netWorthRows
        assertEquals(listOf(dec, jan, feb), rows.map { it.period })
        assertTrue(rows[0].isGap)
        assertEquals(Money(500_000), rows[1].netWorth)
        assertTrue(rows[2].isGap)
        assertTrue(controller.state.value.hasNetWorthData)
    }

    @Test
    fun `остатки не смешиваются с потоками`() = runTest {
        // Доходы есть, счетов и остатков нет: Net Worth обязан остаться пустым (F6.8).
        seedFlows()

        val controller = controller()
        controller.start()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(Money(320_000), state.summary?.income)
        assertFalse(state.hasNetWorthData)
        assertTrue(state.netWorthRows.all { it.isGap })
    }

    @Test
    fun `ячейки другой валюты не попадают в срез`() = runTest {
        fx.category("food", "Еда", section = CategorySection.EXPENSE)
        putCell("food", feb, 30_000)
        // Чужая валюта у той же категории возможна только в другом месяце: пару
        // (категория, месяц) занимает одна живая ячейка (uq_category_month_active).
        putCell("food", jan, 99_999, currency = "USD")

        val controller = controller()
        controller.start()
        advanceUntilIdle()

        assertEquals(Money(30_000), controller.state.value.summary?.expense)
    }

    @Test
    fun `пустой период показывает нули, а не ошибку`() = runTest {
        val controller = controller()
        controller.start()
        advanceUntilIdle()

        val state = controller.state.value
        assertNull(state.error)
        assertFalse(state.isLoading)
        assertFalse(state.hasFlowData)
        assertEquals(Money(0), state.summary?.expense)
        assertTrue(state.expenseRows.isEmpty())
    }

    @Test
    fun `ошибка чтения попадает в состояние и снимается`() = runTest {
        val controller = controller(ledger = FailingLedger(fx.ledger))
        controller.start()
        advanceUntilIdle()

        val state = controller.state.value
        assertFalse(state.isLoading)
        assertEquals("База недоступна", state.error)

        controller.dismissError()
        assertNull(controller.state.value.error)
    }

    /** Обёртка над фейком: роняет только выборку диапазона, которой пользуется аналитика. */
    private class FailingLedger(delegate: LedgerRepository) : LedgerRepository by delegate {
        override suspend fun cellsInRange(
            section: LedgerSection,
            from: Period,
            to: Period,
        ): List<CategoryMonthCell> = throw IllegalStateException("База недоступна")
    }
}
