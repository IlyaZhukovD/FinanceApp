package app.finance.feature.ledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.finance.core.domain.ConflictPolicy
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.ui.shareLabelRu
import app.finance.core.ui.CategoryDot
import app.finance.core.ui.CheckboxRow
import app.finance.core.ui.ConfirmDialog
import app.finance.core.ui.DeltaText
import app.finance.core.ui.EmptyStateCard
import app.finance.core.ui.FinanceCard
import app.finance.core.ui.FinancePalette
import app.finance.core.ui.FinanceRadius
import app.finance.core.ui.FinanceSpace
import app.finance.core.ui.FinanceTextField
import app.finance.core.ui.HeaderCell
import app.finance.core.ui.HeroNumber
import app.finance.core.ui.LabeledControl
import app.finance.core.ui.MoneyCell
import app.finance.core.ui.NoticeBar
import app.finance.core.ui.Numbers
import app.finance.core.ui.PeriodNavigator
import app.finance.core.ui.PrimaryButton
import app.finance.core.ui.QuietButton
import app.finance.core.ui.SectionHeader
import app.finance.core.ui.SecondaryButton
import app.finance.core.ui.TableHeaderRow
import app.finance.core.ui.TableRow
import app.finance.core.ui.TextCell
import app.finance.core.ui.deltaColor
import app.finance.core.ui.formatRu
import app.finance.core.ui.formatSignedRu
import app.finance.core.ui.titleRu
import app.finance.feature.categories.CategoriesController
import app.finance.feature.categories.CategoryManagerDialog

/**
 * Экран расходов и доходов (SPEC.md F1, F2, F5).
 *
 * Здесь только разметка и проброс событий в [LedgerController]: ни чтения БД,
 * ни расчётов. Один и тот же composable обслуживает обе секции (F2.2).
 */
@Composable
fun LedgerScreen(
    controller: LedgerController,
    categories: CategoriesController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()

    // Первичная загрузка месяца. Повторный вход экрана в композицию просто перечитывает данные.
    LaunchedEffect(controller) { controller.start() }

    Column(modifier = modifier.fillMaxSize()) {
        state.error?.let { message ->
            NoticeBar(text = message, onDismiss = controller::dismissError, error = true)
        }
        state.report?.let { report ->
            NoticeBar(text = "Дублирование месяца · $report", onDismiss = controller::dismissNotice)
        }
        state.notice?.let { notice ->
            NoticeBar(text = notice, onDismiss = controller::dismissNotice)
        }

        LedgerHeader(state = state, controller = controller, categories = categories)
        AmountInputForm(state = state, controller = controller)

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LedgerTable(
                state = state,
                controller = controller,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            state.panel?.let { panel ->
                CellPanel(panel = panel, controller = controller)
            }
        }
    }

    state.confirm?.let { request ->
        ConfirmDialog(
            title = request.title,
            message = request.message,
            confirmLabel = request.confirmLabel,
            onConfirm = controller::confirm,
            onDismiss = controller::dismissConfirm,
            destructive = request.destructive,
        )
    }

    state.duplicateDialog?.let { dialog ->
        DuplicateDialog(dialog = dialog, controller = controller)
    }

    // Управление категориями раздела (F3.1): диалог общий для расходов, доходов и активов.
    // После закрытия месяц перечитывается — новая категория появляется в пикере ввода,
    // архивированная из него исчезает (F3.2).
    CategoryManagerDialog(controller = categories, onClosed = controller::start)
}

@Composable
private fun LedgerHeader(
    state: LedgerUiState,
    controller: LedgerController,
    categories: CategoriesController,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = FinanceSpace.Lg, vertical = FinanceSpace.Md),
        verticalArrangement = Arrangement.spacedBy(FinanceSpace.Sm),
    ) {
        SectionHeader(
            title = state.section.titleRu(),
            subtitle = "Одна строка на категорию: повторный ввод прибавляется к сумме",
            actions = {
                // Справочник раздела (F3.1): создать категорию, переименовать, отправить в
                // архив. «Удаление» — именно архивация: суммы и история месяца сохраняются (F3.2).
                SecondaryButton(text = "Категории", onClick = categories::open)
                Spacer(modifier = Modifier.width(FinanceSpace.Sm))
                SecondaryButton(text = "Дублировать прошлый месяц", onClick = controller::openDuplicateDialog)
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
        ) {
            PeriodNavigator(
                period = state.period,
                onPrevious = controller::previousMonth,
                onNext = controller::nextMonth,
                onToday = controller::today,
            )
            Spacer(modifier = Modifier.weight(1f))
            // Итог месяца — главная величина экрана, поэтому число-герой и табличные цифры:
            // при навигации по месяцам разряды не должны «прыгать».
            Column(horizontalAlignment = Alignment.End) {
                Text(text = state.total.formatRu(), style = HeroNumber)
                Text(
                    text = "Категорий: ${state.rows.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = FinancePalette.TextSecondary,
                )
                Text(
                    text = deltaLabel(state.totalDelta, state.totalDeltaPercent),
                    style = MaterialTheme.typography.labelMedium,
                    color = sectionDeltaColor(state.totalDelta, state.section),
                )
            }
        }
    }
}

