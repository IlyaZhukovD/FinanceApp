package app.finance.feature.analytics

import app.finance.core.ui.QuietButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.finance.core.analytics.Change
import app.finance.core.charts.ComboChart
import app.finance.core.charts.DonutChart
import app.finance.core.charts.GroupedBarChart
import app.finance.core.charts.StackedBarChart
import app.finance.core.charts.limitSlices
import app.finance.core.model.Money
import app.finance.core.ui.shareLabelRu
import app.finance.core.ui.CategoryChip
import app.finance.core.ui.EmptyStateCard
import app.finance.core.ui.FinanceCard
import app.finance.core.ui.FinancePalette
import app.finance.core.ui.FinanceSpace
import app.finance.core.ui.HeaderCell
import app.finance.core.ui.MoneyCell
import app.finance.core.ui.NoticeBar
import app.finance.core.ui.Numbers
import app.finance.core.ui.PeriodNavigator
import app.finance.core.ui.SectionHeader
import app.finance.core.ui.TableHeaderRow
import app.finance.core.ui.TableRow
import app.finance.core.ui.TextCell
import app.finance.core.ui.deltaColor
import app.finance.core.ui.formatRu
import app.finance.core.ui.formatSignedRu
import app.finance.core.ui.titleRu

/**
 * Экран «Аналитика» (SPEC.md F6) — дашборд из карточек.
 *
 * Расклад двухколоночный: структуры слева и справа, динамика — во всю ширину. Так соседние
 * карточки сравниваются взглядом по горизонтали, а длинные временные ряды не сжимаются.
 *
 * Графики рисует `core:charts`, все числа приходят из `core:analytics` — экран их не
 * пересчитывает, иначе расчёт и отображение разъехались бы (F6.4, D-15). Таблицы под
 * графиками сохранены (F6.1.7): точную величину по сектору или столбцу не определить.
 *
 * Потоки и остатки разведены по разным карточкам и никогда не складываются в одной строке
 * (F6.8): cash flow — это доходы против расходов, Net Worth — только балансы.
 */
@Composable
fun AnalyticsScreen(controller: AnalyticsController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()

    LaunchedEffect(Unit) { controller.start() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(FinanceSpace.Lg)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
    ) {
        Header(state = state, controller = controller)

        val error = state.error
        if (error != null) {
            NoticeBar(text = error, onDismiss = controller::dismissError, error = true)
        }

        if (state.isLoading) {
            Loading()
        } else {
            Content(state = state)
        }
    }
}

@Composable
private fun Header(state: AnalyticsUiState, controller: AnalyticsController) {
    val range = state.range

    Column(verticalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
        SectionHeader(
            title = "Аналитика",
            subtitle = "Период ${range.from.titleRu()} — ${range.to.titleRu()} · ${range.length} мес.",
            actions = {
                // Расходы: рост — плохая новость, поэтому favorableWhenPositive = false.
                ChangeText(label = "MoM", change = state.expenseMom, favorableWhenPositive = false)
                ChangeText(label = "YoY", change = state.expenseYoy, favorableWhenPositive = false)
            },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
        ) {
            PeriodNavigator(
                period = state.anchor,
                onPrevious = controller::previousMonth,
                onNext = controller::nextMonth,
                onToday = controller::today,
            )
            PresetRow(state = state, controller = controller)
        }
    }
}

/** Пресеты периода (F6.1.4): чипсы, выбранный — с акцентной заливкой и границей. */
@Composable
private fun PresetRow(state: AnalyticsUiState, controller: AnalyticsController) {
    Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
        RangePreset.entries.forEach { preset ->
            CategoryChip(
                label = preset.titleRu,
                selected = preset == state.preset,
                onClick = { controller.selectPreset(preset) },
            )
        }
    }
}

