package app.finance.core.importing

import app.finance.core.model.LedgerSection

/** Поле, которое мастер сопоставляет с колонкой CSV (шаг 2, docs/IMPORT-NOTION.md §4). */
enum class ImportField(val titleRu: String) {
    AMOUNT("Сумма"),
    CATEGORY("Категория"),
    MONTH("Месяц"),
    YEAR("Год"),
    DATE("Дата"),
    NAME("Комментарий"),
}

/** Номер колонки CSV для каждого поля; отсутствие ключа — поле не используется. */
data class ColumnMapping(val columns: Map<ImportField, Int>) {
    operator fun get(field: ImportField): Int? = columns[field]

    fun with(field: ImportField, column: Int?): ColumnMapping =
        ColumnMapping(if (column == null) columns - field else columns + (field to column))
}

/** Куда идёт таблица: расходы, доходы или никуда (пользователь её выключил). */
enum class TableTarget { EXPENSE, INCOME, SKIP;

    fun toLedgerSection(): LedgerSection? = when (this) {
        EXPENSE -> LedgerSection.EXPENSE
        INCOME -> LedgerSection.INCOME
        SKIP -> null
    }
}

/**
 * Распознавание структуры баз владельца (docs/IMPORT-NOTION.md §1.1, §1.2).
 *
 * Имена колонок сравниваются без учёта регистра и пробелов; кроме имён из Notion
 * владельца поддержаны очевидные синонимы на случай переименования колонки.
 */
object NotionTableDetector {

    private val SYNONYMS: Map<ImportField, List<String>> = mapOf(
        ImportField.AMOUNT to listOf("number", "сумма", "amount", "sum", "₽", "руб"),
        ImportField.CATEGORY to listOf("категория", "категории", "category", "categories", "tags", "теги"),
        ImportField.MONTH to listOf("месяц", "month"),
        ImportField.YEAR to listOf("год", "year"),
        ImportField.DATE to listOf("date", "дата"),
        ImportField.NAME to listOf("name", "название", "имя", "комментарий", "comment", "описание"),
    )

    fun detectMapping(header: List<String>): ColumnMapping {
        val normalized = header.map { it.trim().lowercase() }
        val result = mutableMapOf<ImportField, Int>()
        for ((field, names) in SYNONYMS) {
            val index = normalized.indexOfFirst { it in names }
            if (index >= 0 && index !in result.values) result[field] = index
        }
        return ColumnMapping(result)
    }

    /** Есть категория — это расходы; только имя/сумма/дата — доходы (база «Доход»). */
    fun detectTarget(header: List<String>, mapping: ColumnMapping = detectMapping(header)): TableTarget = when {
        mapping[ImportField.AMOUNT] == null -> TableTarget.SKIP
        mapping[ImportField.CATEGORY] != null -> TableTarget.EXPENSE
        mapping[ImportField.DATE] != null || mapping[ImportField.MONTH] != null -> TableTarget.INCOME
        else -> TableTarget.SKIP
    }

    /**
     * Год по умолчанию для строк без года (§3.1): из имени файла, затем самый частый
     * год в колонке «Год», затем в датах, иначе [fallback] (текущий год).
     */
    fun defaultYear(table: SourceTable, mapping: ColumnMapping, fallback: Int): Int {
        NotionValues.yearIn(table.fileName)?.let { return it }
        val fromYears = table.rows.mapNotNull { NotionValues.year(table.cell(it, mapping[ImportField.YEAR])) }
        mostCommon(fromYears)?.let { return it }
        val fromDates = table.rows.mapNotNull { NotionValues.date(table.cell(it, mapping[ImportField.DATE]))?.year }
        return mostCommon(fromDates) ?: fallback
    }

    private fun mostCommon(values: List<Int>): Int? =
        values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}