/** Δ к прошлому месяцу: «+1 200,00 ₽ (+12 %) к прошлому месяцу» или «—», если данных нет. */
private fun deltaLabel(delta: Money?, percent: Int?): String {
    if (delta == null || delta.isZero()) return "К прошлому месяцу: —"
    val percentLabel = percent?.let { " (${if (it > 0) "+" else ""}$it %)" }.orEmpty()
    return "К прошлому месяцу: ${delta.formatSignedRu()}$percentLabel"
}

/**
 * Цвет дельты зависит от раздела: для расходов рост — плохо, для доходов — хорошо.
 *
 * Семантику задаёт общий [deltaColor], а не два «своих» оттенка: зелёный и красный обязаны
 * означать одно и то же во всех разделах, иначе пользователь переучивается на каждом экране.
 */
private fun sectionDeltaColor(delta: Money?, section: LedgerSection): Color {
    if (delta == null || delta.isZero()) return FinancePalette.TextSecondary
    return deltaColor(
        deltaMinor = delta.minor,
        favorableWhenPositive = section == LedgerSection.INCOME,
    )
}

/** Форма ввода суммы: batch-ввод, не закрывается после сохранения (F1.4, F1.6). */
@Composable
private fun AmountInputForm(state: LedgerUiState, controller: LedgerController) {
    val form = state.form
    var categoryPickerExpanded by remember { mutableStateOf(false) }
    val selectedCategory = state.categories.firstOrNull { it.id == form.categoryId }
    val selectedCategoryName = selectedCategory?.name ?: "Категория"

    FinanceCard(modifier = Modifier.padding(horizontal = FinanceSpace.Lg)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            // К верху, а не по центру: под полем суммы появляется текст ошибки, и при
            // центрировании он сдвигал бы всю строку вместе с категорией и кнопкой.
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
        ) {
            // Категории и кнопке нужна та же вертикальная привязка, что и у полей:
            // LabeledControl резервирует строку метки, поэтому все контролы формы стоят
            // на одной линии, а не центрируются по блоку «метка + поле».
            LabeledControl(label = "Категория") {
                Box {
                    SecondaryButton(
                        text = selectedCategoryName,
                        onClick = { categoryPickerExpanded = true },
                        enabled = state.categories.isNotEmpty(),
                        icon = selectedCategory?.let { category -> { CategoryDot(hexColor = category.color) } },
                    )
                    DropdownMenu(
                        expanded = categoryPickerExpanded,
                        onDismissRequest = { categoryPickerExpanded = false },
                    ) {
                        state.categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                leadingIcon = { CategoryDot(hexColor = category.color) },
                                onClick = {
                                    controller.selectCategory(category.id)
                                    categoryPickerExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            FinanceTextField(
                value = form.amountText,
                onValueChange = controller::setAmountInput,
                label = "Сумма",
                placeholder = "0,00",
                modifier = Modifier.width(200.dp),
                isError = form.error != null,
                supportingText = form.error,
                textAlign = TextAlign.End,
            )

            FinanceTextField(
                value = form.commentText,
                onValueChange = controller::setCommentInput,
                label = "Комментарий",
                placeholder = "Необязательно",
                modifier = Modifier.weight(1f),
            )

            LabeledControl {
                PrimaryButton(
                    text = if (form.isSaving) "Сохраняем…" else "Добавить",
                    onClick = controller::submitAmount,
                    enabled = !form.isSaving && state.categories.isNotEmpty(),
                )
            }
        }
    }
}

@Composable
private fun LedgerTable(state: LedgerUiState, controller: LedgerController, modifier: Modifier = Modifier) {
    when {
        state.isLoading && !state.hasRows -> Text(
            text = "Загрузка…",
            modifier = modifier.padding(FinanceSpace.Lg),
            style = MaterialTheme.typography.bodyMedium,
            color = FinancePalette.TextSecondary,
        )

        !state.hasRows -> EmptyStateCard(
            title = "За ${state.period.titleRu()} записей нет",
            hint = "Введите сумму в форме выше: повторный ввод в ту же категорию прибавится к существующей.",
            modifier = modifier,
        )

        else -> FinanceCard(modifier = modifier.padding(FinanceSpace.Lg)) {
            // Шапка закреплена: таблица длинная, и без sticky-заголовка невозможно понять,
            // к какой колонке относится число внизу экрана.
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                stickyHeader { LedgerTableHeader() }
                itemsIndexed(state.rows, key = { _, row -> row.cellId }) { index, row ->
                    LedgerRowView(
                        row = row,
                        section = state.section,
                        selected = state.panel?.cellId == row.cellId,
                        alternate = index % 2 == 1,
                        showDivider = index != state.rows.lastIndex,
                        onClick = { controller.selectCell(row.cellId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LedgerTableHeader() {
    TableHeaderRow {
        HeaderCell(text = "Категория", weight = W_NAME)
        HeaderCell(text = "Доля", weight = W_SHARE, alignEnd = true)
        HeaderCell(text = "Δ к прошлому", weight = W_DELTA, alignEnd = true)
        HeaderCell(text = "Сумма", weight = W_AMOUNT, alignEnd = true)
        Spacer(modifier = Modifier.width(COMMENT_WIDTH))
    }
}

/**
 * Строка таблицы: 48px, наведение, выделение выбранной ячейки.
 *
 * Δ показана и в процентах, и в деньгах: процент отвечает на вопрос «насколько сильно
 * изменилось», сумма — на вопрос «на сколько рублей». Стрелка обязательна — цвет сам по
 * себе не несёт смысла, а для части пользователей неразличим.
 */
@Composable
private fun LedgerRowView(
    row: LedgerRow,
    section: LedgerSection,
    selected: Boolean,
    alternate: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val favorableWhenPositive = section == LedgerSection.INCOME

    TableRow(
        // Подсветка выбора задана снаружи строки: внутренний фон hover и зебры рисуются
        // поверх, поэтому при наведении приоритет у hover — так и должно быть.
        modifier = if (selected) Modifier.background(FinancePalette.PrimaryLight) else Modifier,
        alternate = alternate && !selected,
        showDivider = showDivider,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.weight(W_NAME).padding(horizontal = FinanceSpace.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryDot(hexColor = row.categoryColor)
            Spacer(modifier = Modifier.width(FinanceSpace.Sm))
            Text(
                text = row.categoryName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        TextCell(
            text = shareLabelRu(row.sharePercent, row.amount),
            weight = W_SHARE,
            alignEnd = true,
            color = FinancePalette.TextSecondary,
        )

        Row(
            modifier = Modifier.weight(W_DELTA).padding(horizontal = FinanceSpace.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm),
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                DeltaText(percent = row.deltaPercent, favorableWhenPositive = favorableWhenPositive)
            }
            Text(
                text = row.delta?.formatSignedRu() ?: "—",
                style = Numbers,
                color = sectionDeltaColor(row.delta, section),
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }

        MoneyCell(money = row.amount, weight = W_AMOUNT)

        // Индикатор комментария (F1.2): символ вместо иконки, чтобы не тянуть набор иконок.
        Text(
            text = if (row.hasComment) "\u270E" else "",
            modifier = Modifier.width(COMMENT_WIDTH),
            style = MaterialTheme.typography.bodySmall,
            color = FinancePalette.TextSecondary,
            textAlign = TextAlign.End,
        )
    }
}

/** Панель ячейки: правка, история, сторно, удаление, восстановление (F1.3). */
@Composable
private fun CellPanel(panel: CellPanelState, controller: LedgerController) {
    Surface(
        modifier = Modifier.width(PANEL_WIDTH).fillMaxHeight(),
        color = FinancePalette.Background,
        border = BorderStroke(1.dp, FinancePalette.Divider),
    ) {
        Column(
            modifier = Modifier
                .padding(FinanceSpace.Lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryDot(hexColor = panel.categoryColor)
                Spacer(modifier = Modifier.width(FinanceSpace.Sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = panel.categoryName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = panel.period.titleRu(),
                        style = MaterialTheme.typography.labelSmall,
                        color = FinancePalette.TextSecondary,
                    )
                }
                QuietButton(text = "Закрыть", onClick = controller::closePanel)
            }

            if (panel.isDeleted) {
                Text(
                    text = "Ячейка удалена и не участвует в итоге месяца.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinancePalette.Error,
                )
                PrimaryButton(
                    text = "Восстановить",
                    onClick = controller::restoreSelected,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                FinanceTextField(
                    value = panel.amountText,
                    onValueChange = controller::setPanelAmount,
                    label = "Сумма",
                    placeholder = "0,00",
                    modifier = Modifier.fillMaxWidth(),
                    isError = panel.error != null,
                    supportingText = panel.error,
                    textAlign = TextAlign.End,
                )
                FinanceTextField(
                    value = panel.commentText,
                    onValueChange = controller::setPanelComment,
                    label = "Комментарий",
                    placeholder = "Необязательно",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
                    PrimaryButton(
                        text = if (panel.isSaving) "Сохраняем…" else "Сохранить",
                        onClick = controller::savePanel,
                        enabled = !panel.isSaving,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Сторнировать",
                        onClick = controller::requestReverse,
                        modifier = Modifier.weight(1f),
                    )
                }
                SecondaryButton(
                    text = "Удалить ячейку",
                    onClick = controller::requestDelete,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(text = "История изменений", style = MaterialTheme.typography.titleSmall)
            if (panel.revisions.isEmpty()) {
                Text(
                    text = "Записей пока нет",
                    style = MaterialTheme.typography.bodySmall,
                    color = FinancePalette.TextSecondary,
                )
            } else {
                // Список ревизий короткий, поэтому обычный Column: вложенный LazyColumn
                // внутри вертикально прокручиваемой панели получил бы бесконечную высоту.
                Column(verticalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
                    panel.revisions.forEach { revision -> RevisionView(revision) }
                }
            }
        }
    }
}

@Composable
private fun RevisionView(revision: RevisionRow) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = FinanceRadius.controlShape,
        color = FinancePalette.Surface,
        border = BorderStroke(1.dp, FinancePalette.Divider),
    ) {
        Column(
            modifier = Modifier.padding(FinanceSpace.Md),
            verticalArrangement = Arrangement.spacedBy(FinanceSpace.Xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = revision.operationLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                revision.deltaLabel?.let { delta ->
                    Text(
                        text = delta,
                        style = MaterialTheme.typography.bodySmall,
                        color = FinancePalette.TextSecondary,
                    )
                }
            }
            Text(text = revision.amountLabel, style = Numbers)
            revision.commentLabel?.let { comment ->
                Text(
                    text = comment,
                    style = MaterialTheme.typography.bodySmall,
                    color = FinancePalette.TextSecondary,
                )
            }
            Text(
                text = revision.createdAtLabel,
                style = MaterialTheme.typography.labelSmall,
                color = FinancePalette.TextDisabled,
            )
        }
    }
}

/** Диалог дублирования месяца (F5.2): источник, политика конфликтов, комментарии. */
@Composable
private fun DuplicateDialog(dialog: DuplicateDialogState, controller: LedgerController) {
    AlertDialog(
        onDismissRequest = controller::closeDuplicateDialog,
        title = { Text("Дублировать месяц") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Источник", style = MaterialTheme.typography.bodyMedium)
                    SecondaryButton(text = "‹", onClick = { controller.shiftDuplicateSource(-1) })
                    Text(text = dialog.source.titleRu(), style = MaterialTheme.typography.bodyLarge)
                    SecondaryButton(text = "›", onClick = { controller.shiftDuplicateSource(1) })
                }
                Text(
                    text = "Назначение: ${dialog.target.titleRu()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Если целевой месяц уже заполнен",
                    style = MaterialTheme.typography.labelLarge,
                )
                ConflictPolicy.entries.forEach { policy ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { controller.setConflictPolicy(policy) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = dialog.conflictPolicy == policy, onClick = { controller.setConflictPolicy(policy) })
                        Text(text = policy.labelRu(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                CheckboxRow(
                    checked = dialog.copyComments,
                    onCheckedChange = controller::setCopyComments,
                    label = "Копировать комментарии (по умолчанию выключено)",
                )
                if (dialog.isSourceSameAsTarget) {
                    Text(
                        text = "Источник и назначение совпадают — выберите другой месяц.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                text = if (dialog.isRunning) "Копируем…" else "Дублировать",
                onClick = controller::runDuplicate,
                enabled = !dialog.isRunning && !dialog.isSourceSameAsTarget,
            )
        },
        dismissButton = { QuietButton(text = "Отмена", onClick = controller::closeDuplicateDialog) },
    )
}

private fun ConflictPolicy.labelRu(): String = when (this) {
    ConflictPolicy.SKIP -> "Пропустить заполненные категории"
    ConflictPolicy.ADD -> "Прибавить к существующим суммам"
    ConflictPolicy.OVERWRITE -> "Заменить существующие суммы"
}

/**
 * Веса колонок таблицы.
 *
 * Заголовок и строки обязаны использовать одни и те же веса: иначе подпись «Сумма»
 * съезжает относительно чисел, а таблицу тогда нельзя читать бегло.
 */
private const val W_NAME = 3f
private const val W_SHARE = 0.9f
private const val W_DELTA = 2.6f
private const val W_AMOUNT = 1.6f

/** Узкая колонка индикатора комментария: символ всегда один, вес ей не нужен. */
private val COMMENT_WIDTH = 20.dp

/** Ширина панели ячейки: хватает для суммы и истории, но таблица не теряется. */
private val PANEL_WIDTH = 360.dp
