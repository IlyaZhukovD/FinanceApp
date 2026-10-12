package app.finance.core.analytics

import app.finance.core.model.AssetSnapshot
import app.finance.core.model.Money
import app.finance.core.model.Period

/**
 * Точка серии капитала (F6.1.6).
 *
 * В месяце без значений все поля null: пропуск месяца — это gap, а не ноль (SPEC.md §5).
 * Линия в таком месте разрывается, иначе капитал «обнулится» из-за забытого ввода остатка.
 */
data class NetWorthPoint(
    val period: Period,
    val netWorth: Money?,
    /** Взносы и снятия месяца; null вместе с gap. */
    val contributions: Money?,
    /** Δ − взносы (F4.1); null, если нет данных текущего или строго предыдущего месяца. */
    val gain: Money?,
) {
    val isGap: Boolean get() = netWorth == null
}

/**
 * Итог месяца для серии капитала: сумма учтённых значений и взносы.
 *
 * Готовит вызывающий код через единый механизм значений активов (`AssetBalances`, D-21),
 * поэтому в серии участвуют и фактические остатки, и прогноз вкладов, и перенос остатков
 * активов без роста. Черновики ручной оценки сюда не попадают: они не учтены в итогах.
 */
data class NetWorthMonth(
    val period: Period,
    val netWorth: Money,
    val contributions: Money,
)

/**
 * Серия капитала по месяцам.
 *
 * Остатки не смешиваются с потоками (F6.8): это отдельный тип серии, который нельзя
 * передать в [FlowSeries], и наоборот.
 *
 * Прирост считается только от строго предыдущего месяца (`M − 1`), как записано в F4.1.
 * После gap сравнение с «последним известным» капиталом охватило бы несколько месяцев и
 * показало бы прирост, которого в этом месяце не было, поэтому значение остаётся null.
 */
object NetWorthSeries {

    /**
     * Серия по готовым итогам месяца — основной путь (D-21).
     *
     * Месяц без итога остаётся gap: отсутствие значения означает «данных нет», а не ноль.
     */
    fun monthly(months: List<NetWorthMonth>, range: PeriodRange): List<NetWorthPoint> {
        val byPeriod = months.associateBy { it.period }
        var previous: NetWorthMonth? = null

        return range.months.map { period ->
            val month = byPeriod[period]
            if (month == null) {
                NetWorthPoint(period = period, netWorth = null, contributions = null, gain = null)
            } else {
                val known = previous
                // Разные валюты не складываются: прирост считаем только в одной.
                val gain = if (known != null &&
                    known.period == period.minusMonths(1) &&
                    known.netWorth.currency == month.netWorth.currency
                ) {
                    runCatching { month.netWorth - known.netWorth - month.contributions }.getOrNull()
                } else {
                    null
                }
                previous = month
                NetWorthPoint(
                    period = period,
                    netWorth = month.netWorth,
                    contributions = month.contributions,
                    gain = gain,
                )
            }
        }
    }

    /**
     * Серия по «сырым» снапшотам одной валюты.
     *
     * Вход для кода, которому не нужны правила перехода месяца: удалённые снапшоты и
     * чужая валюта отбрасываются, месяцы без снапшотов остаются gap.
     */
    fun monthly(
        snapshots: List<AssetSnapshot>,
        range: PeriodRange,
        currency: String = Money.DEFAULT_CURRENCY,
    ): List<NetWorthPoint> {
        val months = snapshots
            .filter { snapshot ->
                snapshot.deletedAtUtc == null &&
                    snapshot.balance.currency == currency &&
                    range.contains(snapshot.period)
            }
            .groupBy { it.period }
            .map { (period, list) ->
                val zero = Money(0L, currency)
                NetWorthMonth(
                    period = period,
                    netWorth = list.fold(zero) { acc, snapshot -> acc + snapshot.balance },
                    contributions = list.fold(zero) { acc, snapshot ->
                        snapshot.contribution?.let { acc + it } ?: acc
                    },
                )
            }
        return monthly(months, range)
    }
}
