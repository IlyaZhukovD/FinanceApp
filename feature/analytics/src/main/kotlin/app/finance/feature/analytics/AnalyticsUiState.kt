package app.finance.feature.analytics

import app.finance.core.analytics.Change
import app.finance.core.analytics.PeriodRange
import app.finance.core.model.Money
import app.finance.core.model.Period

/**
 * Пресеты произвольного периода (F6.1.4).
 *
 * Диапазон определяется парой «якорь + пресет»: якорь — это конец диапазона, поэтому
 * переключение месяца сдвигает весь период, а не только его границу.
 */
enum class RangePreset(val titleRu: String) {
    LAST_3("3 месяца"),
    LAST_6("6 месяцев"),
    LAST_12("12 месяцев"),
    YEAR_TO_DATE("С начала года"),
    PREVIOUS_YEAR("Прошлый год");

    fun range(anchor: Period): PeriodRange = when (this) {
        LAST_3 -> PeriodRange.lastMonths(count = 3, anchor = anchor)
        LAST_6 -> PeriodRange.lastMonths(count = 6, anchor = anchor)
        LAST_12 -> PeriodRange.lastMonths(count = 12, anchor = anchor)
        YEAR_TO_DATE -> PeriodRange.yearToDate(anchor)
        PREVIOUS_YEAR -> PeriodRange.previousYear(anchor)
    }
}

/**
 * Состояние экрана «Аналитика» (SPEC.md F6).
 *
 * Экран только читает: мутаций здесь нет, поэтому в состоянии нет форм, подтверждений и
 * ревизий — в отличие от ledger и активов. Все числа приходят готовыми из `core:analytics`
 * (F6.4), экран лишь раскладывает их по строкам и форматирует.
 */
data class AnalyticsUiState(
    /** Конец рассматриваемого диапазона. */
    val anchor: Period,
    val preset: RangePreset = RangePreset.LAST_6,
    val isLoading: Boolean = true,
    /** Валюта среза: курсы конвертации в v1 отсутствуют, поэтому валюты не смешиваются. */
    val currency: String = Money.DEFAULT_CURRENCY,
    val summary: SummaryRow? = null,
    val cashFlow: List<CashFlowRow> = emptyList(),
    val expenseRows: List<CategoryRow> = emptyList(),
    val incomeRows: List<CategoryRow> = emptyList(),
    val netWorthRows: List<NetWorthRow> = emptyList(),
    /**
     * Месячная динамика расходов по категориям — данные для stacked bar (F6.1.1).
     * Порядок уже отсортирован по убыванию итога, поэтому крупные категории стабильно
     * внизу стека и в легенде.
     */
    val expenseTrend: List<CategoryTrendRow> = emptyList(),
    /** MoM и YoY по расходам на конец диапазона (F6.5); null — сравнивать не с чем. */
    val expenseMom: Change? = null,
    val expenseYoy: Change? = null,
    val error: String? = null,
) {

    val range: PeriodRange get() = preset.range(anchor)

    val hasFlowData: Boolean get() = cashFlow.any { it.income.minor != 0L || it.expense.minor != 0L }

    val hasNetWorthData: Boolean get() = netWorthRows.any { !it.isGap }

    /** Пустой период (одни нули) не считается данными: график показывает пустое состояние. */
    val hasExpenseTrend: Boolean
        get() = expenseTrend.any { row -> row.points.any { point -> (point.amount?.minor ?: 0L) != 0L } }
}

/** Итоги диапазона — шапка экрана. */
data class SummaryRow(
    val income: Money,
    val expense: Money,
    val delta: Money,
    val savingsRatePercent: Int?,
    val months: Int,
    val surplusMonths: Int,
)

/** Строка cash flow за месяц (F6.1.5). */
data class CashFlowRow(
    val period: Period,
    val income: Money,
    val expense: Money,
    val delta: Money,
    val savingsRatePercent: Int?,
)

/**
 * Категория в структуре периода (F6.1.3): сумма, доля и среднее за месяц.
 *
 * [sharePercent] равен null только при нулевом итоге — показывать 0% в этом случае было бы
 * неправдой. [mean] считается по месяцам, в которых категория встречалась (F6.5).
 */
data class CategoryRow(
    val categoryId: String,
    val name: String,
    val color: String,
    val amount: Money,
    val sharePercent: Int?,
    val mean: Money?,
)

/** Строка серии капитала (F6.1.6); [isGap] — месяц без введённых остатков. */
data class NetWorthRow(
    val period: Period,
    val netWorth: Money?,
    val contributions: Money?,
    val gain: Money?,
) {
    val isGap: Boolean get() = netWorth == null
}

/** Точка месячной динамики; null означает пропуск (у потоков не встречается, у активов — да). */
data class TrendPoint(val period: Period, val amount: Money?)

/** Месячная динамика одной категории — одна серия графика (F6.1.1, F6.1.2). */
data class CategoryTrendRow(
    val categoryId: String,
    val name: String,
    val color: String,
    val points: List<TrendPoint>,
)
