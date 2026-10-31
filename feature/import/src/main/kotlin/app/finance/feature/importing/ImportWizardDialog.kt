package app.finance.feature.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.finance.core.importing.ImportField
import app.finance.core.importing.TableTarget
import app.finance.core.model.ImportBatchStatus
import app.finance.core.model.LedgerSection
import app.finance.core.ui.CategoryDot
import app.finance.core.ui.FinancePalette
import app.finance.core.ui.FinancePicker
import app.finance.core.ui.FinanceRadius
import app.finance.core.ui.FinanceSpace
import app.finance.core.ui.FinanceTextField
import app.finance.core.ui.HeaderCell
import app.finance.core.ui.MoneyCell
import app.finance.core.ui.NoticeBar
import app.finance.core.ui.PrimaryButton
import app.finance.core.ui.QuietButton
import app.finance.core.ui.SecondaryButton
import app.finance.core.ui.TableHeaderRow
import app.finance.core.ui.TableRow
import app.finance.core.ui.TextCell
import app.finance.core.ui.formatRu
import app.finance.core.ui.formatTimestamp
import app.finance.core.ui.titleRu
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import java.time.ZoneId

/**
 * Мастер «Импорт из Notion» (docs/IMPORT-NOTION.md §4): четыре шага и отчёт.
 *
 * Окно модальное; закрыть его во время записи нельзя — транзакция должна завершиться.
 */
@Composable
fun ImportWizardDialog(controller: ImportController, zone: ZoneId = ZoneId.systemDefault()) {
    val state by controller.state.collectAsState()
    if (!state.isOpen) return

    Dialog(
        onDismissRequest = controller::close,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.width(960.dp).height(680.dp),
            shape = FinanceRadius.cardShape,
            color = FinancePalette.Background,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(FinanceSpace.Lg)) {
                WizardHeader(state.step)
                Spacer(Modifier.height(FinanceSpace.Md))
                state.error?.let {
                    NoticeBar(text = it, onDismiss = controller::dismissError, error = true)
                    Spacer(Modifier.height(FinanceSpace.Sm))
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        when (state.step) {
                            ImportStep.FILES -> FilesStep(state, controller, zone)
                            ImportStep.COLUMNS -> ColumnsStep(state, controller)
                            ImportStep.CATEGORIES -> CategoriesStep(state, controller)
                            ImportStep.PREVIEW -> PreviewStep(state)
                            ImportStep.DONE -> DoneStep(state, controller)
                        }
                    }
                    if (state.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(40.dp))
                    }
                }
                HorizontalDivider(color = FinancePalette.Divider)
                Spacer(Modifier.height(FinanceSpace.Md))
                WizardFooter(state, controller)
            }
        }
    }
}

