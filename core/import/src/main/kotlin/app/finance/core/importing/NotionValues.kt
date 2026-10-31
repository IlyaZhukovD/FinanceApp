package app.finance.core.importing

import app.finance.core.model.Money
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Разбор значений ячеек экспорта Notion (docs/IMPORT-NOTION.md §3).
 *
 * Форматы зависят от языка интерфейса Notion, поэтому поддержано несколько вариантов.
 * Все функции возвращают null на нераспознанном вводе — решение, ошибка это или пропуск,
 * принимает [NotionRowParser].
 */
object NotionValues {

    private val MONTHS_RU: Map<String, Int> = listOf(
        "январь", "февраль", "март", "апрель", "май", "июнь",
        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
    ).withIndex().associate { (i, name) -> name to i + 1 }

    /** Родительный падеж — так месяц пишется в датах: «1 октября 2026 г.». */
    private val MONTHS_RU_GENITIVE: Map<String, Int> = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    ).withIndex().associate { (i, name) -> name to i + 1 }

    private val MONTHS_EN: Map<String, Int> = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    ).withIndex().flatMap { (i, name) -> listOf(name to i + 1, name.take(3) to i + 1) }
        .toMap() + ("sept" to 9)

    private val ISO = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
    private val DMY = Regex("""^(\d{1,2})[./](\d{1,2})[./](\d{4})""")
    private val EN = Regex("""^([A-Za-z]+)\.?\s+(\d{1,2}),?\s+(\d{4})""")
    private val RU = Regex("""^(\d{1,2})\s+([А-Яа-яЁё]+)\s+(\d{4})""")
    private val YEAR = Regex("""(?<!\d)(20\d{2})(?!\d)""")

    /** «Октябрь», «октябрь», «июнь» и номер «10» -> 10. */
    fun month(value: String): Int? {
        val text = value.trim().lowercase().replace('ё', 'е')
        if (text.isEmpty()) return null
        text.toIntOrNull()?.let { return it.takeIf { m -> m in 1..12 } }
        return MONTHS_RU[text] ?: MONTHS_RU_GENITIVE[text] ?: MONTHS_EN[text]
    }

    fun year(value: String): Int? = value.trim().toIntOrNull()?.takeIf { it in 2000..2100 }

    /** Первый год вида 20xx в строке — например, в имени файла `notion-траты-2026.zip`. */
    fun yearIn(text: String): Int? = YEAR.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Дата Notion. Время и вторая половина диапазона (`… → …`) отбрасываются: для учёта
     * по месяцам важно только начало.
     */
    fun date(value: String): LocalDate? {
        val text = value.substringBefore('→').trim().lowercase().replace('ё', 'е')
        if (text.isEmpty()) return null
        return runCatching {
            ISO.find(text)?.let { m ->
                val (y, mo, d) = m.destructured
                return LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
            }
            DMY.find(text)?.let { m ->
                val (d, mo, y) = m.destructured
                return LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
            }
            EN.find(text)?.let { m ->
                val (name, d, y) = m.destructured
                val mo = MONTHS_EN[name] ?: return null
                return LocalDate.of(y.toInt(), mo, d.toInt())
            }
            RU.find(text)?.let { m ->
                val (d, name, y) = m.destructured
                val mo = MONTHS_RU_GENITIVE[name] ?: MONTHS_RU[name] ?: return null
                return LocalDate.of(y.toInt(), mo, d.toInt())
            }
            null
        }.getOrNull()
    }

    /** Результат разбора суммы: пусто, значение или ошибка с текстом для пользователя. */
    sealed interface AmountResult {
        data object Empty : AmountResult
        data class Value(val money: Money) : AmountResult
        data class Invalid(val reason: String) : AmountResult
    }

    /**
     * Сумма без `Double` (SPEC.md §5): `19500`, `19 500`, `19,500`, `₽19,500.50`,
     * `1 500,5 руб`, `-200`. Больше двух знаков после запятой — ошибка, а не округление:
     * молча терять копейки при переносе истории нельзя.
     */
    fun amount(value: String, currency: String = Money.DEFAULT_CURRENCY): AmountResult {
        var text = value.trim()
            .replace(" ", "")
            .replace(" ", "")
            .replace(" ", "")
            .replace("₽", "")
            .replace("руб.", "", ignoreCase = true)
            .replace("руб", "", ignoreCase = true)
            .replace("rub", "", ignoreCase = true)
            .replace("р.", "")
        if (text.isEmpty()) return AmountResult.Empty
        val negative = text.startsWith("-") || text.startsWith("−") || (text.startsWith("(") && text.endsWith(")"))
        text = text.trimStart('-', '−', '+', '(').trimEnd(')')

        val lastComma = text.lastIndexOf(',')
        val lastDot = text.lastIndexOf('.')
        text = when {
            lastComma >= 0 && lastDot >= 0 -> {
                // Оба разделителя: последний — десятичный, другой — разряды.
                if (lastComma > lastDot) text.replace(".", "").replace(',', '.') else text.replace(",", "")
            }
            lastComma >= 0 -> commaOrDot(text, ',')
            lastDot >= 0 -> commaOrDot(text, '.')
            else -> text
        }
        val decimal = runCatching { BigDecimal(text) }.getOrNull()
            ?: return AmountResult.Invalid("не число: «$value»")
        if (decimal.stripTrailingZeros().scale() > 2) return AmountResult.Invalid("больше двух знаков после запятой: «$value»")
        val minor = runCatching { decimal.movePointRight(2).longValueExact() }.getOrNull()
            ?: return AmountResult.Invalid("слишком большая сумма: «$value»")
        return AmountResult.Value(Money(if (negative) -minor else minor, currency))
    }

    /** Один вид разделителя: группы по 3 цифры — разряды, иначе — десятичная часть. */
    private fun commaOrDot(text: String, separator: Char): String {
        val parts = text.split(separator)
        val thousands = parts.size >= 2 && parts.drop(1).all { it.length == 3 } &&
            parts.first().length in 1..3 && (parts.size > 2 || separator == ',')
        return if (thousands) parts.joinToString("") else text.replace(separator, '.')
    }

    /**
     * Мультиселект Notion в CSV: значения через запятую. Пробелы по краям и пустые
     * значения отбрасываются, порядок сохраняется (первое — основная категория, D-25).
     */
    fun multiSelect(value: String): List<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
