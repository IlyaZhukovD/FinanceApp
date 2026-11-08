package app.finance.feature.ledger

import app.finance.core.domain.AmountInput
import app.finance.core.domain.CategoryRepository
import app.finance.core.domain.CommentChange
import app.finance.core.domain.CommentMerger
import app.finance.core.domain.ConflictPolicy
import app.finance.core.domain.DeleteCell
import app.finance.core.domain.DuplicateOptions
import app.finance.core.domain.DuplicatePeriod
import app.finance.core.domain.EditCell
import app.finance.core.domain.LedgerRepository
import app.finance.core.domain.RecordAmount
import app.finance.core.domain.RecordAmountResult
import app.finance.core.domain.RestoreCell
import app.finance.core.model.Category
import app.finance.core.model.CategoryMonthRevision
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import app.finance.core.model.RevisionOp
import app.finance.core.ui.deltaPercent
import app.finance.core.ui.formatRu
import app.finance.core.ui.formatSignedRu
import app.finance.core.ui.formatTimestamp
import app.finance.core.ui.sharePercent
import app.finance.core.ui.titleRu
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Контроллер экрана расходов/доходов (SPEC.md F1, F2, F5).
 *
 * Один класс на обе секции — различие только в [section] (F2.2: копипаста экранов
 * недопустима). Compose здесь не используется: состояние отдаётся как [StateFlow],
 * поэтому логика экрана проверяется обычными тестами без UI-инфраструктуры.
 *
 * Потоки: чтение и запись БД выполняются на [ioDispatcher], потому что
 * `SqlDelightTransactionRunner` внутри использует `runBlocking` (см. предупреждение
 * в `core:data`). Обновления состояния потокобезопасны (`MutableStateFlow`).
 *
 * Не реализовано в M2 и будет добавлено позже: Undo по ⌘/Ctrl+Z (F1.8), сортировка
 * таблицы по имени/доле/Δ (F1.2), поиск и «недавние» в пикере категорий (F1.4),
 * запоминание последнего открытого периода между запусками (F1.7).
 */
