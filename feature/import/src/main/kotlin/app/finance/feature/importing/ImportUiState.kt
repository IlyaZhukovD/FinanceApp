package app.finance.feature.importing

import app.finance.core.domain.ImportReport
import app.finance.core.domain.UndoImportReport
import app.finance.core.importing.ColumnMapping
import app.finance.core.importing.ImportField
import app.finance.core.importing.ParsedRow
import app.finance.core.importing.SourceTable
import app.finance.core.importing.TableTarget
import app.finance.core.model.Category
import app.finance.core.model.ImportBatch
import app.finance.core.model.Money
import app.finance.core.model.Period

/** Шаги мастера (docs/IMPORT-NOTION.md §4). */
enum class ImportStep(val titleRu: String) {
    FILES("Файлы"),
    COLUMNS("Колонки"),
    CATEGORIES("Категории"),
    PREVIEW("Проверка"),
    DONE("Готово"),
}

/** Таблица из выбранного файла и настройки её разбора. */
data class ImportTableState(
    val id: String,
    val table: SourceTable,
    val target: TableTarget,
    val mapping: ColumnMapping,
    val defaultYear: Int,
    /** Когда этот же файл (по sha256) уже импортировался действующим пакетом. */
    val importedBeforeAtUtc: Long? = null,
) {
    val isActive: Boolean get() = target != TableTarget.SKIP

    /** Для разбора нужны сумма и месяц (из колонки «Месяц» или из даты). */
    val missingFields: List<ImportField>
        get() = buildList {
            if (mapping[ImportField.AMOUNT] == null) add(ImportField.AMOUNT)
            if (mapping[ImportField.MONTH] == null && mapping[ImportField.DATE] == null) add(ImportField.MONTH)
        }
}

/** Выбор для категории Notion на шаге 3: создать, сопоставить с существующей, пропустить. */
data class CategoryChoice(
    val sourceName: String,
    val rows: Int,
    val total: Money,
    /** [CHOICE_NEW], [CHOICE_SKIP] или id существующей категории расходов. */
    val choice: String,
    /** Похожие имена среди категорий Notion — подсказка «похоже на…». */
    val similarTo: List<String>,
) {
    companion object {
        const val CHOICE_NEW = "__new__"
        const val CHOICE_SKIP = "__skip__"
    }
}

data class PreviewMonth(val period: Period, val expense: Money, val income: Money, val rows: Int)

data class PreviewIssue(val tableName: String, val rowNumber: Int, val text: String, val row: String)

/** Шаг 4: что именно будет записано и что нет. */
data class ImportPreview(
    val months: List<PreviewMonth>,
    val warnings: List<PreviewIssue>,
    val errors: List<PreviewIssue>,
    val rowsTotal: Int,
    val rowsToImport: Int,
    val rowsEmpty: Int,
    val rowsZero: Int,
    val rowsAlreadyImported: Int,
    val rowsSkippedCategory: Int,
    val expenseTotal: Money,
    val incomeTotal: Money,
    val categoriesToCreate: List<String>,
)

data class ImportUiState(
    val isOpen: Boolean = false,
    val step: ImportStep = ImportStep.FILES,
    val tables: List<ImportTableState> = emptyList(),
    val fileErrors: List<String> = emptyList(),
    val categoryChoices: List<CategoryChoice> = emptyList(),
    val expenseCategories: List<Category> = emptyList(),
    val incomeCategories: List<Category> = emptyList(),
    /** Категория доходов для всей базы «Доход» (§3.6); null — создать «Зарплата». */
    val incomeCategoryId: String? = null,
    val preview: ImportPreview? = null,
    val report: ImportReport? = null,
    val undoReport: UndoImportReport? = null,
    val history: List<ImportBatch> = emptyList(),
    val isBusy: Boolean = false,
    val error: String? = null,
) {
    val activeTables: List<ImportTableState> get() = tables.filter { it.isActive }

    val hasExpenseTables: Boolean get() = activeTables.any { it.target == TableTarget.EXPENSE }

    val hasIncomeTables: Boolean get() = activeTables.any { it.target == TableTarget.INCOME }

    val canGoNext: Boolean
        get() = !isBusy && when (step) {
            ImportStep.FILES -> activeTables.isNotEmpty()
            ImportStep.COLUMNS -> activeTables.all { it.missingFields.isEmpty() }
            ImportStep.CATEGORIES -> true
            ImportStep.PREVIEW -> (preview?.rowsToImport ?: 0) > 0
            ImportStep.DONE -> false
        }
}

/** Разобранные строки таблицы — промежуточный результат для шагов 3 и 4. */
internal data class ParsedTable(val state: ImportTableState, val rows: List<ParsedRow>)
