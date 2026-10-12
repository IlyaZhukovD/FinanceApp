package app.finance.core.analytics

import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period

/** Точка cash flow за месяц: доходы против расходов (F6.1.5). */
data class CashFlowPoint(
    val period: Period,
    val income: Money,
    val expense: Money,
    /** Доходы минус расходы: положительное значение — профицит месяца. */
    val delta: Money,
    /** Норма сбережений: доля дельты в доходах; null, если доходов в месяце нет. */
    val savingsRatePercent: Int?,
)

/** Cash flow за весь диапазон — для шапки и табличного вида (F6.1.7). */
data class CashFlowSummary(
    val income: Money,
    val expense: Money,
    val delta: Money,
    val savingsRatePercent: Int?,
    val months: Int,
    /** Месяцев с профицитом: ориентир стабильности, а не средняя величина. */
    val surplusMonths: Int,
)

/**
 * Cash flow — единственный расчёт, где доходы и расходы встречаются в одной точке.
 *
 * Это не смешивание миров из F6.8: там запрещено складывать потоки с остатками активов,
 * а доходы и расходы — оба потоки, и их дельта является смыслом метрики.
 */
object CashFlow {

    fun monthly(
        incomes: List<CategoryMonthCell>,
        expenses: List<CategoryMonthCell>,
        range: PeriodRange,
        currency: String = Money.DEFAULT_CURRENCY,
    ): List<CashFlowPoint> {
        val incomePoints = FlowSeries.monthlyTotals(incomes, LedgerSection.INCOME, range, currency)
        val expensePoints = FlowSeries.monthlyTotals(expenses, LedgerSection.EXPENSE, range, currency)
        val zero = Money(0L, currency)

        // Обе серии построены по одним и тем же месяцам диапазона, поэтому zip корректен.
        return incomePoints.zip(expensePoints) { income, expense ->
            val incomeAmount = income.amount ?: zero
            val expenseAmount = expense.amount ?: zero
            val delta = incomeAmount - expenseAmount
            CashFlowPoint(
                period = income.period,
                income = incomeAmount,
                expense = expenseAmount,
                delta = delta,
                savingsRatePercent = Percent.share(delta.minor, incomeAmount.minor),
            )
        }
    }

    fun summary(
        incomes: List<CategoryMonthCell>,
        expenses: List<CategoryMonthCell>,
        range: PeriodRange,
        currency: String = Money.DEFAULT_CURRENCY,
    ): CashFlowSummary {
        val points = monthly(incomes, expenses, range, currency)
        val zero = Money(0L, currency)
        val income = points.fold(zero) { acc, point -> acc + point.income }
        val expense = points.fold(zero) { acc, point -> acc + point.expense }
        val delta = income - expense

        return CashFlowSummary(
            income = income,
            expense = expense,
            delta = delta,
            savingsRatePercent = Percent.share(delta.minor, income.minor),
            months = points.size,
            surplusMonths = points.count { it.delta.isPositive() },
        )
    }
}