@Composable
private fun WizardHeader(step: ImportStep) {
    Column {
        Text("Импорт из Notion", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(FinanceSpace.Xs))
        Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Md)) {
            ImportStep.entries.forEachIndexed { index, item ->
                val current = item == step
                Text(
                    text = "${index + 1}. ${item.titleRu}",
                    style = if (current) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                    color = if (current) FinancePalette.TextPrimary else FinancePalette.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun WizardFooter(state: ImportUiState, controller: ImportController) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        QuietButton(text = if (state.step == ImportStep.DONE) "Закрыть" else "Отмена", onClick = controller::close, enabled = !state.isBusy)
        Spacer(Modifier.weight(1f))
        when (state.step) {
            ImportStep.FILES -> Unit
            ImportStep.DONE -> SecondaryButton(text = "Импортировать ещё", onClick = controller::restart, enabled = !state.isBusy)
            else -> SecondaryButton(text = "Назад", onClick = controller::back, enabled = !state.isBusy)
        }
        if (state.step != ImportStep.DONE) {
            Spacer(Modifier.width(FinanceSpace.Sm))
            PrimaryButton(
                text = if (state.step == ImportStep.PREVIEW) "Импортировать" else "Далее",
                onClick = controller::next,
                enabled = state.canGoNext,
            )
        }
    }
}

// ── Шаг 1 ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.FilesStep(state: ImportUiState, controller: ImportController, zone: ZoneId) {
    Hint(
        "Выберите ZIP-архивы из Notion: «⋯ → Экспорт → Markdown & CSV», подстраницы выключены. " +
            "Можно сразу несколько — например, «Траты 2025», «Траты 2026» и «Доход».",
    )
    Spacer(Modifier.height(FinanceSpace.Sm))
    PrimaryButton(text = "Выбрать файлы…", onClick = { controller.addFiles(chooseFiles()) }, enabled = !state.isBusy)
    Spacer(Modifier.height(FinanceSpace.Md))

    state.fileErrors.forEach { Text("✖ $it", color = FinancePalette.Error, style = MaterialTheme.typography.bodySmall) }

    if (state.tables.isNotEmpty()) {
        TableHeaderRow(background = FinancePalette.Background) {
            HeaderCell("Файл и таблица", weight = 3f)
            HeaderCell("Строк", weight = 0.7f, alignEnd = true)
            HeaderCell("Куда", weight = 1.6f)
            HeaderCell("Год по умолчанию", weight = 1.2f)
            HeaderCell("", weight = 0.8f)
        }
        state.tables.forEach { table ->
            TableRow {
                Column(modifier = Modifier.weight(3f).padding(horizontal = FinanceSpace.Xs)) {
                    Text(table.table.tableName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(table.table.fileName, style = MaterialTheme.typography.bodySmall, color = FinancePalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    table.importedBeforeAtUtc?.let {
                        Text("⚠ этот файл уже импортировался ${formatTimestamp(it, zone)} — повторные строки будут пропущены", style = MaterialTheme.typography.bodySmall, color = FinancePalette.TextSecondary)
                    }
                }
                TextCell(table.table.rows.size.toString(), weight = 0.7f, alignEnd = true)
                Box(modifier = Modifier.weight(1.6f).padding(horizontal = FinanceSpace.Xs)) {
                    FinancePicker(
                        label = null,
                        current = table.target.titleRu(),
                        options = TableTarget.entries.map { it.name to it.titleRu() },
                        onPick = { controller.setTarget(table.id, TableTarget.valueOf(it)) },
                    )
                }
                Box(modifier = Modifier.weight(1.2f).padding(horizontal = FinanceSpace.Xs)) {
                    var text by remember(table.id) { mutableStateOf(table.defaultYear.toString()) }
                    FinanceTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            controller.setDefaultYear(table.id, it)
                        },
                        enabled = table.target == TableTarget.EXPENSE || table.target == TableTarget.INCOME,
                    )
                }
                Box(modifier = Modifier.weight(0.8f)) {
                    QuietButton(text = "Убрать", onClick = { controller.removeTable(table.id) })
                }
            }
        }
        Spacer(Modifier.height(FinanceSpace.Sm))
        Hint("«Год по умолчанию» подставляется строкам, где в Notion нет ни года, ни даты.")
    }

    if (state.history.isNotEmpty()) {
        Spacer(Modifier.height(FinanceSpace.Lg))
        Text("История импорта", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(FinanceSpace.Xs))
        state.undoReport?.let { UndoNotice(it) }
        state.history.forEach { batch ->
            TableRow {
                TextCell(formatTimestamp(batch.createdAtUtc, zone), weight = 1.4f)
                TextCell(batch.files.joinToString(", ") { it.name }, weight = 3f)
                TextCell("${batch.rowsImported} строк", weight = 1f, alignEnd = true)
                Box(modifier = Modifier.weight(1.4f).padding(horizontal = FinanceSpace.Xs)) {
                    if (batch.status == ImportBatchStatus.APPLIED) {
                        SecondaryButton(text = "Отменить импорт", onClick = { controller.undo(batch.id) }, enabled = !state.isBusy)
                    } else {
                        Text("отменён", style = MaterialTheme.typography.bodySmall, color = FinancePalette.TextSecondary)
                    }
                }
            }
        }
    }
}

