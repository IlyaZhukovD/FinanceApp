package app.finance.core.model

import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Период учёта: месяц + год. Единственная временная ось приложения — дневной
 * гранулярности нет (SPEC.md §5).
 *
 * В БД хранится как `period INTEGER = year * 100 + month`, например февраль 2026 -> 202602.
 */
data class Period(val year: Int, val month: Int) : Comparable<Period> {

    init {
        require(year in MIN_YEAR..MAX_YEAR) {
            "Год $year вне допустимого диапазона $MIN_YEAR..$MAX_YEAR"
        }
        require(month in MONTHS_IN_YEAR) { "Месяц $month вне диапазона 1..12" }
    }

    /** Ключ для хранения и диапазонных запросов. */
    val key: Int get() = year * 100 + month

    fun plusMonths(count: Int): Period {
        val total = year * 12 + (month - 1) + count
        return Period(Math.floorDiv(total, 12), Math.floorMod(total, 12) + 1)
    }

    fun minusMonths(count: Int): Period = plusMonths(-count)

    /** Число месяцев от this до other; отрицательно, если other раньше this. */
    fun monthsUntil(other: Period): Int =
        (other.year * 12 + other.month) - (year * 12 + month)

    /** Все периоды от this до other включительно; пусто, если other раньше this. */
    fun monthsTo(other: Period): List<Period> {
        val count = monthsUntil(other)
        if (count < 0) return emptyList()
        return (0..count).map(::plusMonths)
    }

    fun withYear(newYear: Int): Period = Period(newYear, month)

    fun withMonth(newMonth: Int): Period = Period(year, newMonth)

    override fun compareTo(other: Period): Int = key.compareTo(other.key)

    override fun toString(): String = "%04d-%02d".format(year, month)

    companion object {
        const val MIN_YEAR = 2000
        const val MAX_YEAR = 2100
        private val MONTHS_IN_YEAR = 1..12

        /** Обратное преобразование ключа БД. Бросает исключение на недопустимом ключе. */
        fun ofKey(key: Int): Period {
            require(key >= 0) { "Ключ периода не может быть отрицательным: $key" }
            return Period(key / 100, key % 100)
        }

        fun of(yearMonth: YearMonth): Period = Period(yearMonth.year, yearMonth.monthValue)

        /** Текущий период в часовом поясе пользователя. Clock инжектится для тестов. */
        fun now(clock: Clock, zone: ZoneId): Period {
            val today = LocalDate.now(clock.withZone(zone))
            return Period(today.year, today.monthValue)
        }
    }
}
