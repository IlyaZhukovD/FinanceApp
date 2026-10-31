package app.finance.core.ui

import app.finance.core.model.AssetBalancePolicy
import app.finance.core.model.BalanceSource
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Форматирование для экрана. Только представление: арифметика денег остаётся в
 * [Money] и выполняется в целых копейках, доли и проценты считаются точно,
 * без перевода денег в `Double` (SPEC.md §5, §9).
 */

private const val MONEY_SCALE = 2

/** Неразрывный пробел как разделитель разрядов: сумма не переносится на другую строку. */
private const val GROUP_SEPARATOR = '\u00A0'
private const val DECIMAL_SEPARATOR = ','

private val HUNDRED = BigDecimal.valueOf(100)

private val MONTHS_SHORT_RU = listOf(
    "Янв", "Фев", "Мар", "Апр", "Май", "Июн",
    "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек",
)

private val MONTHS_RU = listOf(
    "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
)

private val CURRENCY_SYMBOLS = mapOf(
    "RUB" to "₽",
    "USD" to "$",
    "EUR" to "€",
    "GBP" to "£",
    "CNY" to "¥",
    "JPY" to "¥",
    "CHF" to "₣",
    "TRY" to "₺",
    "KZT" to "₸",
    "BYN" to "Br",
)

private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

/** «1234.50» -> «1 234,50 ₽». Неизвестный код валюты показывается как есть. */
fun Money.formatRu(): String {
    val plain = BigDecimal.valueOf(minor).movePointLeft(MONEY_SCALE).toPlainString()
    val negative = plain.startsWith("-")
    val unsigned = if (negative) plain.removePrefix("-") else plain
    val dot = unsigned.indexOf('.')
    val integerPart = if (dot >= 0) unsigned.substring(0, dot) else unsigned
    val fractionPart = if (dot >= 0) unsigned.substring(dot + 1) else "0".repeat(MONEY_SCALE)

    return buildString {
        if (negative) append('-')
        append(groupThousands(integerPart))
        append(DECIMAL_SEPARATOR)
        append(fractionPart)
        append(' ')
        append(CURRENCY_SYMBOLS[currency] ?: currency)
    }
}

/** То же, но с явным знаком: «+1 200,00 ₽», «−300,00 ₽». Ноль показывается без знака. */
fun Money.formatSignedRu(): String = when {
    minor > 0 -> "+${formatRu()}"
    // U+2212 (минус) вместо дефиса: в таблицах знак и разряды визуально совпадают.
    minor < 0 -> formatRu().replaceFirst("-", "\u2212")
    else -> formatRu()
}

/** Доля части в целом, в процентах, округление HALF_UP. Null, если итог неположителен. */
fun sharePercent(part: Money, total: Money): Int? {
    if (total.minor <= 0 || part.currency != total.currency) return null
    return percentOf(part.minor, total.minor)
}

/**
 * Подпись доли для таблицы: «38 %», «<1 %» или «—».
 *
 * Ненулевая сумма с долей, округлённой до нуля, показывается как «<1 %»: «0 %» читается
 * как «трат не было», а они были (найдено на реальных данных: «связь» 550 ₽ из 130 тыс.).
 */
fun shareLabelRu(percent: Int?, part: Money): String = when {
    percent == null -> "—"
    percent == 0 && part.isPositive() -> "<1 %"
    else -> "$percent %"
}

/**
 * Изменение к прошлому периоду в процентах. Null, если прошлый период пустой
 * (делить не на что) или валюты не совпадают — на экране это показывается как «—».
 */
fun deltaPercent(current: Money, previous: Money): Int? {
    if (previous.minor <= 0 || current.currency != previous.currency) return null
    val difference = BigDecimal.valueOf(current.minor).subtract(BigDecimal.valueOf(previous.minor))
    return percentOfDecimal(difference, BigDecimal.valueOf(previous.minor))
}

/** Заголовок периода: «Февраль 2026». */
fun Period.titleRu(): String = "${MONTHS_RU[month - 1]} $year"

/** Короткая подпись месяца для осей графиков: «Сен 2026». Цифры «09.2026» читались хуже. */
fun Period.chartLabelRu(): String = "${MONTHS_SHORT_RU[month - 1]} $year"

/** Короткая подпись периода для пикеров и отчётов: «02.2026». */
fun Period.shortRu(): String = "%02d.%04d".format(month, year)

fun LedgerSection.titleRu(): String = when (this) {
    LedgerSection.EXPENSE -> "Расходы"
    LedgerSection.INCOME -> "Доходы"
}

/**
 * Короткая подпись правила баланса категории активов (SPEC.md F4.6, D-20).
 *
 * Формулировки отвечают на вопрос пользователя «что будет с суммой в следующем месяце»,
 * а не пересказывают имена значений перечисления.
 */
fun AssetBalancePolicy.titleRu(): String = when (this) {
    AssetBalancePolicy.FIXED_RATE -> "Процент известен"
    AssetBalancePolicy.MANUAL_VALUATION -> "Оценка вручную"
    AssetBalancePolicy.FLAT -> "Без роста"
}

/** Пояснение к правилу баланса: показывается под выбором в редакторе категории. */
fun AssetBalancePolicy.hintRu(): String = when (this) {
    AssetBalancePolicy.FIXED_RATE ->
        "Вклад: ставка и срок задаются у счёта, сумма следующих месяцев считается сама"
    AssetBalancePolicy.MANUAL_VALUATION ->
        "Акции, паи, валюта: сумма переносится с прошлого месяца и ждёт вашего подтверждения"
    AssetBalancePolicy.FLAT ->
        "Наличные и подобное: сумма переносится как есть, без напоминаний"
}

/**
 * Пометка источника значения счёта (F4.6, D-21).
 *
 * Факт и отсутствие данных не помечаются: пометка нужна ровно там, где сумма получена
 * расчётом или переносом, чтобы прогноз вклада не выглядел введённым остатком.
 */
fun BalanceSource.badgeRu(): String? = when (this) {
    BalanceSource.RECORDED -> null
    BalanceSource.PROJECTED -> "прогноз"
    BalanceSource.CARRIED -> "перенесено"
    BalanceSource.CARRIED_DRAFT -> "черновик"
    BalanceSource.MISSING -> null
}

/** Метка времени ревизии в часовом поясе пользователя: «09.02.2026 14:35». */
fun formatTimestamp(utcMillis: Long, zone: ZoneId): String =
    TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(utcMillis).atZone(zone))

private fun percentOf(numerator: Long, denominator: Long): Int? =
    percentOfDecimal(BigDecimal.valueOf(numerator), BigDecimal.valueOf(denominator))

/**
 * Процент с округлением HALF_UP. На отрицательных значениях HALF_UP округляет
 * от нуля (-16.67 -> -17), что совпадает с ожиданием для дельт.
 *
 * Исключение (например, результат вне диапазона Int) превращается в null:
 * форматирование не должно ронять экран.
 */
private fun percentOfDecimal(numerator: BigDecimal, denominator: BigDecimal): Int? =
    runCatching {
        numerator.multiply(HUNDRED)
            .divide(denominator, 0, RoundingMode.HALF_UP)
            .intValueExact()
    }.getOrNull()

private fun groupThousands(digits: String): String {
    val builder = StringBuilder()
    digits.reversed().forEachIndexed { index, char ->
        if (index > 0 && index % 3 == 0) builder.append(GROUP_SEPARATOR)
        builder.append(char)
    }
    return builder.reverse().toString()
}