// ── Шаг 2 ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnsStep(state: ImportUiState, controller: ImportController) {
    Hint("Колонки сопоставлены по названиям. Поправьте, если в Notion их переименовали.")
    state.activeTables.forEach { table ->
        Spacer(Modifier.height(FinanceSpace.Md))
        Text("${table.table.tableName} → ${table.target.titleRu()}", style = MaterialTheme.typography.titleMedium)
        val fields = when (table.target) {
            TableTarget.EXPENSE -> ImportField.entries
            else -> ImportField.entries - ImportField.CATEGORY
        }
        val options = listOf(NONE to "— не использовать") + table.table.header.mapIndexed { i, name -> i.toString() to name.ifEmpty { "колонка ${i + 1}" } }
        Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
            fields.forEach { field ->
                val column = table.mapping[field]
                FinancePicker(
                    label = field.titleRu,
                    current = column?.let { table.table.header.getOrNull(it) } ?: "—",
                    options = options,
                    onPick = { controller.setColumn(table.id, field, it.toIntOrNull()) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (table.missingFields.isNotEmpty()) {
            Text(
                "✖ Нужно выбрать: ${table.missingFields.joinToString { if (it == ImportField.MONTH) "«Месяц» или «Дата»" else "«${it.titleRu}»" }}",
                color = FinancePalette.Error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(FinanceSpace.Xs))
        TableHeaderRow(background = FinancePalette.Background) { table.table.header.forEach { HeaderCell(it) } }
        table.table.rows.take(PREVIEW_ROWS).forEach { row ->
            TableRow { table.table.header.indices.forEach { TextCell(row.getOrElse(it) { "" }) } }
        }
    }
}

// ── Шаг 3 ─────────────────────────────────────────────────────────────────────────

@Composable
private fun CategoriesStep(state: ImportUiState, controller: ImportController) {
    if (state.hasExpenseTables) {
        Hint("Категории расходов из Notion. По умолчанию создаётся категория с тем же именем; можно направить в существующую или пропустить.")
        Spacer(Modifier.height(FinanceSpace.Sm))
        TableHeaderRow(background = FinancePalette.Background) {
            HeaderCell("Категория Notion", weight = 2f)
            HeaderCell("Строк", weight = 0.6f, alignEnd = true)
            HeaderCell("Сумма", weight = 1.2f, alignEnd = true)
            HeaderCell("В приложении", weight = 2.4f)
        }
        val existing = state.expenseCategories
        val options = listOf(CategoryChoice.CHOICE_NEW to "Создать новую", CategoryChoice.CHOICE_SKIP to "Пропустить") +
            existing.map { it.id to it.name } +
            // Можно направить в категорию, которую создаст этот же импорт («мама» → «маме»).
            state.categoryChoices.filter { c -> existing.none { it.name.equals(c.sourceName, ignoreCase = true) } }
                .map { "$NEW_PREFIX${it.sourceName}" to "Новая «${it.sourceName}»" }
        state.categoryChoices.forEach { choice ->
            TableRow {
                Column(modifier = Modifier.weight(2f).padding(horizontal = FinanceSpace.Xs)) {
                    Text(choice.sourceName, style = MaterialTheme.typography.bodyMedium)
                    if (choice.similarTo.isNotEmpty()) {
                        Text("🔗 похоже на: ${choice.similarTo.joinToString()}", style = MaterialTheme.typography.bodySmall, color = FinancePalette.TextSecondary)
                    }
                }
                TextCell(choice.rows.toString(), weight = 0.6f, alignEnd = true)
                MoneyCell(choice.total, weight = 1.2f)
                Box(modifier = Modifier.weight(2.4f).padding(horizontal = FinanceSpace.Xs)) {
                    val current = when {
                        choice.choice == CategoryChoice.CHOICE_NEW -> "Создать «${choice.sourceName}»"
                        choice.choice == CategoryChoice.CHOICE_SKIP -> "Пропустить"
                        choice.choice.startsWith(NEW_PREFIX) -> "Новая «${choice.choice.removePrefix(NEW_PREFIX)}»"
                        else -> existing.firstOrNull { it.id == choice.choice }?.name ?: "—"
                    }
                    FinancePicker(label = null, current = current, options = options, onPick = { controller.setCategoryChoice(choice.sourceName, it) })
                }
            }
        }
    }
    if (state.hasIncomeTables) {
        Spacer(Modifier.height(FinanceSpace.Lg))
        Text("Доходы", style = MaterialTheme.typography.titleMedium)
        Hint("В базе «Доход» нет категорий — все суммы пойдут в одну категорию доходов.")
        val options = state.incomeCategories.map { it.id to it.name } + (NONE to "Создать «${ImportController.DEFAULT_INCOME_CATEGORY}»")
        FinancePicker(
            label = "Категория доходов",
            current = state.incomeCategories.firstOrNull { it.id == state.incomeCategoryId }?.name
                ?: "Создать «${ImportController.DEFAULT_INCOME_CATEGORY}»",
            options = options,
            onPick = { controller.setIncomeCategory(it.takeIf { id -> id != NONE }) },
            modifier = Modifier.width(320.dp),
        )
    }
}

// ── Шаг 4 ─────────────────────────────────────────────────────────────────────────

@Composable
private fun PreviewStep(state: ImportUiState) {
    val preview = state.preview ?: return
    var tab by remember { mutableStateOf(0) }
    val totals = listOfNotNull(
        "расходы ${preview.expenseTotal.formatRu()}".takeIf { state.hasExpenseTables },
        "доходы ${preview.incomeTotal.formatRu()}".takeIf { state.hasIncomeTables },
    )
    Text(
        "Будет импортировано строк: ${preview.rowsToImport} из ${preview.rowsTotal}" +
            (if (totals.isEmpty()) "." else ": ${totals.joinToString(", ")}."),
        style = MaterialTheme.typography.bodyLarge,
    )
    val skipped = listOfNotNull(
        preview.rowsAlreadyImported.takeIf { it > 0 }?.let { "уже импортированы: $it" },
        preview.rowsSkippedCategory.takeIf { it > 0 }?.let { "пропущенные категории: $it" },
        preview.rowsEmpty.takeIf { it > 0 }?.let { "пустые: $it" },
        preview.rowsZero.takeIf { it > 0 }?.let { "с нулевой суммой: $it" },
        preview.errors.size.takeIf { it > 0 }?.let { "с ошибками: $it" },
    )
    if (skipped.isNotEmpty()) Hint("Не войдут — ${skipped.joinToString(", ")}.")
    if (preview.categoriesToCreate.isNotEmpty()) {
        Hint("Будут созданы категории (${preview.categoriesToCreate.size}): ${preview.categoriesToCreate.joinToString()}.")
    }
    Hint("Перед записью приложение сохранит копию базы в папку backups. Импорт можно отменить одной кнопкой.")
    Hint("Если строку изменить в Notion после импорта, при повторном импорте она будет считаться новой.")
    Spacer(Modifier.height(FinanceSpace.Sm))

    Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
        listOf("По месяцам", "⚠ Предупреждения (${preview.warnings.size})", "✖ Ошибки (${preview.errors.size})").forEachIndexed { i, title ->
            if (i == tab) PrimaryButton(text = title, onClick = {}) else SecondaryButton(text = title, onClick = { tab = i })
        }
    }
    Spacer(Modifier.height(FinanceSpace.Sm))
    when (tab) {
        0 -> {
            TableHeaderRow(background = FinancePalette.Background) {
                HeaderCell("Месяц", weight = 1.5f)
                HeaderCell("Строк", weight = 0.7f, alignEnd = true)
                HeaderCell("Расходы", weight = 1.2f, alignEnd = true)
                HeaderCell("Доходы", weight = 1.2f, alignEnd = true)
            }
            preview.months.forEachIndexed { i, month ->
                TableRow(alternate = i % 2 == 1) {
                    TextCell(month.period.titleRu(), weight = 1.5f)
                    TextCell(month.rows.toString(), weight = 0.7f, alignEnd = true)
                    MoneyCell(month.expense.takeIf { !it.isZero() }, weight = 1.2f)
                    MoneyCell(month.income.takeIf { !it.isZero() }, weight = 1.2f)
                }
            }
        }
        1 -> IssueList(preview.warnings, empty = "Предупреждений нет")
        else -> IssueList(preview.errors, empty = "Ошибок нет")
    }
}

@Composable
private fun IssueList(issues: List<PreviewIssue>, empty: String) {
    if (issues.isEmpty()) {
        Hint(empty)
        return
    }
    TableHeaderRow(background = FinancePalette.Background) {
        HeaderCell("Таблица, строка", weight = 1.3f)
        HeaderCell("Что не так", weight = 3f)
        HeaderCell("Строка Notion", weight = 3f)
    }
    issues.forEach { issue ->
        TableRow {
            TextCell("${issue.tableName}, №${issue.rowNumber}", weight = 1.3f)
            Text(issue.text, modifier = Modifier.weight(3f).padding(horizontal = FinanceSpace.Xs), style = MaterialTheme.typography.bodySmall)
            Text(issue.row, modifier = Modifier.weight(3f).padding(horizontal = FinanceSpace.Xs), style = MaterialTheme.typography.bodySmall, color = FinancePalette.TextSecondary)
        }
    }
}

// ── Готово ────────────────────────────────────────────────────────────────────────

@Composable
private fun DoneStep(state: ImportUiState, controller: ImportController) {
    val report = state.report ?: return
    val batch = report.batch
    if (batch == null) {
        Text("Новых строк нет: всё из этих файлов уже импортировано раньше.", style = MaterialTheme.typography.bodyLarge)
        return
    }
    Text("Импорт завершён", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(FinanceSpace.Sm))
    Text(
        "Импортировано строк: ${report.rowsImported} в ${report.cellsCreated + report.cellsUpdated} ячеек " +
            "(новых ${report.cellsCreated}, дополнено ${report.cellsUpdated}).",
        style = MaterialTheme.typography.bodyLarge,
    )
    if (report.categoriesCreated.isNotEmpty()) {
        Spacer(Modifier.height(FinanceSpace.Xs))
        Text("Создано категорий: ${report.categoriesCreated.size}", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm), verticalAlignment = Alignment.CenterVertically) {
            report.categoriesCreated.take(MAX_CATEGORY_DOTS).forEach {
                CategoryDot(it.color)
                Text(it.name, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (report.rowsAlreadyImported > 0) Hint("Пропущено как уже импортированные: ${report.rowsAlreadyImported}.")
    if (report.rowsZeroSum > 0) Hint("Строк с нулевой суммой в ячейке: ${report.rowsZeroSum} — такие ячейки не созданы.")
    report.firstPeriod.forEach { (section, period) ->
        Hint("${if (section == LedgerSection.EXPENSE) "Расходы" else "Доходы"}: история начинается с ${period.titleRu()} — откройте раздел и листайте месяцы назад.")
    }
    report.backupPath?.let { Hint("Копия базы до импорта: $it") }
    Spacer(Modifier.height(FinanceSpace.Md))
    val undone = state.history.firstOrNull { it.id == batch.id }?.status == ImportBatchStatus.UNDONE
    if (undone) {
        state.undoReport?.let { UndoNotice(it) }
    } else {
        SecondaryButton(text = "Отменить импорт", onClick = { controller.undo(batch.id) }, enabled = !state.isBusy)
    }
}

@Composable
private fun UndoNotice(report: app.finance.core.domain.UndoImportReport) {
    Text(
        "Импорт отменён: возвращено ячеек ${report.cellsReverted}, удалено ${report.cellsRemoved}" +
            (if (report.cellsSkipped > 0) ", пропущено (удалены вручную) ${report.cellsSkipped}" else "") +
            (if (report.categoriesArchived > 0) ", в архив ушло категорий: ${report.categoriesArchived}" else "") + ".",
        style = MaterialTheme.typography.bodyMedium,
        color = FinancePalette.Success,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = FinancePalette.TextSecondary)
}

private fun TableTarget.titleRu(): String = when (this) {
    TableTarget.EXPENSE -> "Расходы"
    TableTarget.INCOME -> "Доходы"
    TableTarget.SKIP -> "Не импортировать"
}

/** Системный диалог выбора файлов: на Windows и macOS он нативный. */
private fun chooseFiles(): List<Path> {
    val dialog = FileDialog(null as Frame?, "Экспорт Notion (.zip или .csv)", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.file = "*.zip;*.csv"
    dialog.setFilenameFilter { _, name -> name.endsWith(".zip", true) || name.endsWith(".csv", true) }
    dialog.isVisible = true
    return dialog.files.orEmpty().map(File::toPath)
}

private const val NONE = "-"
private const val PREVIEW_ROWS = 5
private const val MAX_CATEGORY_DOTS = 12

/** Префикс выбора «в категорию, которую создаст этот импорт» на шаге 3. */
internal const val NEW_PREFIX = "new:"
