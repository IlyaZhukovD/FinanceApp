package app.finance.feature.assets

import app.finance.core.domain.DomainFixture
import app.finance.core.model.CategorySection
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
 * Тесты контроллера раздела «Активы» (SPEC.md F4).
 *
 * Как и в `LedgerControllerTest`, контроллер получает `TestScope` и
 * `StandardTestDispatcher(testScheduler)`: контроллер принимает scope извне, поэтому
 * `runTest` управляет виртуальным временем и `advanceUntilIdle()` продвигает перезагрузку
 * месяца. С `backgroundScope` тот же код не продвигается — состояние остаётся незагруженным.
 */
class AssetsControllerTest {

    private val fx = DomainFixture()
    private val jan = Period(2026, 1)
    private val feb = Period(2026, 2)
    private val mar = Period(2026, 3)
    private val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
    private val zone: ZoneId = ZoneOffset.UTC

    private fun TestScope.controller(period: Period = feb): AssetsController = AssetsController(
        assets = fx.assets,
        categories = fx.categories,
        recordSnapshot = fx.recordSnapshot,
        createAccount = fx.createAccount,
        closeAccount = fx.closeAccount,
        assetSummary = fx.assetSummary,
        initialPeriod = period,
        clock = clock,
        zone = zone,
        scope = this,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    /** Категория активов и два счёта в ней: вклад и кредитка. */
    private fun seedAccounts() {
        fx.category("banks", "Банки", section = CategorySection.ASSET)
        fx.account("deposit", "Вклад", categoryId = "banks", openedPeriod = jan)
        fx.account("credit", "Кредитка", categoryId = "banks", openedPeriod = jan)
    }

    private fun AssetsController.rows(): List<AccountRow> = state.value.groups.flatMap { it.accounts }

    @Test
    fun `загрузка показывает счета по категориям и Net Worth в шапке`() = runTest {
        seedAccounts()
        fx.snapshot("deposit", feb, balanceMinor = 500_000)
        fx.snapshot("credit", feb, balanceMinor = -75_000)

        val controller = controller(feb)
        controller.start()
        advanceUntilIdle()

        val state = controller.state.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        // Критерий приёмки M4: отрицательный баланс кредита уменьшает Net Worth.
        assertEquals(Money(425_000), state.header.netWorth)
        assertEquals(2, state.header.snapshotCount)
        assertEquals(2, state.header.accountCount)

        val group = state.groups.single()
        assertEquals("Банки", group.categoryName)
        assertEquals(Money(425_000), group.total)
        assertEquals(listOf("deposit", "credit"), group.accounts.map { it.accountId }, "Сначала больший баланс")
        assertEquals(listOf("Вклад", "Кредитка"), group.accounts.map { it.name })
        assertTrue(group.accounts.last().balance!!.isNegative())
    }

    @Test
    fun `шапка показывает дельту, взносы и прирост`() = runTest {
        seedAccounts()
        fx.snapshot("deposit", jan, balanceMinor = 400_000)
        fx.snapshot("deposit", feb, balanceMinor = 500_000, contributionMinor = 50_000)

        val controller = controller(feb)
        controller.start()
        advanceUntilIdle()

        val header = controller.state.value.header
        assertEquals(Money(400_000), header.previousNetWorth)
        assertEquals(Money(100_000), header.delta)
        assertEquals(Money(50_000), header.contributions)
        assertEquals(Money(50_000), header.gain)
        assertEquals(25, header.deltaPercent)
    }

    @Test
    fun `ввод остатка создаёт снапшот и закрывает форму`() = runTest {
        fx.category("banks", "Банки", section = CategorySection.ASSET)
        fx.account("deposit", "Вклад", categoryId = "banks", openedPeriod = jan)

        val controller = controller(feb)
        controller.start()
        advanceUntilIdle()

        controller.openSnapshotForm("deposit")
        advanceUntilIdle()
        assertNotNull(controller.state.value.snapshotForm)
        assertFalse(requireNotNull(controller.state.value.snapshotForm).isEdit)

        controller.setBalanceText("15000,50")
        controller.submitSnapshot()
        advanceUntilIdle()

        assertEquals(Money(1_500_050), fx.assets.findSnapshot("deposit", feb)?.balance)
        assertNull(controller.state.value.snapshotForm, "После сохранения форма закрывается")
        assertNotNull(controller.state.value.notice)
        assertEquals(Money(1_500_050), controller.state.value.header.netWorth)
    }

    @Test
    fun `повторный ввод за месяц заменяет остаток, а не добавляет второй`() = runTest {
        fx.category("banks", "Банки", section = CategorySection.ASSET)
        fx.account("deposit", "Вклад", categoryId = "banks", openedPeriod = jan)
        fx.snapshot("deposit", feb, balanceMinor = 500_000)

        val controller = controller(feb)
        controller.start()
        advanceUntilIdle()

        controller.openSnapshotForm("deposit")
        advanceUntilIdle()
        assertTrue(requireNotNull(controller.state.value.snapshotForm).isEdit)
        assertEquals("5000.00", controller.state.value.snapshotForm?.balanceText, "Прежнее значение подставляется")

        controller.setBalanceText("5500")
        controller.submitSnapshot()
        advanceUntilIdle()

        val snapshots = fx.assets.snapshotsByAccount("deposit")
        assertEquals(1, snapshots.size, "Второй строки за тот же месяц не появилось")
        assertEquals(Money(550_000), snapshots.single().balance)
        assertEquals(2, snapshots.single().rowVersion)
        assertEquals(Money(550_000), controller.state.value.header.netWorth)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `некорректный остаток показывает подсказку и не пишет данные`() = runTest {
        fx.category("banks", "Банки", section = CategorySection.ASSET)
        fx.account("deposit", "Вклад", categoryId = "banks", openedPeriod = jan)

        val controller = controller(feb)
        controller.start()
        advanceUntilIdle()
        controller.openSnapshotForm("deposit")
        advanceUntilIdle()

        controller.setBalanceText("не число")
        controller.submitSnapshot()
        advanceUntilIdle()

        assertNotNull(controller.state.value.snapshotForm?.error)
        assertNull(fx.assets.findSnapshot("deposit", feb), "Ничего не сохранено")
        assertEquals(0, controller.state.value.header.snapshotCount)
    }

    @Test
    fun `отрицательный остаток для вклада отклоняется сообщением домена`() = runTest {
        fx.category("banks", "Банки", section = CategorySection.ASSET)
        fx.account("deposit", "Вклад", categoryId = "banks", openedPeriod = jan)

        val controller = controller(feb)
        controller.start()
        advanceUntilIdle()
        controller.openSnapshotForm("deposit")
        advanceUntilIdle()

        controller.setBalanceText("-100")
        controller.submitSnapshot()
        advanceUntilIdle()

        val error = requireNotNull(controller.state.value.error)
        assertTrue(error.contains("Отрицательный баланс"), "Сообщение: $error")
        assertNull(fx.assets.findSnapshot("deposit", feb))
        // Право на минус задаёт категория (D-20, D-22): кредит — это счёт в категории
        // с разрешённым отрицательным остатком, у самого счёта признака нет.
        fx.category("credits", "Кредиты", section = CategorySection.ASSET, allowsNegativeBalance = true)
        fx.account("credit", "Кредитка", categoryId = "credits", openedPeriod = jan)
        controller.start()
        advanceUntilIdle()
        controller.openSnapshotForm("credit")
        advanceUntilIdle()
        controller.setBalanceText("-750")
        controller.submitSnapshot()
        advanceUntilIdle()
        assertEquals(Money(-75_000), fx.assets.findSnapshot("credit", feb)?.balance)
    }

    @Test
    fun `создание счёта добавляет его в список месяца`() = runTest {
        fx.category("banks", "Банки", section = CategorySection.ASSET)

        val controller = controller(feb)
        controller.start()
        advanceUntilIdle()
        assertFalse(controller.state.value.hasAccounts)

        controller.openAccountDialog()
        assertEquals("banks", controller.state.value.accountDialog?.categoryId, "Категория подставляется")
        controller.setAccountName("  ИИС  ")
        controller.submitAccount()
        advanceUntilIdle()

        val accounts = fx.assets.accounts(includeClosed = true)
        assertEquals(1, accounts.size)
        assertEquals("ИИС", accounts.single().name)
        assertEquals(feb, accounts.single().openedPeriod, "Счёт открывается текущим месяцем")
        assertEquals(listOf("ИИС"), controller.rows().map { it.name })
        assertNull(controller.state.value.accountDialog)
    }

    @Test
    fun `закрытие счёта требует подтверждения и убирает счёт из следующих месяцев`() = runTest {
        seedAccounts()
        fx.snapshot("deposit", feb, balanceMinor = 500_000)

        val controller = controller(feb)
        controller.start()
        advanceUntilIdle()

        controller.requestCloseAccount("deposit")
        assertNotNull(controller.state.value.confirm, "Закрытие — необратимое действие, нужно подтверждение")

        controller.confirmAction()
        advanceUntilIdle()

        assertEquals(feb, fx.assets.accountById("deposit")?.closedPeriod)
        assertNull(controller.state.value.confirm)
        assertTrue(controller.rows().any { it.accountId == "deposit" && it.isClosed }, "В месяце закрытия счёт виден")

        controller.nextMonth()
        advanceUntilIdle()
        assertEquals(mar, controller.state.value.period)
        assertFalse(controller.rows().any { it.accountId == "deposit" }, "После закрытия счёт не показывается")
        assertTrue(controller.rows().any { it.accountId == "credit" })
    }

    @Test
    fun `переключение периода перечитывает месяц без переноса остатков`() = runTest {
        seedAccounts()
        fx.snapshot("deposit", feb, balanceMinor = 500_000)

        val controller = controller(feb)
        controller.start()
        advanceUntilIdle()
        assertEquals(Money(500_000), controller.state.value.header.netWorth)

        controller.previousMonth()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(jan, state.period)
        assertEquals(0, state.header.snapshotCount)
        assertNull(state.header.delta)
        // Счёт активен в январе, но остаток не введён: переноса балансов нет (F5.3).
        val row = state.groups.single().accounts.first { it.accountId == "deposit" }
        assertNull(row.balance)
        assertFalse(row.hasSnapshot)

        controller.today()
        advanceUntilIdle()
        assertEquals(feb, controller.state.value.period, "«Сегодня» возвращает в текущий месяц")
        assertEquals(Money(500_000), controller.state.value.header.netWorth)
    }

    @Test
    fun `остатки в другой валюте не смешиваются с Net Worth`() = runTest {
        fx.category("banks", "Банки", section = CategorySection.ASSET)
        fx.account("deposit", "Вклад", categoryId = "banks", openedPeriod = jan)
        fx.account("usd", "Брокер USD", categoryId = "banks", currency = "USD", openedPeriod = jan)
        fx.snapshot("deposit", feb, balanceMinor = 500_000)
        fx.snapshot("usd", feb, balanceMinor = 2_000, currency = "USD")

        val controller = controller(feb)
        controller.start()
        advanceUntilIdle()

        val header = controller.state.value.header
        assertEquals(Money(500_000), header.netWorth, "В Net Worth только основная валюта")
        assertEquals(listOf(Money(2_000, "USD")), header.otherCurrencies)
        assertTrue(header.hasMultipleCurrencies)
        assertEquals(Money(2_000, "USD"), controller.rows().first { it.accountId == "usd" }.balance)
    }

    @Test
    fun `без счетов показывается пустое состояние`() = runTest {
        fx.category("banks", "Банки", section = CategorySection.ASSET)

        val controller = controller(feb)
        controller.start()
        advanceUntilIdle()

        val state = controller.state.value
        assertFalse(state.isLoading)
        assertFalse(state.hasAccounts)
        assertTrue(state.groups.isEmpty())
        assertEquals(0, state.header.accountCount)
        assertFalse(state.header.hasData)
    }

    // ── Напоминание обновить остаток (D-34) ─────────────────────────────────

    private fun TestScope.controllerAt(now: String, period: Period): AssetsController = AssetsController(
        assets = fx.assets,
        categories = fx.categories,
        recordSnapshot = fx.recordSnapshot,
        createAccount = fx.createAccount,
        closeAccount = fx.closeAccount,
        assetSummary = fx.assetSummary,
        initialPeriod = period,
        clock = Clock.fixed(Instant.parse(now), ZoneOffset.UTC),
        zone = zone,
        scope = this,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun seedReminderAccounts() {
        fx.category("cards", "Счёт под ключ", section = CategorySection.ASSET)
        fx.account("key", "Счёт в Т", categoryId = "cards", openedPeriod = jan, monthlyReminder = true)
        fx.account("cash", "Наличные", categoryId = "cards", openedPeriod = jan)
        fx.snapshot("key", jan, balanceMinor = 30_000_000)
        fx.snapshot("cash", jan, balanceMinor = 500_000)
    }

    @Test
    fun `счёт с напоминанием попадает в плашку в конце месяца, а сумма учитывается`() = runTest {
        seedReminderAccounts()
        val controller = controllerAt("2026-02-25T12:00:00Z", feb)
        controller.start()
        advanceUntilIdle()

        val reminder = assertNotNull(controller.state.value.reminder)
        assertEquals(listOf("key"), reminder.accounts.map { it.accountId }, "Только счёт с флагом")
        assertTrue(reminder.title.startsWith("Обновите остаток"))
        assertEquals(Money(30_000_000), reminder.accounts.single().carriedBalance)
        // Плашка лишь напоминает: перенесённая сумма по-прежнему входит в Net Worth.
        assertEquals(Money(30_500_000), controller.state.value.header.netWorth)
    }

    @Test
    fun `в начале текущего месяца и в будущих месяцах напоминания нет`() = runTest {
        seedReminderAccounts()
        val early = controllerAt("2026-02-10T12:00:00Z", feb)
        early.start()
        advanceUntilIdle()
        assertNull(early.state.value.reminder)

        val future = controllerAt("2026-02-25T12:00:00Z", mar)
        future.start()
        advanceUntilIdle()
        assertNull(future.state.value.reminder)
    }

    @Test
    fun `за прошедший месяц напоминание есть, пока остаток не введён`() = runTest {
        seedReminderAccounts()
        val controller = controllerAt("2026-03-03T12:00:00Z", feb)
        controller.start()
        advanceUntilIdle()
        assertNotNull(controller.state.value.reminder)

        controller.openConfirmBalances()
        controller.setConfirmAmount("key", "1500")
        controller.submitConfirmBalances()
        advanceUntilIdle()

        assertNull(controller.state.value.reminder)
        assertEquals(Money(30_150_000), fx.assets.findSnapshot("key", feb)?.balance)
    }
}
