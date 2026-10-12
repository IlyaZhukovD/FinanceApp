package app.finance.core.analytics

import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period

/**
 * Точка месячной серии.
 *
 * `amount == null` означает gap: месяц пропущен и точка не рисуется (SPEC.md §5).
 * У потоков gaps не бывает — отсутствие ячейки legitimately означает ноль, поэтому
 * [FlowSeries] всегда возвращает значения. Gap используется только для остатков
 * ([NetWorthSeries]), где пропуск месяца — это отсутствие данных, а не ноль.
 */
data class SeriesPoint(val period: Period, val amount: Money?) {
    val isGap: Boolean get() = amount == null
}

/**
 * Серия одной категории: сегмент stacked bar (F6.1.1) или линия динамики (F6.1.2).
 *
 * Точки идут по всем месяцам [PeriodRange], поэтому серии разных категорий всегда
 * одинаковой длины и их можно рисовать на одной оси без выравнивания.
 */
data class CategorySeries(val categoryId: String, val points: List<SeriesPoint>) {

    /** Сумма по месяцам с данными; null, если значений нет вовсе. */
    val total: Money?
        get() {
            val values = points.mapNotNull { it.amount }
            if (values.isEmpty()) return null
            val currency = values.first().currency
            return values.filter { it.currency == currency }
                .fold(Money(0L, currency)) { acc, money -> acc + money }
        }
}

/** Категория, её сумма и доля в итоге — данные для donut и табличного вида (F6.1.3, F6.1.7). */
data class CategoryShare(
    val categoryId: String,
    val amount: Money,
    /** Доля в целых процентах (HALF_UP); null при нулевом итоге. */
    val sharePercent: Int?,
)

/**
 * Серии потоков — расходов и доходов (F6.1.1, F6.1.2).
 *
 * Балансы активов здесь не появляются намеренно: миры потоков и остатков не смешиваются
 * ни в одной серии (F6.8). Для капитала есть [NetWorthSeries].
 *
 * Ячейки другой секции, другой валюты, удалённые и вне диапазона отбрасываются, поэтому
 * в функцию можно передать весь срез из хранилища.
 */
object FlowSeries {

    /**
     * Итоги по месяцам. Отсутствующий месяц — ноль, а не gap (SPEC.md §5).
     */
    fun monthlyTotals(
        cells: List<CategoryMonthCell>,
        section: LedgerSection,
        range: PeriodRange,
        currency: String = Money.DEFAULT_CURRENCY,
    ): List<SeriesPoint> {
        val sums = sumsByPeriod(relevant(cells, section, currency, range), currency)
        return range.months.map { period ->
            SeriesPoint(period = period, amount = sums[period] ?: Money(0L, currency))
        }
    }

    /** Итог за весь диапазон в той же валюте. */
    fun total(
        cells: List<CategoryMonthCell>,
        section: LedgerSection,
        range: PeriodRange,
        currency: String = Money.DEFAULT_CURRENCY,
    ): Money = relevant(cells, section, currency, range)
        .fold(Money(0L, currency)) { acc, cell -> acc + cell.amount }

    /**
     * По серии на категорию для stacked bar.
     *
     * Порядок — по убыванию итога, при равенстве по id: крупные категории стабильно первые
     * и в легенде, и в стеке, а golden-тесты не зависят от порядка выборки из базы.
     */
    fun byCategory(
        cells: List<CategoryMonthCell>,
        section: LedgerSection,
        range: PeriodRange,
        currency: String = Money.DEFAULT_CURRENCY,
    ): List<CategorySeries> {
        val sumsByCategory = relevant(cells, section, currency, range)
            .groupBy { it.categoryId }
            .mapValues { (_, categoryCells) -> sumsByPeriod(categoryCells, currency) }

        return sumsByCategory.entries
            .map { (categoryId, sums) ->
                CategorySeries(
                    categoryId = categoryId,
                    points = range.months.map { period ->
                        SeriesPoint(period = period, amount = sums[period] ?: Money(0L, currency))
                    },
                )
            }
            .sortedWith(
                compareByDescending<CategorySeries> { it.total?.minor ?: Long.MIN_VALUE }
                    .thenBy { it.categoryId },
            )
    }

    /** Ячейки, относящиеся к расчёту: секция, валюта, живые и внутри диапазона. */
    internal fun relevant(
        cells: List<CategoryMonthCell>,
        section: LedgerSection,
        currency: String,
        range: PeriodRange,
    ): List<CategoryMonthCell> = cells.filter { cell ->
        cell.section == section &&
            cell.amount.currency == currency &&
            !cell.isDeleted &&
            range.contains(cell.period)
    }

    /**
     * Суммы по месяцам. Ячейка `(category, period)` уникальна, поэтому группировка
     * сводится к одному счету на категорию — SQL не обязан ничего агрегировать (F6.4).
     */
    private fun sumsByPeriod(cells: List<CategoryMonthCell>, currency: String): Map<Period, Money> =
        cells.groupBy { it.period }.mapValues { (_, list) ->
            list.fold(Money(0L, currency)) { acc, cell -> acc + cell.amount }
        }
}

/**
 * Структура периода по категориям: суммы и доли (F6.1.3), топ-N (F6.5).
 *
 * Сумма округлённых долей может отличаться от 100% на единицу — это следствие округления
 * HALF_UP каждой доли, а не потеря данных: итог считается по точным копейкам.
 */
object Breakdown {

    fun of(
        cells: List<CategoryMonthCell>,
        section: LedgerSection,
        range: PeriodRange,
        currency: String = Money.DEFAULT_CURRENCY,
    ): List<CategoryShare> {
        val relevant = FlowSeries.relevant(cells, section, currency, range)
        val sums = relevant
            .groupBy { it.categoryId }
            .mapValues { (_, list) ->
                list.fold(Money(0L, currency)) { acc, cell -> acc + cell.amount }
            }
        val total = sums.values.fold(Money(0L, currency)) { acc, money -> acc + money }

        return sums.entries
            .map { (categoryId, amount) ->
                CategoryShare(
                    categoryId = categoryId,
                    amount = amount,
                    sharePercent = Percent.share(amount.minor, total.minor),
                )
            }
            .sortedWith(
                compareByDescending<CategoryShare> { it.amount.minor }.thenBy { it.categoryId },
            )
    }

    /** Топ-N категорий по сумме. `n <= 0` даёт пустой список. */
    fun topN(shares: List<CategoryShare>, n: Int): List<CategoryShare> =
        if (n <= 0) emptyList() else shares.take(n)
}
