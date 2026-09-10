package app.finance.feature.ledger

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import app.finance.core.domain.ConflictPolicy
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.ui.AmountField
import app.finance.core.ui.CategoryDot
import app.finance.core.ui.CheckboxRow
import app.finance.core.ui.ConfirmDialog
import app.finance.core.ui.EmptyState
import app.finance.core.ui.ExpenseColor
import app.finance.core.ui.IncomeColor
import app.finance.core.ui.MoneyText
import app.finance.core.ui.MutedColor
import app.finance.core.ui.NoticeBar
import app.finance.core.ui.PeriodNavigator
import app.finance.core.ui.formatRu
import app.finance.core.ui.formatSignedRu
import app.finance.core.ui.titleRu

/**
 * Экран расходов и доходов (SPEC.md F1, F2, F5).
 *
 * Здесь только разметка и проброс событий в [LedgerController]: ни чтения БД,
 * ни расчётов. Один и тот же composable обслуживает обе секции (F2.2).
 */
@Composable
fun LedgerScreen(controller: LedgerController, modifier: Modifier = Modifier) {
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

        LedgerHeader(state = state, controller = controller)
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
}

@Composable
private fun LedgerHeader(state: LedgerUiState, controller: LedgerController) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = state.section.titleRu(), style = MaterialTheme.typography.headlineSmall)
            PeriodNavigator(
                period = state.period,
                onPrevious = controller::previousMonth,
                onNext = controller::nextMonth,
                onToday = controller::today,
            )
        }

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MoneyText(
                money = state.total,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Категорий: ${state.rows.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = deltaLabel(state.totalDelta, state.totalDeltaPercent),
                style = MaterialTheme.typography.bodySmall,
                color = deltaColor(state.totalDelta, state.section),
            )
        }

        Button(onClick = controller::openDuplicateDialog) {
            Text("Дублировать прошлый месяц")
        }
    }
}

/** Δ к прошлому месяцу: «+1 200,00 ₽ (+12%) к прошлому месяцу» или «—», если данных нет. */
private fun deltaLabel(delta: Money?, percent: Int?): String {
    if (delta == null || delta.isZero()) return "К прошлому месяцу: —"
    val percentLabel = percent?.let { " (${if (it > 0) "+" else ""}$it%)" }.orEmpty()
    return "К прошлому месяцу: ${delta.formatSignedRu()}$percentLabel"
}

/**
 * Цвет дельты зависит от секции: для расходов рост — плохо, для доходов — хорошо.
 * Нулевая или неопределённая дельта показывается нейтральным цветом.
 */
private fun deltaColor(delta: Money?, section: LedgerSection): Color {
    if (delta == null || delta.isZero()) return MutedColor
    val grew = delta.isPositive()
    val isGood = if (section == LedgerSection.INCOME) grew else !grew
    return if (isGood) IncomeColor else ExpenseColor
}

/** Форма ввода суммы: batch-ввод, не закрывается после сохранения (F1.4, F1.6). */
@Composable
private fun AmountInputForm(state: LedgerUiState, controller: LedgerController) {
    val form = state.form
    var categoryPickerExpanded by remember { mutableStateOf(false) }
    val selectedCategoryName = state.categories.firstOrNull { it.id == form.categoryId }?.name ?: "Категория"

    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                OutlinedButton(
                    onClick = { categoryPickerExpanded = true },
                    enabled = state.categories.isNotEmpty(),
                ) {
                    Text(selectedCategoryName)
                }
                DropdownMenu(
                    expanded = categoryPickerExpanded,
                    onDismissRequest = { categoryPickerExpanded = false },
                ) {
                    state.categories.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category.name) },
                            onClick = {
                                controller.selectCategory(category.id)
                                categoryPickerExpanded = false
                            },
                        )
                    }
                }
            }

            AmountField(
                value = form.amountText,
                onValueChange = controller::setAmountInput,
                label = "Сумма",
                modifier = Modifier.width(180.dp),
                isError = form.error != null,
                supportingText = form.error,
            )

            OutlinedTextField(
                value = form.commentText,
                onValueChange = controller::setCommentInput,
                modifier = Modifier.weight(1f),
                label = { Text("Комментарий (необязательно)") },
                singleLine = true,
            )

            Button(
                onClick = controller::submitAmount,
                enabled = !form.isSaving && state.categories.isNotEmpty(),
            ) {
                Text("Добавить")
            }
        }
    }
}

