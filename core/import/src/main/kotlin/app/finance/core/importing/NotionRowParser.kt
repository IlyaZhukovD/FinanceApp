package app.finance.core.importing

import app.finance.core.model.Money
import app.finance.core.model.Period
import java.security.MessageDigest

/** Итог разбора строки. */
enum class RowStatus {
    /** Строка будет импортирована. */
    OK,

    /** Будет импортирована, но правило применено с допущением — показать пользователю (⚠). */
    WARNING,

    /** Не импортируется: данных не хватает или они неверны (✖). */
    ERROR,

    /** Полностью пустая строка — молча пропускаем, считаем в отчёте. */
    EMPTY,

    /** Сумма ноль — в приложении ячейка с нулём не создаётся (F1.4). */
    ZERO,
}

/**
 * Строка таблицы после применения правил docs/IMPORT-NOTION.md §3.
 *
 * @property rowNumber номер строки в CSV, считая заголовок первой — как видит пользователь
 *   в Excel; помогает найти строку в Notion.
 * @property key ключ идемпотентности строки (§5.2).
 * @property category основная категория Notion (для доходов — null).
 */
data class ParsedRow(
    val rowNumber: Int,
    val key: String,
    val status: RowStatus,
    val period: Period?,
    val amount: Money?,
    val category: String?,
    val comment: String?,
    val messages: List<String>,
    val preview: String,
) {
    val isImportable: Boolean get() = status == RowStatus.OK || status == RowStatus.WARNING
}

/** Категория, которую раздел подставляет строкам без категории (§1.3). */
const val FALLBACK_CATEGORY = "Прочее"

/**
 * Применение правил §3 к таблице. Чистая функция: тот же вход — тот же результат,
 * включая ключи строк (на этом держится защита от повторного импорта).
 */
object NotionRowParser {

    private const val SEP = "\u001F"

    fun parse(
        table: SourceTable,
        mapping: ColumnMapping,
        target: TableTarget,
        defaultYear: Int,
    ): List<ParsedRow> {
        if (target == TableTarget.SKIP) return emptyList()
        val occurrences = mutableMapOf<String, Int>()
        return table.rows.mapIndexed { index, row ->
            val raw = ImportField.entries.joinToString(SEP) { table.cell(row, mapping[it]) }
            val k = (occurrences[raw] ?: 0) + 1
            occurrences[raw] = k
            val key = sha256(table.tableId + SEP + raw + SEP + k)
            parseRow(table, row, mapping, target, defaultYear, rowNumber = index + 2, key = key)
        }
    }

    private fun parseRow(
        table: SourceTable,
        row: List<String>,
        mapping: ColumnMapping,
        target: TableTarget,
        defaultYear: Int,
        rowNumber: Int,
        key: String,
    ): ParsedRow {
        fun value(field: ImportField) = table.cell(row, mapping[field])

        val name = value(ImportField.NAME)
        val amountText = value(ImportField.AMOUNT)
        val categories = if (target == TableTarget.EXPENSE) NotionValues.multiSelect(value(ImportField.CATEGORY)) else emptyList()
        val monthText = value(ImportField.MONTH)
        val yearText = value(ImportField.YEAR)
        val dateText = value(ImportField.DATE)
        val preview = listOf(name, amountText, categories.joinToString(", "), monthText, yearText, dateText)
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        fun result(status: RowStatus, period: Period? = null, amount: Money? = null, category: String? = null, comment: String? = null) =
            ParsedRow(rowNumber, key, status, period, amount, category, comment, errors + warnings, preview)

        if (row.all { it.isBlank() } || listOf(name, amountText, monthText, yearText, dateText).all { it.isEmpty() } && categories.isEmpty()) {
            return result(RowStatus.EMPTY)
        }

        // Сумма.
        val amount = when (val parsed = NotionValues.amount(amountText)) {
            NotionValues.AmountResult.Empty -> { errors += "нет суммы"; null }
            is NotionValues.AmountResult.Invalid -> { errors += "сумма ${parsed.reason}"; null }
            is NotionValues.AmountResult.Value -> parsed.money
        }

        // Период: «Месяц» + «Год» главнее даты (D-24).
        val date = NotionValues.date(dateText)
        if (dateText.isNotEmpty() && date == null) warnings += "дата «$dateText» не распознана"
        val monthFromColumn = NotionValues.month(monthText)
        if (monthText.isNotEmpty() && monthFromColumn == null) warnings += "месяц «$monthText» не распознан"
        val yearFromColumn = NotionValues.year(yearText)
        if (yearText.isNotEmpty() && yearFromColumn == null) warnings += "год «$yearText» не распознан"

        val month = monthFromColumn ?: date?.monthValue
        val year = when {
            yearFromColumn != null -> yearFromColumn
            date != null -> date.year
            else -> {
                if (month != null) warnings += "нет года — взят $defaultYear"
                defaultYear
            }
        }
        if (month == null) errors += "нет месяца"
        if (date != null && month != null && (date.monthValue != month || date.year != year)) {
            warnings += "«Месяц/Год» (${"%02d".format(month)}.$year) расходятся с датой " +
                "(${"%02d".format(date.monthValue)}.${date.year}) — взят месяц из колонки"
        }
        val period = if (month != null) runCatching { Period(year, month) }.getOrElse {
            errors += "период $month.$year вне допустимого диапазона"
            null
        } else null

        // Категория (только расходы).
        var category: String? = null
        val commentParts = mutableListOf<String>()
        if (name.isNotEmpty()) commentParts += name
        if (target == TableTarget.EXPENSE) {
            category = categories.firstOrNull() ?: FALLBACK_CATEGORY.also { warnings += "нет категории — «$FALLBACK_CATEGORY»" }
            if (categories.size > 1) {
                warnings += "несколько категорий — взята «${categories.first()}», остальные в комментарий"
                commentParts += categories.drop(1).joinToString(" ") { "#$it" }
            }
        }
        val comment = commentParts.joinToString(" ").ifEmpty { null }

        if (errors.isNotEmpty()) return result(RowStatus.ERROR, period, amount, category, comment)
        if (amount != null && amount.isZero()) return result(RowStatus.ZERO, period, amount, category, comment)
        val status = if (warnings.isEmpty()) RowStatus.OK else RowStatus.WARNING
        return result(status, period, amount, category, comment)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * Пары похожих категорий для подсказки на шаге 3 («маме» и «мама», «дом» и «на дом»).
 * Эвристика простая и только подсказывает — решение за пользователем.
 */
object CategorySimilarity {

    fun similarPairs(names: Collection<String>): List<Pair<String, String>> {
        val list = names.distinct()
        val result = mutableListOf<Pair<String, String>>()
        for (i in list.indices) for (j in i + 1 until list.size) {
            if (similar(list[i], list[j])) result += list[i] to list[j]
        }
        return result
    }

    fun similar(a: String, b: String): Boolean {
        val x = a.trim().lowercase()
        val y = b.trim().lowercase()
        if (x == y) return true
        if (x.length >= 3 && y.length >= 3 && (wordsOf(x).contains(y) || wordsOf(y).contains(x))) return true
        return minOf(x.length, y.length) >= 4 && levenshtein(x, y) <= 1
    }

    private fun wordsOf(text: String): Set<String> = text.split(' ').filter { it.isNotEmpty() }.toSet()

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