@Composable
private fun Loading() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(FinanceSpace.Xl),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun Content(state: AnalyticsUiState) {
    SummaryCard(summary = state.summary)

    Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Md)) {
        StructureCard(
            title = "Структура расходов",
            rows = state.expenseRows,
            emptyHint = "За период нет ни одной ячейки расходов",
            currency = state.currency,
            showMean = true,
        )
        StructureCard(
            title = "Структура доходов",
            rows = state.incomeRows,
            emptyHint = "За период нет ни одной ячейки доходов",
            currency = state.currency,
            showMean = false,
        )
    }

    ExpenseTrendCard(rows = state.expenseTrend, hasData = state.hasExpenseTrend, currency = state.currency)

    Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Md)) {
        CashFlowCard(rows = state.cashFlow, hasData = state.hasFlowData, currency = state.currency)
        NetWorthCard(rows = state.netWorthRows, hasData = state.hasNetWorthData, currency = state.currency)
    }
}

/** Итоги периода одной строкой: пять ключевых чисел, которые сравнивают между собой. */
@Composable
private fun SummaryCard(summary: SummaryRow?) {
    FinanceCard(title = "Итоги периода") {
        if (summary == null) {
            EmptyStateCard(title = "Нет данных", hint = "Выберите период, в котором есть расходы или доходы")
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Lg),
            ) {
                Metric(label = "Доходы", value = summary.income.formatRu())
                Metric(label = "Расходы", value = summary.expense.formatRu())
                Metric(
                    label = "Дельта",
                    value = summary.delta.formatSignedRu(),
                    color = deltaColor(summary.delta.minor, favorableWhenPositive = true),
                )
                Metric(label = "Норма сбережений", value = percent(summary.savingsRatePercent))
                Metric(label = "Профицит", value = "${summary.surplusMonths} из ${summary.months} мес.")
            }
        }
    }
}

@Composable
private fun RowScope.Metric(label: String, value: String, color: Color = FinancePalette.TextPrimary) {
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(FinanceSpace.Xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = FinancePalette.TextSecondary,
        )
        Text(text = value, style = Numbers, color = color)
    }
}

/**
 * Структура раздела: кольцо и таблица (F6.1.3, F6.1.7).
 *
 * В таблице нет цветовых маркеров — они уже стоят в легенде справа, а повтор того же списка
 * читался бы как вторая легенда. Доля и сумма тоже продублированы намеренно: доля отвечает
 * на вопрос «сколько это в структуре», сумма — на вопрос «сколько это денег».
 *
 * [showMean] включает колонку среднего за месяц (F6.5). Для доходов она выключена: доход
 * нерегулярен, и среднее здесь скорее вводит в заблуждение.
 */
@Composable
private fun RowScope.StructureCard(
    title: String,
    rows: List<CategoryRow>,
    emptyHint: String,
    currency: String,
    showMean: Boolean,
) {
    FinanceCard(modifier = Modifier.weight(1f), title = title) {
        if (rows.isEmpty()) {
            EmptyStateCard(title = "Нет данных", hint = emptyHint)
        } else {
            DonutChart(
                slices = donutSlices(rows).limitSlices(),
                modifier = Modifier.fillMaxWidth(),
                currency = currency,
                size = DONUT_SIZE,
            )

            TableHeaderRow {
                HeaderCell(text = "Категория", weight = NAME_WEIGHT)
                if (showMean) HeaderCell(text = "Среднее/мес", alignEnd = true)
                HeaderCell(text = "Доля", alignEnd = true)
                HeaderCell(text = "Сумма", alignEnd = true)
            }
            // Список длинный (в расходах — десятки категорий): сначала первые 5, дальше по 5
            // по кнопке, и свернуть обратно. Диаграмма выше показывает всю структуру сразу.
            var visible by remember(title) { mutableStateOf(STRUCTURE_PAGE) }
            val shown = rows.take(visible)
            shown.forEachIndexed { index, row ->
                TableRow(alternate = index % 2 == 1, showDivider = index != shown.lastIndex) {
                    TextCell(text = row.name, weight = NAME_WEIGHT)
                    if (showMean) MoneyCell(money = row.mean)
                    TextCell(
                        text = shareLabelRu(row.sharePercent, row.amount),
                        alignEnd = true,
                        color = FinancePalette.TextSecondary,
                    )
                    MoneyCell(money = row.amount, color = moneyColor(row.amount))
                }
            }
            if (rows.size > STRUCTURE_PAGE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Показано ${shown.size} из ${rows.size}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = FinancePalette.TextSecondary,
                    )
                    if (visible > STRUCTURE_PAGE) {
                        QuietButton(text = "Скрыть", onClick = { visible = STRUCTURE_PAGE })
                    }
                    if (shown.size < rows.size) {
                        QuietButton(
                            text = "Показать ещё ${minOf(STRUCTURE_PAGE, rows.size - shown.size)}",
                            onClick = { visible += STRUCTURE_PAGE },
                        )
                    }
                }
            }
        }
    }
}

