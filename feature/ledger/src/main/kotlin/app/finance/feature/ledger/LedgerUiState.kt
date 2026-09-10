package app.finance.feature.ledger

import app.finance.core.domain.ConflictPolicy
import app.finance.core.model.Category
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period

/**
 * Состояние экрана расходов/доходов.
 *
 * Экран один на две секции и различается только [LedgerUiState.section] (SPEC.md F2.2).
 * Все значения уже подготовлены к отображению: контроллер форматирует суммы, доли и
 * метки времени, чтобы в Compose не оставалось логики.
 */
data class LedgerUiState(
    val section: LedgerSection,
    val period: Period,
    val isLoading: Boolean = true,
    val categories: List<Category> = emptyList(),
    val rows: List<LedgerRow> = emptyList(),
    val total: Money = Money.ZERO,
    val previousTotal: Money = Money.ZERO,
    /** Итог прошлого месяца для Δ в шапке (F1.9); null — если прошлый месяц пуст. */
    val totalDelta: Money? = null,
    val totalDeltaPercent: Int? = null,
    val form: AmountFormState = AmountFormState(),
    val panel: CellPanelState? = null,
    val duplicateDialog: DuplicateDialogState? = null,
    /** Отчёт о дублировании месяца (F5.6). */
    val report: String? = null,
    /** Короткое сообщение об успехе операции. */
    val notice: String? = null,
    /** Сообщение об ошибке операции; показывается вместо снекбара. */
    val error: String? = null,
    val confirm: ConfirmRequest? = null,
) {
    val hasRows: Boolean get() = rows.isNotEmpty()
}

/** Строка таблицы месяца: одна строка на категорию (F1.2). */
data class LedgerRow(
    val cellId: String,
    val categoryId: String,
    val categoryName: String,
    val categoryColor: String,
    val amount: Money,
    val sharePercent: Int?,
    val previousAmount: Money?,
    val delta: Money?,
    val deltaPercent: Int?,
    val hasComment: Boolean,
)

/** Форма ввода суммы. После сохранения не закрывается — batch-ввод (F1.6). */
data class AmountFormState(
    val categoryId: String? = null,
    val amountText: String = "",
    val commentText: String = "",
    val error: String? = null,
    val isSaving: Boolean = false,
)

/** Панель выбранной ячейки: правка, история, сторно, удаление (F1.3). */
data class CellPanelState(
    val cellId: String,
    val categoryName: String,
    val categoryColor: String,
    val period: Period,
    val amountText: String,
    val commentText: String,
    val error: String? = null,
    val isDeleted: Boolean = false,
    val isSaving: Boolean = false,
    val revisions: List<RevisionRow> = emptyList(),
)

/** Запись журнала изменений ячейки, подготовленная к отображению. */
data class RevisionRow(
    val id: String,
    val operationLabel: String,
    val amountLabel: String,
    val deltaLabel: String?,
    val commentLabel: String?,
    val createdAtLabel: String,
)

/** Диалог «Дублировать месяц» (F5.2). */
data class DuplicateDialogState(
    val source: Period,
    val target: Period,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.SKIP,
    val copyComments: Boolean = false,
    val isRunning: Boolean = false,
) {
    val isSourceSameAsTarget: Boolean get() = source == target
}

/** Запрос подтверждения: решение об исполнении принимает контроллер по [id]. */
data class ConfirmRequest(
    val id: String,
    val title: String,
    val message: String,
    val confirmLabel: String,
    val destructive: Boolean = false,
)
