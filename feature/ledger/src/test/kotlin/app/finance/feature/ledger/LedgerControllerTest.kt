package app.finance.feature.ledger

import app.finance.core.domain.ConflictPolicy
import app.finance.core.domain.DomainFixture
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Поведение экрана расходов/доходов проверяется без Compose: контроллер отдаёт
 * состояние как StateFlow, поэтому тесты работают с фейками домена (F2.2 — логика
 * общая для обеих секций).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedgerControllerTest {

    private val zone = ZoneOffset.UTC

    /** «Сегодня» зафиксировано: 9 февраля 2026, иначе тесты зависят от даты запуска. */
    private val clock = Clock.fixed(Instant.parse("2026-02-09T12:00:00Z"), zone)

    private val fixture = DomainFixture()

    private val february = Period(2026, 2)
    private val january = Period(2026, 1)

    private fun TestScope.controller(
        section: LedgerSection = LedgerSection.EXPENSE,
        period: Period = february,
    ): LedgerController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return LedgerController(
            section = section,
            categories = fixture.categories,
            ledger = fixture.ledger,
            recordAmount = fixture.recordAmount,
            editCell = fixture.editCell,
            deleteCell = fixture.deleteCell,
            restoreCell = fixture.restoreCell,
            duplicatePeriod = fixture.duplicatePeriod,
            initialPeriod = period,
            clock = clock,
            zone = zone,
            scope = this,
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `при загрузке видны категории и пустой месяц`() = runTest {
        val food = fixture.category("food", "Продукты")
        fixture.category("transport", "Транспорт")
        val controller = controller()

        controller.start()
        advanceUntilIdle()

        val state = controller.state.value
        assertFalse(state.isLoading, "После загрузки флаг загрузки должен быть снят")
        assertEquals(listOf("Продукты", "Транспорт"), state.categories.map { it.name })
        assertTrue(state.rows.isEmpty())
        assertEquals(Money.ZERO, state.total)
        assertNull(state.totalDelta, "Прошлый месяц пуст, поэтому дельта не определена")
        // Первая категория подставляется в форму, чтобы ввод начинался сразу (F1.4).
        assertEquals(food.id, state.form.categoryId)
    }

    @Test
    fun `ввод суммы создаёт строку месяца с долей и комментарием`() = runTest {
        val food = fixture.category("food", "Продукты")
        fixture.category("transport", "Транспорт")
        val controller = controller()
        controller.start()
        advanceUntilIdle()

        controller.selectCategory(food.id)
        controller.setAmountInput("1500,50")
        controller.setCommentInput("Пятёрочка")
        controller.submitAmount()
        advanceUntilIdle()

        val state = controller.state.value
        assertNull(state.error)
        assertEquals(1, state.rows.size)
        val row = state.rows.single()
        assertEquals("Продукты", row.categoryName)
        assertEquals(Money(150_050), row.amount)
        assertEquals(100, row.sharePercent)
        assertTrue(row.hasComment)
        assertEquals("#4E79A7", row.categoryColor, "Цвет категории берётся из её данных")
        assertEquals(Money(150_050), state.total)
        assertTrue(state.notice.orEmpty().contains("Добавлено"), "Сообщение: ${state.notice}")
    }

    @Test
    fun `форма не закрывается после сохранения - batch-ввод`() = runTest {
        val food = fixture.category("food", "Продукты")
        val controller = controller()
        controller.start()
        advanceUntilIdle()

        controller.selectCategory(food.id)
        controller.setAmountInput("1000")
        controller.setCommentInput("Обед")
        controller.submitAmount()
        advanceUntilIdle()

        val form = controller.state.value.form
        assertEquals(food.id, form.categoryId, "Категория сохраняется для следующего ввода (F1.6)")
        assertEquals("", form.amountText, "Сумма очищается")
        assertEquals("", form.commentText, "Комментарий очищается")
        assertEquals(february, controller.state.value.period, "Период сохраняется")
    }

    @Test
    fun `повторный ввод в ту же категорию аккумулируется, а не создаёт вторую строку`() = runTest {
        val food = fixture.category("food", "Продукты")
        val controller = controller()
        controller.start()
        advanceUntilIdle()

        controller.selectCategory(food.id)
        controller.setAmountInput("1000")
        controller.submitAmount()
        advanceUntilIdle()
        controller.setAmountInput("500")
        controller.submitAmount()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(1, state.rows.size, "F1.5: одна строка на категорию и месяц")
        assertEquals(Money(150_000), state.rows.single().amount)
        assertEquals(Money(150_000), state.total)
        assertTrue(state.notice.orEmpty().contains("Прибавлено"), "Сообщение: ${state.notice}")

        val revisions = fixture.ledger.revisionsOf(state.rows.single().cellId)
        assertEquals(2, revisions.size, "Каждое изменение пишется в журнал")
    }

    @Test
    fun `некорректная сумма не меняет данные и показывает подсказку`() = runTest {
        val food = fixture.category("food", "Продукты")
        val controller = controller()
        controller.start()
        advanceUntilIdle()
        controller.selectCategory(food.id)

        controller.setAmountInput("не сумма")
        controller.submitAmount()
        advanceUntilIdle()
        assertNotNull(controller.state.value.form.error)
        assertTrue(controller.state.value.rows.isEmpty())

        controller.setAmountInput("0")
        controller.submitAmount()
        advanceUntilIdle()
        assertEquals("Сумма не может быть нулевой", controller.state.value.form.error)
        assertTrue(controller.state.value.rows.isEmpty())

        controller.setAmountInput("")
        controller.submitAmount()
        advanceUntilIdle()
        assertNotNull(controller.state.value.form.error, "Пустой ввод не должен превращаться в трату")
        assertTrue(fixture.ledger.cells.isEmpty(), "В хранилище не появилось ни одной ячейки")
    }

    @Test
    fun `ввод без доступных категорий отклоняется`() = runTest {
        val controller = controller()
        controller.start()
        advanceUntilIdle()

        controller.setAmountInput("500")
        controller.submitAmount()
        advanceUntilIdle()

        assertEquals("Выберите категорию", controller.state.value.form.error)
        assertTrue(controller.state.value.rows.isEmpty())
    }

    @Test
    fun `архивная категория отклоняется с понятным сообщением`() = runTest {
        val food = fixture.category("food", "Продукты")
        val controller = controller()
        controller.start()
        advanceUntilIdle()
        controller.selectCategory(food.id)
        controller.setAmountInput("500")

        // Категория ушла в архив уже после открытия экрана (F3.2).
        fixture.categories.setArchived(food.id, archived = true, updatedAtUtc = fixture.time.now)
        controller.submitAmount()
        advanceUntilIdle()

        val error = controller.state.value.error
        assertNotNull(error)
        assertTrue(error.contains("в архиве"), "Сообщение: $error")
        assertTrue(controller.state.value.rows.isEmpty())
    }

    @Test
    fun `переключение периода перечитывает месяц`() = runTest {
        val food = fixture.category("food", "Продукты")
        val controller = controller()
        controller.start()
        advanceUntilIdle()
        controller.selectCategory(food.id)
        controller.setAmountInput("1000")
        controller.submitAmount()
        advanceUntilIdle()

        controller.previousMonth()
        advanceUntilIdle()
        assertEquals(january, controller.state.value.period)
        assertTrue(controller.state.value.rows.isEmpty(), "В январе записей нет")
        assertNull(controller.state.value.panel, "Панель ячейки закрыта вместе со сменой месяца")

        controller.nextMonth()
        advanceUntilIdle()
        assertEquals(1, controller.state.value.rows.size)

        controller.moveTo(Period(2026, 3))
        controller.today()
        advanceUntilIdle()
        assertEquals(february, controller.state.value.period, "«Сегодня» возвращает в текущий месяц")
    }

    @Test
    fun `панель ячейки показывает историю и правит сумму`() = runTest {
        val food = fixture.category("food", "Продукты")
        val controller = controller()
        controller.start()
        advanceUntilIdle()
        controller.selectCategory(food.id)
        controller.setAmountInput("1500,50")
        controller.setCommentInput("Пятёрочка")
        controller.submitAmount()
        advanceUntilIdle()

        val cellId = controller.state.value.rows.single().cellId
        controller.selectCell(cellId)
        advanceUntilIdle()

        val panel = assertNotNull(controller.state.value.panel)
        assertEquals("Продукты", panel.categoryName)
        assertEquals("1500,50", panel.amountText, "Сумма в поле — в том же формате, что вводит пользователь")
        assertEquals("Пятёрочка", panel.commentText)
        assertEquals(1, panel.revisions.size)
        assertEquals("Создано", panel.revisions.single().operationLabel)

        controller.setPanelAmount("2000")
        controller.setPanelComment("")
        controller.savePanel()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(Money(200_000), state.rows.single().amount)
        assertFalse(state.rows.single().hasComment, "Пустой комментарий очищает значение")
        val history = fixture.ledger.revisionsOf(cellId)
        assertEquals(3, history.size, "Правка суммы и комментария — две отдельные записи (F1.5)")
        assertEquals(
            setOf("Создано", "Изменена сумма", "Изменён комментарий"),
            state.panel?.revisions?.map { it.operationLabel }?.toSet(),
        )
    }

    @Test
    fun `правка без изменений не создаёт ревизий`() = runTest {
        val food = fixture.category("food", "Продукты")
        val controller = controller()
        controller.start()
        advanceUntilIdle()
        controller.selectCategory(food.id)
        controller.setAmountInput("1000")
        controller.submitAmount()
        advanceUntilIdle()

        val cellId = controller.state.value.rows.single().cellId
        controller.selectCell(cellId)
        advanceUntilIdle()
        controller.savePanel()
        advanceUntilIdle()

        assertEquals("Изменений нет", controller.state.value.notice)
        assertEquals(1, fixture.ledger.revisionsOf(cellId).size)
    }

    @Test
    fun `удаление скрывает строку, восстановление возвращает её`() = runTest {
        val food = fixture.category("food", "Продукты")
        val controller = controller()
        controller.start()
        advanceUntilIdle()
        controller.selectCategory(food.id)
        controller.setAmountInput("1000")
        controller.submitAmount()
        advanceUntilIdle()

        val cellId = controller.state.value.rows.single().cellId
        controller.selectCell(cellId)
        advanceUntilIdle()

        controller.requestDelete()
        val confirm = assertNotNull(controller.state.value.confirm)
        assertTrue(confirm.destructive)
        assertEquals(1, controller.state.value.rows.size, "До подтверждения данные не меняются")

        controller.confirm()
        advanceUntilIdle()

        assertTrue(controller.state.value.rows.isEmpty(), "Удалённая ячейка не входит в итог месяца")
        assertEquals(Money.ZERO, controller.state.value.total)
        assertEquals("Ячейка удалена", controller.state.value.notice)
        val deletedPanel = assertNotNull(controller.state.value.panel)
        assertTrue(deletedPanel.isDeleted, "Панель остаётся открытой, чтобы ячейку можно было вернуть")

        controller.restoreSelected()
        advanceUntilIdle()

        assertEquals(1, controller.state.value.rows.size)
        assertEquals(Money(100_000), controller.state.value.rows.single().amount)
        assertFalse(controller.state.value.panel!!.isDeleted)
    }

    @Test
    fun `сторно обнуляет сумму и сохраняет строку с историей`() = runTest {
        val food = fixture.category("food", "Продукты")
        val controller = controller()
        controller.start()
        advanceUntilIdle()
        controller.selectCategory(food.id)
        controller.setAmountInput("1000")
        controller.submitAmount()
        advanceUntilIdle()

        val cellId = controller.state.value.rows.single().cellId
        controller.selectCell(cellId)
        advanceUntilIdle()
        controller.requestReverse()
        advanceUntilIdle()
        assertNotNull(controller.state.value.confirm)

        controller.confirm()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(1, state.rows.size, "Строка остаётся: сторно — это корректировка, а не удаление (D-02)")
        assertEquals(Money.ZERO, state.rows.single().amount)
        assertTrue(state.notice.orEmpty().contains("Сторнировано"))
        assertEquals(2, fixture.ledger.revisionsOf(cellId).size)
    }

    @Test
    fun `дублирование прошлого месяца по умолчанию пропускает заполненные категории`() = runTest {
        val food = fixture.category("food", "Продукты")
        val transport = fixture.category("transport", "Транспорт")
        val controller = controller(period = january)
        controller.start()
        advanceUntilIdle()
        controller.selectCategory(food.id)
        controller.setAmountInput("1000")
        controller.submitAmount()
        advanceUntilIdle()

        // Во втором месяце уже заполнен «Транспорт» — его сумма не должна измениться.
        controller.moveTo(february)
        advanceUntilIdle()
        controller.selectCategory(transport.id)
        controller.setAmountInput("300")
        controller.submitAmount()
        advanceUntilIdle()

        controller.openDuplicateDialog()
        val dialog = assertNotNull(controller.state.value.duplicateDialog)
        assertEquals(january, dialog.source, "Источник по умолчанию — предыдущий месяц (F5.2)")
        assertEquals(february, dialog.target)
        assertEquals(ConflictPolicy.SKIP, dialog.conflictPolicy)
        assertFalse(dialog.copyComments, "Комментарии по умолчанию не копируются")

        controller.runDuplicate()
        advanceUntilIdle()

        val state = controller.state.value
        assertNull(state.duplicateDialog)
        assertEquals(2, state.rows.size)
        assertEquals(Money(100_000), state.rows.first { it.categoryName == "Продукты" }.amount)
        assertEquals(Money(30_000), state.rows.first { it.categoryName == "Транспорт" }.amount)
        assertTrue(state.report.orEmpty().contains("Скопировано: 1"), "Отчёт: ${state.report}")
    }

    @Test
    fun `перезапись месяца требует явного подтверждения`() = runTest {
        val food = fixture.category("food", "Продукты")
        val controller = controller(period = january)
        controller.start()
        advanceUntilIdle()
        controller.selectCategory(food.id)
        controller.setAmountInput("1000")
        controller.submitAmount()
        advanceUntilIdle()

        controller.moveTo(february)
        advanceUntilIdle()
        controller.selectCategory(food.id)
        controller.setAmountInput("250")
        controller.submitAmount()
        advanceUntilIdle()

        controller.openDuplicateDialog()
        controller.setConflictPolicy(ConflictPolicy.OVERWRITE)
        controller.runDuplicate()

        // F5.7: молчаливая перезапись запрещена — сначала диалог, данные не тронуты.
        val confirm = assertNotNull(controller.state.value.confirm)
        assertTrue(confirm.destructive)
        assertEquals(Money(25_000), controller.state.value.rows.single().amount)

        controller.confirm()
        advanceUntilIdle()

        assertEquals(Money(100_000), controller.state.value.rows.single().amount)
        assertTrue(controller.state.value.report.orEmpty().contains("перезаписано: 1"))
        assertNull(controller.state.value.confirm)
    }

    @Test
    fun `дублирование в тот же месяц отклоняется`() = runTest {
        fixture.category("food", "Продукты")
        val controller = controller()
        controller.start()
        advanceUntilIdle()

        controller.openDuplicateDialog()
        controller.shiftDuplicateSource(1)
        assertEquals(february, controller.state.value.duplicateDialog?.source)

        controller.runDuplicate()
        advanceUntilIdle()

        assertEquals("Месяц-источник совпадает с месяцем-назначением", controller.state.value.error)
        assertNotNull(controller.state.value.duplicateDialog, "Диалог остаётся открытым, чтобы выбрать другой месяц")
    }

    @Test
    fun `дельта к прошлому месяцу считается в шапке и в строках`() = runTest {
        val food = fixture.category("food", "Продукты")
        val controller = controller(period = january)
        controller.start()
        advanceUntilIdle()
        controller.selectCategory(food.id)
        controller.setAmountInput("1000")
        controller.submitAmount()
        advanceUntilIdle()

        controller.moveTo(february)
        advanceUntilIdle()
        controller.selectCategory(food.id)
        controller.setAmountInput("1200")
        controller.submitAmount()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(Money(20_000), state.totalDelta, "1200 - 1000 = +200")
        assertEquals(20, state.totalDeltaPercent)
        val row = state.rows.single()
        assertEquals(Money(100_000), row.previousAmount)
        assertEquals(Money(20_000), row.delta)
        assertEquals(20, row.deltaPercent)
    }

    @Test
    fun `доходы используют тот же экран со своим набором категорий`() = runTest {
        fixture.category("food", "Продукты", section = app.finance.core.model.CategorySection.EXPENSE)
        val salary = fixture.category("salary", "Зарплата", section = app.finance.core.model.CategorySection.INCOME)

        val expenses = controller(section = LedgerSection.EXPENSE)
        val incomes = controller(section = LedgerSection.INCOME)
        expenses.start()
        incomes.start()
        advanceUntilIdle()

        assertEquals(listOf("Продукты"), expenses.state.value.categories.map { it.name })
        assertEquals(listOf("Зарплата"), incomes.state.value.categories.map { it.name })

        incomes.selectCategory(salary.id)
        incomes.setAmountInput("90000")
        incomes.submitAmount()
        advanceUntilIdle()

        assertEquals(1, incomes.state.value.rows.size)
        assertTrue(expenses.state.value.rows.isEmpty(), "Доходы не попадают в расходы")
        assertEquals(LedgerSection.INCOME, incomes.state.value.section)
    }
}