/** Динамика расходов по категориям: стек и линия итога поверх него (F6.1.1). */
@Composable
private fun ExpenseTrendCard(rows: List<CategoryTrendRow>, hasData: Boolean, currency: String) {
    // Фильтр по одной категории (F6.1.2): нажатие в легенде оставляет только её, повторное
    // нажатие или «Все категории» возвращает общий вид. Выбор живёт в экране, а не в
    // контроллере: это вид графика, а не данные.
    var selectedId by remember { mutableStateOf<String?>(null) }
    val all = categorySeries(rows)
    val selected = all.firstOrNull { it.id == selectedId }
    if (selectedId != null && selected == null) selectedId = null

    FinanceCard(
        title = if (selected == null) "Расходы по месяцам" else "Расходы по месяцам: ${selected.title}",
        subtitle = if (selected == null) {
            "Столбцы — категории, линия поверх — итог месяца. Нажмите на категорию в легенде, чтобы увидеть только её"
        } else {
            "Динамика одной категории. Нажмите на неё ещё раз или на «Все категории»"
        },
        actions = if (selected == null) null else {
            { QuietButton(text = "Все категории", onClick = { selectedId = null }) }
        },
    ) {
        if (!hasData) {
            EmptyStateCard(title = "Нет данных", hint = "За период нет ни одной ячейки расходов")
        } else {
            StackedBarChart(
                series = if (selected == null) all else listOf(selected),
                modifier = Modifier.fillMaxWidth(),
                currency = currency,
                height = CHART_HEIGHT,
                // У одной категории линия итога совпала бы с верхом столбцов.
                showTotalLine = selected == null,
                legendItems = all,
                selectedSeriesId = selected?.id,
                onLegendClick = { id -> selectedId = if (selectedId == id) null else id },
            )
        }
    }
}

/** Доходы против расходов и дельта по месяцам (F6.1.5). */
@Composable
private fun RowScope.CashFlowCard(rows: List<CashFlowRow>, hasData: Boolean, currency: String) {
    FinanceCard(modifier = Modifier.weight(1f), title = "Cash flow по месяцам") {
        if (!hasData) {
            EmptyStateCard(title = "Нет данных", hint = "Введите расходы или доходы за период")
        } else {
            // Группированные столбцы: дельта уходит ниже нуля, поэтому дефицит виден сразу.
            GroupedBarChart(
                series = cashFlowSeries(rows),
                modifier = Modifier.fillMaxWidth(),
                currency = currency,
                height = CHART_HEIGHT,
            )

            TableHeaderRow {
                HeaderCell(text = "Месяц")
                HeaderCell(text = "Доходы", alignEnd = true)
                HeaderCell(text = "Расходы", alignEnd = true)
                HeaderCell(text = "Дельта", alignEnd = true)
                HeaderCell(text = "Норма", alignEnd = true)
            }
            rows.forEachIndexed { index, row ->
                TableRow(alternate = index % 2 == 1, showDivider = index != rows.lastIndex) {
                    TextCell(text = row.period.titleRu())
                    MoneyCell(money = row.income)
                    MoneyCell(money = row.expense)
                    MoneyCell(
                        money = row.delta,
                        signed = true,
                        color = deltaColor(row.delta.minor, favorableWhenPositive = true),
                    )
                    TextCell(
                        text = percent(row.savingsRatePercent),
                        alignEnd = true,
                        color = FinancePalette.TextSecondary,
                    )
                }
            }
        }
    }
}

