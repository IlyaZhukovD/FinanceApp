package app.finance.feature.analytics

import app.finance.core.analytics.Breakdown
import app.finance.core.analytics.CashFlow
import app.finance.core.analytics.CategorySeries
import app.finance.core.analytics.CategoryShare
import app.finance.core.analytics.CategoryStats
import app.finance.core.analytics.Change
import app.finance.core.analytics.FlowSeries
import app.finance.core.analytics.NetWorthMonth
import app.finance.core.analytics.NetWorthSeries
import app.finance.core.analytics.PeriodRange
import app.finance.core.analytics.Stats
import app.finance.core.analytics.Trends
import app.finance.core.domain.AssetRepository
import app.finance.core.domain.CategoryRepository
import app.finance.core.domain.LedgerRepository
import app.finance.core.model.AssetBalances
import app.finance.core.model.Category
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Period
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Контроллер раздела «Аналитика» (SPEC.md F6).
 *
 * Раздел read-only: он не создаёт ревизий и не пишет в `sync_outbox`, а значит не может
 * испортить данные — вся опасность здесь в неправильном расчёте, поэтому расчёты вынесены
 * в чистые функции `core:analytics` с golden-тестами (F6.4), а контроллер отвечает лишь за
 * выборку диапазона и перекладывание результата в состояние экрана.
 *
 * [scope] и [ioDispatcher] принимаются извне, как в ledger и активах: в приложении их даёт
 * `AppGraph`, в тестах — `TestScope`.
 */
