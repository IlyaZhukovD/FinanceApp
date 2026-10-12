package app.finance.core.analytics

import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period

/** Статистика категории за диапазон: среднее, медиана, мин/макс (F6.5). */
data class CategoryStats(
    val categoryId: String,
    val total: Money,
    /** Число месяцев, в которых категория встречается, — база для среднего и медианы. */
    val monthCount: Int,
    val mean: Money,
    val median: Money,
    val min: Money,
    val max: Money,
)

/**
 * Изменение к соседнему периоду.
 *
 * `null` означает «сравнивать не с чем» (нет данных или gap), а не ноль: это та же
 * семантика, что у `totalDelta` в ledger и у дельты в активах (D-14).
 */
data class Change(val absolute: Money?, val percent: Int?) {
    val isUnknown: Boolean get() = absolute == null

    companion object {
        val NONE: Change = Change(absolute = null, percent = null)
    }
}

/**
 * Статистика по категориям (F6.5).
 *
 * Среднее и медиана считаются только по месяцам, в которых категория встречается.
 * Нулевой месяц для потока означает «трат не было», и его включение превратило бы средний
 * чек категории, которой пользовались дважды за год, в постоянно мелкое расходование.
 */
object Stats {

    fun byCategory(
        cells: List<CategoryMonthCell>,
        section: LedgerSection,
        range: PeriodRange,
        currency: String = Money.DEFAULT_CURRENCY,
    ): List<CategoryStats> = FlowSeries
        .relevant(cells, section, currency, range)
        .groupBy { it.categoryId }
        .entries
        .map { (categoryId, categoryCells) -> statsOf(categoryId, categoryCells, currency) }
        .sortedWith(
            compareByDescending<CategoryStats> { it.total.minor }.thenBy { it.categoryId },
        )

    private fun statsOf(
        categoryId: String,
        categoryCells: List<CategoryMonthCell>,
        currency: String,
    ): CategoryStats {
        // Одна ячейка на (категория, месяц), но сгруппировать всё равно нужно: вход может
        // содержать ячейки разных месяцев, а в будущем — нескольких валютных срезов.
        val monthly = categoryCells
            .groupBy { it.period }
            .mapValues { (_, cellsInMonth) ->
                cellsInMonth.fold(Money(0L, currency)) { acc, cell -> acc + cell.amount }
            }
            .values
            .sortedBy { it.minor }

        val total = monthly.fold(Money(0L, currency)) { acc, money -> acc + money }

        return CategoryStats(
            categoryId = categoryId,
            total = total,
            monthCount = monthly.size,
            mean = Money(meanMinor(total.minor, monthly.size), currency),
            median = median(monthly.map { it.minor }, currency),
            min = monthly.first(),
            max = monthly.last(),
        )
    }

    /** Медиана: для чётного числа значений — среднее двух средних, HALF_UP до копейки. */
    private fun median(sortedValues: List<Long>, currency: String): Money {
        val size = sortedValues.size
        require(size > 0) { "Медиана по пустому набору не определена" }
        val middle = size / 2
        val minor = if (size % 2 == 1) {
            sortedValues[middle]
        } else {
            meanMinor(Math.addExact(sortedValues[middle - 1], sortedValues[middle]), 2)
        }
        return Money(minor, currency)
    }
}

/** MoM и YoY по готовой месячной серии (F6.5). */
object Trends {

    /** Month-over-month: [anchor] против предыдущего месяца. */
    fun mom(points: List<SeriesPoint>, anchor: Period): Change =
        change(points, current = anchor, base = anchor.minusMonths(1))

    /** Year-over-year: [anchor] против того же месяца годом ранее. */
    fun yoy(points: List<SeriesPoint>, anchor: Period): Change =
        change(points, current = anchor, base = anchor.minusMonths(MONTHS_IN_YEAR))

    private fun change(points: List<SeriesPoint>, current: Period, base: Period): Change {
        val currentValue = points.firstOrNull { it.period == current }?.amount ?: return Change.NONE
        val baseValue = points.firstOrNull { it.period == base }?.amount ?: return Change.NONE
        val delta = runCatching { currentValue - baseValue }.getOrNull() ?: return Change.NONE
        return Change(
            absolute = delta,
            percent = Percent.change(current = currentValue.minor, base = baseValue.minor),
        )
    }

    private const val MONTHS_IN_YEAR = 12
}