/**
 * Динамика капитала (F6.1.6): линия с заливкой поверх столбцов взносов и прироста.
 *
 * Месяц без введённых остатков — прочерк во всех колонках и разрыв линии: пропуск не равен
 * нулю (D-15). Подпись под таблицей объясняет это явно, иначе прочерк читают как ошибку.
 */
@Composable
private fun RowScope.NetWorthCard(rows: List<NetWorthRow>, hasData: Boolean, currency: String) {
    FinanceCard(modifier = Modifier.weight(1f), title = "Net Worth по месяцам") {
        if (!hasData) {
            EmptyStateCard(
                title = "Нет остатков",
                hint = "Введите остатки счетов в разделе «Активы» — капитал считается по ним",
            )
        } else {
            ComboChart(
                line = netWorthLine(rows),
                bars = netWorthBars(rows),
                modifier = Modifier.fillMaxWidth(),
                currency = currency,
                height = CHART_HEIGHT,
            )

            TableHeaderRow {
                HeaderCell(text = "Месяц")
                HeaderCell(text = "Капитал", alignEnd = true)
                HeaderCell(text = "Взносы", alignEnd = true)
                HeaderCell(text = "Прирост", alignEnd = true)
            }
            rows.forEachIndexed { index, row ->
                TableRow(alternate = index % 2 == 1, showDivider = index != rows.lastIndex) {
                    TextCell(text = row.period.titleRu())
                    MoneyCell(money = row.netWorth, color = row.netWorth?.let { moneyColor(it) } ?: FinancePalette.TextDisabled)
                    MoneyCell(money = row.contributions, signed = true)
                    MoneyCell(money = row.gain, signed = true, color = row.gain?.let { moneyColor(it) } ?: FinancePalette.TextDisabled)
                }
            }
            Text(
                text = "Прочерк — месяц без остатков: это пропуск, а не ноль",
                style = MaterialTheme.typography.labelSmall,
                color = FinancePalette.TextSecondary,
            )
        }
    }
}

/**
 * MoM/YoY (F6.5). «Неизвестно» показывается прочерком: сравнивать не с чем, и выдумывать
 * ноль здесь нельзя.
 */
@Composable
private fun ChangeText(label: String, change: Change?, favorableWhenPositive: Boolean) {
    val absolute = change?.absolute
    if (change == null || change.isUnknown || absolute == null) {
        Text(
            text = "$label: —",
            style = MaterialTheme.typography.labelMedium,
            color = FinancePalette.TextSecondary,
        )
        return
    }

    val percent = change.percent
    Text(
        text = "$label: ${absolute.formatSignedRu()}" +
            if (percent != null) " (${percent.signumLabel()}$percent %)" else "",
        style = MaterialTheme.typography.labelMedium,
        color = deltaColor(absolute.minor, favorableWhenPositive),
    )
}

/** Отрицательные суммы подсвечиваются цветом ошибки; ноль остаётся обычным текстом. */
private fun moneyColor(money: Money): Color =
    if (money.minor < 0L) FinancePalette.Error else FinancePalette.TextPrimary

/** Процент в тексте уже несёт знак числа, поэтому отдельный «+» добавляется только росту. */
private fun Int.signumLabel(): String = if (this > 0) "+" else ""

/** Сколько строк таблицы структуры показывается сразу и добавляется кнопкой «Показать ещё». */
private const val STRUCTURE_PAGE = 5

private fun percent(value: Int?): String = value?.let { "$it %" } ?: "—"

/** Метрики экрана: единая высота графиков и размер кольца, чтобы карточки не «прыгали». */
private val CHART_HEIGHT: Dp = 240.dp
private val DONUT_SIZE: Dp = 168.dp

/** Вес колонки названия: она длиннее денежных и должна забирать больше места. */
private const val NAME_WEIGHT = 2f