class AnalyticsController(
    private val ledger: LedgerRepository,
    private val assets: AssetRepository,
    private val categories: CategoryRepository,
    private val initialPeriod: Period,
    private val clock: Clock,
    private val zone: ZoneId,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val _state = MutableStateFlow(AnalyticsUiState(anchor = initialPeriod))

    val state: StateFlow<AnalyticsUiState> = _state.asStateFlow()

    /** Первичная загрузка. Вызывается из `LaunchedEffect` при входе экрана в композицию. */
    fun start() = reload()

    // ── Период (F6.1.4) ──────────────────────────────────────────────────────

    fun previousMonth() = moveTo(_state.value.anchor.minusMonths(1))

    fun nextMonth() = moveTo(_state.value.anchor.plusMonths(1))

    fun today() = moveTo(Period.now(clock, zone))

    fun moveTo(period: Period) {
        if (period == _state.value.anchor) return
        _state.update { it.copy(anchor = period) }
        reload()
    }

    fun selectPreset(preset: RangePreset) {
        if (preset == _state.value.preset) return
        _state.update { it.copy(preset = preset) }
        reload()
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ── Загрузка ─────────────────────────────────────────────────────────────

    private fun reload() {
        val requested = _state.value.range
        val currency = _state.value.currency
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            runCatching { io { read(requested, currency) } }
                .onFailure { error -> _state.update { failed(it, error) } }
                .onSuccess { data ->
                    _state.update { current ->
                        // За время загрузки пользователь мог сменить месяц или пресет:
                        // устаревший срез не показываем — его заменит более поздняя загрузка.
                        if (current.range != requested) current else current.withData(data)
                    }
                }
        }
    }

    private suspend fun read(range: PeriodRange, currency: String): AnalyticsData {
        // MoM и YoY сравнивают конец диапазона с месяцами до его начала, поэтому ячейки
        // читаются один раз за расширенный период, а расчёты идут по своим границам.
        // Расширение может выйти за минимально допустимый год — тогда сравниваем в пределах
        // запрошенного диапазона, и YoY честно останется неизвестным.
        val extended = runCatching {
            PeriodRange(from = range.from.minusMonths(MONTHS_IN_YEAR), to = range.to)
        }.getOrDefault(range)

        val expenseCells = ledger.cellsInRange(LedgerSection.EXPENSE, extended.from, extended.to)
        val incomeCells = ledger.cellsInRange(LedgerSection.INCOME, extended.from, extended.to)

        // Серия капитала строится из тех же значений, что и экран активов (D-21): факт,
        // прогноз вкладов по ставке и перенос остатков. Снапшоты читаются до конца
        // диапазона, потому что значение месяца считается от последнего введённого остатка.
        val accounts = assets.accounts(includeClosed = true)
        val assetCategories = categories.bySection(CategorySection.ASSET, includeArchived = true)
        val snapshots = assets.snapshotsUpTo(range.to)
        val netWorthMonths = range.months.mapNotNull { period ->
            AssetBalances.monthTotals(period, accounts, assetCategories, snapshots)
                .firstOrNull { it.currency == currency }
                ?.let { total ->
                    NetWorthMonth(
                        period = period,
                        netWorth = total.netWorth,
                        contributions = total.contributions,
                    )
                }
        }
        val knownCategories = categoryIndex()

        val expenseTotals = FlowSeries.monthlyTotals(expenseCells, LedgerSection.EXPENSE, extended, currency)
        val expenseStats = Stats.byCategory(expenseCells, LedgerSection.EXPENSE, range, currency)
            .associateBy { it.categoryId }

        val flow = CashFlow.monthly(incomeCells, expenseCells, range, currency)
        val totals = CashFlow.summary(incomeCells, expenseCells, range, currency)

        return AnalyticsData(
            summary = SummaryRow(
                income = totals.income,
                expense = totals.expense,
                delta = totals.delta,
                savingsRatePercent = totals.savingsRatePercent,
                months = totals.months,
                surplusMonths = totals.surplusMonths,
            ),
            cashFlow = flow.map { point ->
                CashFlowRow(
                    period = point.period,
                    income = point.income,
                    expense = point.expense,
                    delta = point.delta,
                    savingsRatePercent = point.savingsRatePercent,
                )
            },
            expenseRows = rowsOf(
                shares = Breakdown.of(expenseCells, LedgerSection.EXPENSE, range, currency),
                categories = knownCategories,
                stats = expenseStats,
            ),
            incomeRows = rowsOf(
                shares = Breakdown.of(incomeCells, LedgerSection.INCOME, range, currency),
                categories = knownCategories,
                // Средний доход по категории не показываем: доход нерегулярен, и среднее
                // за месяц здесь скорее вводит в заблуждение, чем помогает.
                stats = emptyMap(),
            ),
            netWorthRows = NetWorthSeries.monthly(netWorthMonths, range).map { point ->
                NetWorthRow(
                    period = point.period,
                    netWorth = point.netWorth,
                    contributions = point.contributions,
                    gain = point.gain,
                )
            },
            expenseTrend = FlowSeries.byCategory(expenseCells, LedgerSection.EXPENSE, range, currency)
                .map { series -> trendRowOf(series = series, categories = knownCategories) },
            expenseMom = Trends.mom(expenseTotals, range.to),
            expenseYoy = Trends.yoy(expenseTotals, range.to),
        )
    }

    private suspend fun categoryIndex(): Map<String, Category> =
        (categories.bySection(CategorySection.EXPENSE, includeArchived = true) +
            categories.bySection(CategorySection.INCOME, includeArchived = true))
            .associateBy { it.id }

    private fun rowsOf(
        shares: List<CategoryShare>,
        categories: Map<String, Category>,
        stats: Map<String, CategoryStats>,
    ): List<CategoryRow> = shares.map { share ->
        val category = categories[share.categoryId]
        CategoryRow(
            categoryId = share.categoryId,
            // Архивная или удалённая категория не должна превращать сумму в «Без названия»:
            // имя берём из справочника, а при его отсутствии показываем честную заглушку.
            name = category?.name ?: UNKNOWN_CATEGORY,
            color = category?.color ?: NEUTRAL_COLOR,
            amount = share.amount,
            sharePercent = share.sharePercent,
            mean = stats[share.categoryId]?.mean,
        )
    }

    private fun trendRowOf(series: CategorySeries, categories: Map<String, Category>): CategoryTrendRow {
        val category = categories[series.categoryId]
        return CategoryTrendRow(
            categoryId = series.categoryId,
            name = category?.name ?: UNKNOWN_CATEGORY,
            color = category?.color ?: NEUTRAL_COLOR,
            points = series.points.map { point -> TrendPoint(period = point.period, amount = point.amount) },
        )
    }

    // ── Служебное ────────────────────────────────────────────────────────────

    private suspend fun <T> io(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    private fun failed(state: AnalyticsUiState, error: Throwable): AnalyticsUiState = state.copy(
        isLoading = false,
        error = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
    )

    private fun AnalyticsUiState.withData(data: AnalyticsData): AnalyticsUiState = copy(
        isLoading = false,
        summary = data.summary,
        cashFlow = data.cashFlow,
        expenseRows = data.expenseRows,
        incomeRows = data.incomeRows,
        netWorthRows = data.netWorthRows,
        expenseTrend = data.expenseTrend,
        expenseMom = data.expenseMom,
        expenseYoy = data.expenseYoy,
        error = null,
    )

    private data class AnalyticsData(
        val summary: SummaryRow,
        val cashFlow: List<CashFlowRow>,
        val expenseRows: List<CategoryRow>,
        val incomeRows: List<CategoryRow>,
        val netWorthRows: List<NetWorthRow>,
        val expenseTrend: List<CategoryTrendRow>,
        val expenseMom: Change?,
        val expenseYoy: Change?,
    )

    private companion object {
        const val MONTHS_IN_YEAR = 12
        const val NEUTRAL_COLOR = "#7A8189"
        const val UNKNOWN_CATEGORY = "Без категории"
    }
}