class LedgerController(
    val section: LedgerSection,
    private val categories: CategoryRepository,
    private val ledger: LedgerRepository,
    private val recordAmount: RecordAmount,
    private val editCell: EditCell,
    private val deleteCell: DeleteCell,
    private val restoreCell: RestoreCell,
    private val duplicatePeriod: DuplicatePeriod,
    private val initialPeriod: Period,
    private val clock: Clock,
    private val zone: ZoneId,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val _state = MutableStateFlow(LedgerUiState(section = section, period = initialPeriod))

    val state: StateFlow<LedgerUiState> = _state.asStateFlow()

    /** Первичная загрузка. Вызывается из `LaunchedEffect` при входе экрана в композицию. */
    fun start() = reloadMonth()

    // ── Период (F1.7) ──────────────────────────────────────────────────────────────

    fun previousMonth() = moveTo(_state.value.period.minusMonths(1))

    fun nextMonth() = moveTo(_state.value.period.plusMonths(1))

    fun today() = moveTo(Period.now(clock, zone))

    fun moveTo(period: Period) {
        if (period == _state.value.period) return
        // Панель ячейки относится к прежнему месяцу — закрываем её вместе со сменой периода.
        _state.update { it.copy(period = period, panel = null, error = null, notice = null) }
        reloadMonth()
    }

    // ── Форма ввода суммы (F1.4, F1.5, F1.6) ───────────────────────────────────────

    fun selectCategory(categoryId: String) {
        _state.update { it.copy(form = it.form.copy(categoryId = categoryId, error = null)) }
    }

    fun setAmountInput(text: String) {
        _state.update { it.copy(form = it.form.copy(amountText = text, error = null)) }
    }

    fun setCommentInput(text: String) {
        _state.update { it.copy(form = it.form.copy(commentText = text)) }
    }

    /**
     * Сохранение суммы. Если ячейка `(категория, период)` уже есть, сумма прибавляется
     * к ней — это делает use-case `RecordAmount` (F1.5).
     *
     * Форма не закрывается: сумма и комментарий очищаются, категория и период остаются,
     * поэтому несколько трат вводятся подряд (batch-ввод, F1.6).
     */
    fun submitAmount() {
        val current = _state.value
        val form = current.form
        val categoryId = form.categoryId

        if (categoryId == null) {
            _state.update { it.copy(form = it.form.copy(error = "Выберите категорию")) }
            return
        }
        val amount = Money.parse(form.amountText)
        if (amount == null) {
            _state.update { it.copy(form = it.form.copy(error = "Введите сумму, например 1500 или 1 500,50")) }
            return
        }
        if (amount.isZero()) {
            _state.update { it.copy(form = it.form.copy(error = "Сумма не может быть нулевой")) }
            return
        }

        val period = current.period
        val comment = form.commentText.trim().ifEmpty { null }
        val categoryName = current.categories.firstOrNull { it.id == categoryId }?.name.orEmpty()

        scope.launch {
            _state.update { it.copy(form = it.form.copy(isSaving = true, error = null)) }
            runCatching {
                io {
                    recordAmount(
                        AmountInput(
                            section = section,
                            categoryId = categoryId,
                            period = period,
                            amount = amount,
                            comment = comment,
                        ),
                    )
                }
            }
                .onFailure { error -> _state.update { state -> failed(state, error) } }
                .onSuccess { result ->
                    _state.update { state ->
                        state.copy(form = state.form.copy(amountText = "", commentText = "", error = null))
                    }
                    reloadAfterChange(notice = recordedNotice(result, categoryName))
                }
        }
    }

    // ── Панель ячейки (F1.3) ───────────────────────────────────────────────────────

    fun selectCell(cellId: String) {
        scope.launch {
            runCatching { io { readPanel(cellId) } }
                .onFailure { error -> _state.update { state -> failed(state, error) } }
                .onSuccess { panel -> _state.update { it.copy(panel = panel, error = null) } }
        }
    }

    fun closePanel() {
        _state.update { it.copy(panel = null) }
    }

    fun setPanelAmount(text: String) {
        _state.update { state -> state.copy(panel = state.panel?.copy(amountText = text, error = null)) }
    }

    fun setPanelComment(text: String) {
        _state.update { state -> state.copy(panel = state.panel?.copy(commentText = text)) }
    }

    /** Явная правка: сумма заменяется, комментарий перезаписывается (без слияния, F1.5). */
    fun savePanel() {
        val panel = _state.value.panel ?: return
        val amount = Money.parse(panel.amountText)
        if (amount == null) {
            updatePanelError("Введите сумму, например 1500 или 1 500,50")
            return
        }
        if (amount.isZero()) {
            updatePanelError("Сумма не может быть нулевой")
            return
        }

        val cellId = panel.cellId
        val comment = panel.commentText.trim().ifEmpty { null }

        scope.launch {
            _state.update { state -> state.copy(panel = state.panel?.copy(isSaving = true, error = null)) }
            runCatching { io { editCell(cellId, amount = amount, comment = CommentChange.Set(comment)) } }
                .onFailure { error -> _state.update { state -> failed(state, error) } }
                .onSuccess { result ->
                    val notice = if (result.revisions.isEmpty()) {
                        "Изменений нет"
                    } else {
                        "Сохранено, записей в истории: ${result.revisions.size}"
                    }
                    reloadAfterChange(notice = notice)
                }
        }
    }

    /** Сторно: прибавляем обратную сумму, поэтому ячейка и история сохраняются (D-02). */
    fun requestReverse() {
        val panel = _state.value.panel ?: return
        scope.launch {
            val cell = runCatching { io { ledger.cellById(panel.cellId) } }.getOrNull()
            if (cell == null || cell.amount.isZero()) {
                _state.update { it.copy(error = "Сумма ячейки нулевая: сторнировать нечего") }
                return@launch
            }
            _state.update {
                it.copy(
                    confirm = ConfirmRequest(
                        id = CONFIRM_REVERSE,
                        title = "Сторнировать сумму?",
                        message = "«${panel.categoryName}», ${panel.period.titleRu()}: к сумме будет " +
                            "прибавлено ${(-cell.amount).formatRu()}. Строка останется в месяце, " +
                            "операция попадёт в историю.",
                        confirmLabel = "Сторнировать",
                        destructive = true,
                    ),
                )
            }
        }
    }

    fun requestDelete() {
        val panel = _state.value.panel ?: return
        _state.update {
            it.copy(
                confirm = ConfirmRequest(
                    id = CONFIRM_DELETE,
                    title = "Удалить ячейку?",
                    message = "«${panel.categoryName}», ${panel.period.titleRu()}: сумма исчезнет из " +
                        "списка месяца. Удаление обратимо — ячейку можно восстановить из панели.",
                    confirmLabel = "Удалить",
                    destructive = true,
                ),
            )
        }
    }

    /** Подтверждение действия из диалога. */
    fun confirm() {
        val request = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        when (request.id) {
            CONFIRM_DELETE -> performDelete()
            CONFIRM_REVERSE -> performReverse()
            CONFIRM_OVERWRITE -> performDuplicate()
        }
    }

    fun dismissConfirm() {
        _state.update { it.copy(confirm = null) }
    }

    private fun performDelete() {
        val cellId = _state.value.panel?.cellId ?: return
        scope.launch {
            runCatching { io { deleteCell(cellId) } }
                .onFailure { error -> _state.update { state -> failed(state, error) } }
                .onSuccess { reloadAfterChange(notice = "Ячейка удалена") }
        }
    }

    private fun performReverse() {
        val cellId = _state.value.panel?.cellId ?: return
        scope.launch {
            val cell = runCatching { io { ledger.cellById(cellId) } }.getOrNull()
            if (cell == null) {
                _state.update { it.copy(error = "Ячейка не найдена") }
                return@launch
            }
            runCatching {
                io {
                    recordAmount(
                        AmountInput(
                            section = section,
                            categoryId = cell.categoryId,
                            period = cell.period,
                            amount = -cell.amount,
                            comment = null,
                        ),
                    )
                }
            }
                .onFailure { error -> _state.update { state -> failed(state, error) } }
                .onSuccess { reloadAfterChange(notice = "Сторнировано: ${cell.amount.formatRu()}") }
        }
    }

    /** Восстановление удалённой ячейки доступно из её панели. */
    fun restoreSelected() {
        val cellId = _state.value.panel?.cellId ?: return
        scope.launch {
            runCatching { io { restoreCell(cellId) } }
                .onFailure { error -> _state.update { state -> failed(state, error) } }
                .onSuccess { reloadAfterChange(notice = "Ячейка восстановлена") }
        }
    }

    // ── Дублирование месяца (F5) ───────────────────────────────────────────────────

    fun openDuplicateDialog() {
        val target = _state.value.period
        _state.update {
            it.copy(
                error = null,
                duplicateDialog = DuplicateDialogState(source = target.minusMonths(1), target = target),
            )
        }
    }

    fun closeDuplicateDialog() {
        _state.update { it.copy(duplicateDialog = null) }
    }

    fun shiftDuplicateSource(months: Int) {
        _state.update { state ->
            val dialog = state.duplicateDialog ?: return@update state
            val shifted = runCatching { dialog.source.plusMonths(months) }.getOrNull() ?: return@update state
            state.copy(duplicateDialog = dialog.copy(source = shifted))
        }
    }

    fun setConflictPolicy(policy: ConflictPolicy) {
        _state.update { state ->
            val dialog = state.duplicateDialog ?: return@update state
            state.copy(duplicateDialog = dialog.copy(conflictPolicy = policy))
        }
    }

    fun setCopyComments(copyComments: Boolean) {
        _state.update { state ->
            val dialog = state.duplicateDialog ?: return@update state
            state.copy(duplicateDialog = dialog.copy(copyComments = copyComments))
        }
    }

    /**
     * Запуск дублирования. При политике OVERWRITE сначала требуется явное
     * подтверждение: молчаливая перезапись запрещена (F5.7).
     */
    fun runDuplicate() {
        val dialog = _state.value.duplicateDialog ?: return
        if (dialog.isSourceSameAsTarget) {
            _state.update { it.copy(error = "Месяц-источник совпадает с месяцем-назначением") }
            return
        }
        if (dialog.conflictPolicy == ConflictPolicy.OVERWRITE) {
            _state.update {
                it.copy(
                    confirm = ConfirmRequest(
                        id = CONFIRM_OVERWRITE,
                        title = "Перезаписать суммы?",
                        message = "Существующие суммы за ${dialog.target.titleRu()} будут заменены " +
                            "значениями из ${dialog.source.titleRu()}. Прежние суммы останутся в истории.",
                        confirmLabel = "Перезаписать",
                        destructive = true,
                    ),
                )
            }
            return
        }
        performDuplicate()
    }

    private fun performDuplicate() {
        val dialog = _state.value.duplicateDialog ?: return
        scope.launch {
            _state.update { state -> state.copy(duplicateDialog = state.duplicateDialog?.copy(isRunning = true)) }
            runCatching {
                io {
                    duplicatePeriod(
                        section = section,
                        source = dialog.source,
                        target = dialog.target,
                        options = DuplicateOptions(
                            conflictPolicy = dialog.conflictPolicy,
                            copyComments = dialog.copyComments,
                        ),
                    )
                }
            }
                .onFailure { error -> _state.update { state -> failed(state, error) } }
                .onSuccess { report ->
                    // F5.6: экран переходит в целевой месяц. Период меняем без отдельной
                    // загрузки, чтобы не читать месяц дважды и не потерять отчёт.
                    _state.update {
                        it.copy(duplicateDialog = null, period = dialog.target, panel = null)
                    }
                    reloadAfterChange(report = report.summaryRu())
                }
        }
    }

    // ── Служебное ──────────────────────────────────────────────────────────────────

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null, report = null) }
    }

    /** Все обращения к БД — на фоновом диспетчерце (см. KDoc класса). */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    private fun reloadMonth() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            runCatching { io { readMonth(_state.value.period) } }
                .onFailure { error -> _state.update { state -> failed(state, error) } }
                .onSuccess { snapshot -> _state.update { state -> applySnapshot(state, snapshot) } }
        }
    }

    /** Перечитывает месяц и открытую панель после успешной операции. */
    private suspend fun reloadAfterChange(notice: String? = null, report: String? = null) {
        val period = _state.value.period
        val panelId = _state.value.panel?.cellId
        runCatching { io { readMonth(period) } }
            .onFailure { error -> _state.update { state -> failed(state, error) } }
            .onSuccess { snapshot ->
                // Панель перечитывается, чтобы показать свежую сумму и историю.
                // Если ячейка исчезла, панель закрывается сама.
                val panel = panelId?.let { id -> runCatching { io { readPanel(id) } }.getOrNull() }
                _state.update { state ->
                    applySnapshot(state, snapshot).copy(
                        notice = notice,
                        report = report ?: state.report,
                        panel = panel,
                    )
                }
            }
    }

    private fun updatePanelError(message: String) {
        _state.update { state -> state.copy(panel = state.panel?.copy(error = message)) }
    }

    private suspend fun readMonth(period: Period): MonthSnapshot {
        // Архивные категории тоже нужны: их суммы остаются в истории месяца (F3.2).
        val categoriesById = categories
            .bySection(CategorySection.of(section), includeArchived = true)
            .associateBy { it.id }
        val cells = ledger.cellsForPeriod(section, period)
        val previousByCategory = ledger.cellsForPeriod(section, period.minusMonths(1)).associateBy { it.categoryId }
        val total = totalOf(cells.map { it.amount })
        val previousTotal = totalOf(previousByCategory.values.map { it.amount })

        val rows = cells.map { cell ->
            val category = categoriesById[cell.categoryId]
            val previous = previousByCategory[cell.categoryId]
            LedgerRow(
                cellId = cell.id,
                categoryId = cell.categoryId,
                categoryName = category?.name ?: UNKNOWN_CATEGORY,
                categoryColor = category?.color ?: NEUTRAL_COLOR,
                amount = cell.amount,
                sharePercent = sharePercent(cell.amount, total),
                previousAmount = previous?.amount,
                delta = previous?.let { differenceOrNull(cell.amount, it.amount) },
                deltaPercent = previous?.let { deltaPercent(cell.amount, it.amount) },
                hasComment = cell.hasComment,
            )
        }.sortedByDescending { it.amount.minor }

        return MonthSnapshot(
            selectableCategories = categoriesById.values.filter { it.isSelectable }.sortedBy { it.sortOrder },
            rows = rows,
            total = total,
            previousTotal = previousTotal,
            // Если в прошлом месяце не было ни одной записи, дельта не определена:
            // «0,00 ₽» выглядело бы как осмысленное сравнение с пустым месяцем.
            totalDelta = if (previousByCategory.isEmpty()) null else differenceOrNull(total, previousTotal),
            totalDeltaPercent = deltaPercent(total, previousTotal),
        )
    }

    private suspend fun readPanel(cellId: String): CellPanelState {
        val cell = requireNotNull(ledger.cellById(cellId)) { "Ячейка $cellId не найдена" }
        val category = categories.byId(cell.categoryId)
        val revisions = ledger.revisionsOf(cellId).reversed().map(::revisionRow)

        return CellPanelState(
            cellId = cell.id,
            categoryName = category?.name ?: UNKNOWN_CATEGORY,
            categoryColor = category?.color ?: NEUTRAL_COLOR,
            period = cell.period,
            amountText = cell.amount.toPlainString().replace('.', ','),
            commentText = cell.comment.orEmpty(),
            isDeleted = cell.isDeleted,
            revisions = revisions,
        )
    }

    private fun revisionRow(revision: CategoryMonthRevision): RevisionRow = RevisionRow(
        id = revision.id,
        operationLabel = operationLabel(revision.op),
        amountLabel = revision.amountAfter.formatRu(),
        deltaLabel = revision.delta?.takeIf { !it.isZero() }?.formatSignedRu(),
        commentLabel = revisionComment(revision),
        createdAtLabel = formatTimestamp(revision.createdAtUtc, zone),
    )

    /**
     * Комментарий записи истории — текст именно этой операции, а не весь накопленный
     * комментарий ячейки: так у каждого «Прибавлено» видна своя строка.
     */
    private fun revisionComment(revision: CategoryMonthRevision): String? = when (revision.op) {
        RevisionOp.SET_COMMENT -> CommentMerger.replace(revision.commentAfter) ?: "Комментарий удалён"
        else -> CommentMerger.addedPart(revision.commentBefore, revision.commentAfter)
    }

    private fun operationLabel(op: RevisionOp): String = when (op) {
        RevisionOp.CREATE -> "Создано"
        RevisionOp.ADD -> "Прибавлено"
        RevisionOp.SET_AMOUNT -> "Изменена сумма"
        RevisionOp.SET_COMMENT -> "Изменён комментарий"
        RevisionOp.DELETE -> "Удалено"
        RevisionOp.UNDELETE -> "Восстановлено"
        RevisionOp.DUPLICATE -> "Из дублирования"
        RevisionOp.IMPORT -> "Импорт"
        RevisionOp.UNDO -> "Отмена"
    }

    private fun applySnapshot(current: LedgerUiState, snapshot: MonthSnapshot): LedgerUiState {
        // Выбранная в форме категория могла быть архивирована — тогда берём первую доступную.
        val formCategoryId = current.form.categoryId
            ?.takeIf { id -> snapshot.selectableCategories.any { it.id == id } }
            ?: snapshot.selectableCategories.firstOrNull()?.id

        return current.copy(
            isLoading = false,
            categories = snapshot.selectableCategories,
            rows = snapshot.rows,
            total = snapshot.total,
            previousTotal = snapshot.previousTotal,
            totalDelta = snapshot.totalDelta,
            totalDeltaPercent = snapshot.totalDeltaPercent,
            form = current.form.copy(categoryId = formCategoryId, isSaving = false),
            error = null,
        )
    }

    private fun failed(state: LedgerUiState, error: Throwable): LedgerUiState = state.copy(
        isLoading = false,
        // Сообщения доменных use-case'ов уже на русском и понятны пользователю.
        error = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
        form = state.form.copy(isSaving = false),
        panel = state.panel?.copy(isSaving = false),
        duplicateDialog = state.duplicateDialog?.copy(isRunning = false),
    )

    private fun recordedNotice(result: RecordAmountResult, categoryName: String): String = when (result) {
        is RecordAmountResult.Created -> "Добавлено ${result.cell.amount.formatRu()} · $categoryName"
        is RecordAmountResult.Accumulated -> {
            val delta = result.revision.delta?.formatRu().orEmpty()
            "Прибавлено $delta · $categoryName: теперь ${result.cell.amount.formatRu()}"
        }

        is RecordAmountResult.AlreadyApplied -> "Эта операция уже учтена, сумма не изменилась"
    }

    private fun totalOf(amounts: List<Money>): Money {
        if (amounts.isEmpty()) return Money.ZERO
        // v1 одновалютный; при появлении нескольких валют итоги разводятся по валютам (M9).
        val currency = amounts.first().currency
        var total = Money(0L, currency)
        for (amount in amounts) {
            if (amount.currency == currency) total += amount
        }
        return total
    }

    private fun differenceOrNull(current: Money, previous: Money): Money? {
        if (current.currency != previous.currency) return null
        return runCatching { current - previous }.getOrNull()
    }

    private data class MonthSnapshot(
        val selectableCategories: List<Category>,
        val rows: List<LedgerRow>,
        val total: Money,
        val previousTotal: Money,
        val totalDelta: Money?,
        val totalDeltaPercent: Int?,
    )

    private companion object {
        const val UNKNOWN_CATEGORY = "Категория не найдена"
        const val NEUTRAL_COLOR = "#7A8189"
        const val CONFIRM_DELETE = "delete-cell"
        const val CONFIRM_REVERSE = "reverse-cell"
        const val CONFIRM_OVERWRITE = "overwrite-month"
    }
}
