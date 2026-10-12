package app.finance.core.analytics

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Точная арифметика аналитики: проценты и средние без `Double` (SPEC.md §5 и §9).
 *
 * Округление совпадает с `core:ui` (HALF_UP до целого процента), но живёт здесь:
 * `core:analytics` — слой расчётов и не должен зависеть от UI (F6.4). Дублирование
 * одной функции округления дешевле, чем обратная зависимость слоёв.
 */
internal object Percent {

    /**
     * Доля [part] от [total] в целых процентах.
     *
     * `null`, если [total] равен нулю: доля не определена, и показывать 0% было бы ложью.
     */
    fun share(part: Long, total: Long): Int? = ratio(part, total)

    /**
     * Изменение [current] относительно [base] в процентах.
     *
     * `null` при непол положительной базе: процент от нуля не определён, а от отрицательной
     * базы (например, отрицательный Net Worth из-за кредитов) знак процента вводит в
     * заблуждение — лучше отсутствие значения, чем красивая бессмыслица.
     */
    fun change(current: Long, base: Long): Int? = if (base <= 0L) null else ratio(current - base, base)

    private fun ratio(numerator: Long, denominator: Long): Int? {
        if (denominator == 0L) return null
        return BigDecimal.valueOf(numerator)
            .multiply(HUNDRED)
            .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP)
            .intValueExact()
    }

    private val HUNDRED: BigDecimal = BigDecimal.valueOf(100)
}

/**
 * Среднее по деньгам с округлением HALF_UP до целых копеек.
 *
 * Деление суммы на число месяцев почти всегда даёт дробную копейку; округление допустимо
 * только здесь, на границе представления метрики, а не в накоплении сумм.
 */
internal fun meanMinor(totalMinor: Long, count: Int): Long {
    require(count > 0) { "Среднее по пустому набору не определено" }
    return BigDecimal.valueOf(totalMinor)
        .divide(BigDecimal.valueOf(count.toLong()), 0, RoundingMode.HALF_UP)
        .longValueExact()
}
