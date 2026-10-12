package app.finance.core.analytics

import app.finance.core.model.Period

/**
 * Диапазон месяцев `[from, to]` включительно (SPEC.md §5).
 *
 * Покрывает пресеты F6.1.4: последние 3/6/12 месяцев, YTD, прошлый год и произвольный
 * диапазон. Все функции аналитики принимают именно [PeriodRange], чтобы «период» нельзя
 * было передать в половину открытым.
 */
data class PeriodRange(val from: Period, val to: Period) {

    init {
        require(from <= to) { "Начало диапазона $from позже конца $to" }
    }

    /** Все месяцы диапазона по порядку. */
    val months: List<Period> get() = from.monthsTo(to)

    /** Число месяцев в диапазоне. */
    val length: Int get() = from.monthsUntil(to) + 1

    fun contains(period: Period): Boolean = period >= from && period <= to

    /** Тот же диапазон, сдвинутый на [count] месяцев назад (для сравнения «A vs B», F6.1.4). */
    fun shiftedBackBy(count: Int): PeriodRange =
        PeriodRange(from = from.minusMonths(count), to = to.minusMonths(count))

    companion object {

        /** Последние [count] месяцев, заканчивая [anchor] включительно. */
        fun lastMonths(count: Int, anchor: Period): PeriodRange {
            require(count > 0) { "Длина диапазона должна быть положительной: $count" }
            return PeriodRange(from = anchor.minusMonths(count - 1), to = anchor)
        }

        /** Год до текущего месяца включительно (YTD). */
        fun yearToDate(anchor: Period): PeriodRange = PeriodRange(from = Period(anchor.year, 1), to = anchor)

        /** Полный календарный год. */
        fun year(year: Int): PeriodRange = PeriodRange(from = Period(year, 1), to = Period(year, 12))

        /** Прошлый календарный год относительно [anchor]. */
        fun previousYear(anchor: Period): PeriodRange = year(anchor.year - 1)
    }
}