@Composable
private fun LedgerTable(state: LedgerUiState, controller: LedgerController, modifier: Modifier = Modifier) {
    when {
        state.isLoading && !state.hasRows -> Text(
            text = "Загрузка…",
            modifier = modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
        )

        !state.hasRows -> EmptyState(
            title = "За ${state.period.titleRu()} записей нет",
            hint = "Введите сумму в форме выше: повторный ввод в ту же категорию прибавится к существующей.",
            modifier = modifier,
        )

        else -> Column(modifier = modifier) {
            TableHeaderRow()
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(state.rows, key = { it.cellId }) { row ->
                    LedgerRowView(
                        row = row,
                        section = state.section,
                        selected = state.panel?.cellId == row.cellId,
                        onClick = { controller.selectCell(row.cellId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TableHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(12.dp))
        Text("Категория", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        Text("Доля", modifier = Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
        Text("Δ к прошлому", modifier = Modifier.width(130.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
        Text("Сумма", modifier = Modifier.width(140.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
        Spacer(modifier = Modifier.width(20.dp))
    }
}

@Composable
private fun LedgerRowView(row: LedgerRow, section: LedgerSection, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(background)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryDot(hexColor = row.categoryColor)
        Text(
            text = row.categoryName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = row.sharePercent?.let { "$it%" } ?: "—",
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
        Text(
            text = row.delta?.formatSignedRu() ?: "—",
            modifier = Modifier.width(130.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = deltaColor(row.delta, section),
            textAlign = TextAlign.End,
        )
        MoneyText(
            money = row.amount,
            modifier = Modifier.width(140.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        // Индикатор комментария (F1.2): символ вместо иконки, чтобы не тянуть набор иконок.
        Text(
            text = if (row.hasComment) "\u270E" else "",
            modifier = Modifier.width(20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MutedColor,
            textAlign = TextAlign.End,
        )
    }
}

/** Панель ячейки: правка, история, сторно, удаление, восстановление (F1.3). */
@Composable
private fun CellPanel(panel: CellPanelState, controller: LedgerController) {
    Surface(
        modifier = Modifier.width(360.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryDot(hexColor = panel.categoryColor)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = panel.categoryName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = panel.period.titleRu(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = controller::closePanel) { Text("Закрыть") }
            }

            if (panel.isDeleted) {
                Text(
                    text = "Ячейка удалена и не участвует в итоге месяца.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = controller::restoreSelected, modifier = Modifier.fillMaxWidth()) {
                    Text("Восстановить")
                }
            } else {
                AmountField(
                    value = panel.amountText,
                    onValueChange = controller::setPanelAmount,
                    label = "Сумма",
                    modifier = Modifier.fillMaxWidth(),
                    isError = panel.error != null,
                    supportingText = panel.error,
                )
                OutlinedTextField(
                    value = panel.commentText,
                    onValueChange = controller::setPanelComment,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Комментарий") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = controller::savePanel, enabled = !panel.isSaving) { Text("Сохранить") }
                    OutlinedButton(onClick = controller::requestReverse) { Text("Сторнировать") }
                }
                OutlinedButton(onClick = controller::requestDelete, modifier = Modifier.fillMaxWidth()) {
                    Text("Удалить ячейку")
                }
            }

            Text(text = "История изменений", style = MaterialTheme.typography.titleSmall)
            if (panel.revisions.isEmpty()) {
                Text(
                    text = "Записей пока нет",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(panel.revisions, key = { it.id }) { revision ->
                        RevisionView(revision)
                    }
                }
            }
        }
    }
}

@Composable
private fun RevisionView(revision: RevisionRow) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = revision.operationLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            revision.deltaLabel?.let { delta ->
                Text(text = delta, style = MaterialTheme.typography.bodySmall, color = MutedColor)
            }
        }
        Text(text = revision.amountLabel, style = MaterialTheme.typography.bodyMedium)
        revision.commentLabel?.let { comment ->
            Text(
                text = comment,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = revision.createdAtLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                    OutlinedButton(onClick = { controller.shiftDuplicateSource(-1) }) { Text("‹") }
                    Text(text = dialog.source.titleRu(), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { controller.shiftDuplicateSource(1) }) { Text("›") }
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
            Button(
                onClick = controller::runDuplicate,
                enabled = !dialog.isRunning && !dialog.isSourceSameAsTarget,
            ) {
                Text(if (dialog.isRunning) "Копируем…" else "Дублировать")
            }
        },
        dismissButton = { TextButton(onClick = controller::closeDuplicateDialog) { Text("Отмена") } },
    )
}

private fun ConflictPolicy.labelRu(): String = when (this) {
    ConflictPolicy.SKIP -> "Пропустить заполненные категории"
    ConflictPolicy.ADD -> "Прибавить к существующим суммам"
    ConflictPolicy.OVERWRITE -> "Заменить существующие суммы"
}
