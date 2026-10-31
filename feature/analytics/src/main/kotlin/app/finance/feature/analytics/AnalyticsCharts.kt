package app.finance.feature.analytics

import app.finance.core.charts.ChartPoint
import app.finance.core.charts.ChartSeries
import app.finance.core.charts.DonutSlice
import app.finance.core.ui.ChartPalette
import app.finance.core.ui.chartLabelRu

/**
 * Преобразование строк состояния экрана в данные графиков (F6.1).
 *
 * Функции чистые и не зависят от Compose: `core:charts` принимает готовые [ChartSeries] и
 * [DonutSlice], а цвета передаются HEX-строками — модель графиков намеренно не тянет
 * Compose-типы. Пропуски сохраняются как `valueMinor == null`, поэтому линия в этом месте
 * рвётся, а не падает в ноль (D-15).
 *
 * Подписи оси X — `Period.chartLabelRu()` («Фев 2026»): название месяца читается быстрее
 * цифр, а год в подписи сохраняет однозначность для диапазона длиннее года.
 *
 * Цвета семантические, а не произвольные: доходы и взносы — зелёный, расходы — красный,
 * капитал и дельта — акцентный синий, прирост — жёлтый. Все взяты из палитры, безопасной
 * для дейтеранопии, и различаются ещё и светлотой, поэтому график читается без цвета.
 */

/**
 * Серии для сравнения доходов, расходов и дельты по месяцам (F6.1.5).
 *
 * Дельта показана отдельной серией: она бывает отрицательной, и на общей оси дефицит
 * сразу видно — столбцы уходят ниже нуля.
 */
internal fun cashFlowSeries(rows: List<CashFlowRow>): List<ChartSeries> = listOf(
    ChartSeries(
        id = "income",
        title = "Доходы",
        colorHex = ChartPalette.SUCCESS_HEX,
        points = rows.map { ChartPoint(label = it.period.chartLabelRu(), valueMinor = it.income.minor) },
    ),
    ChartSeries(
        id = "expense",
        title = "Расходы",
        colorHex = ChartPalette.ERROR_HEX,
        points = rows.map { ChartPoint(label = it.period.chartLabelRu(), valueMinor = it.expense.minor) },
    ),
    ChartSeries(
        id = "delta",
        title = "Дельта",
        colorHex = ChartPalette.PRIMARY_HEX,
        points = rows.map { ChartPoint(label = it.period.chartLabelRu(), valueMinor = it.delta.minor) },
    ),
)

/**
 * Линия капитала для комбинированного графика (F6.1.6).
 *
 * Месяц без остатков даёт `null` — линия рвётся, потому что отсутствие введённого остатка
 * не означает отсутствие капитала (SPEC.md §5).
 */
internal fun netWorthLine(rows: List<NetWorthRow>): ChartSeries = ChartSeries(
    id = "netWorth",
    title = "Капитал",
    colorHex = ChartPalette.PRIMARY_HEX,
    points = rows.map { ChartPoint(label = it.period.chartLabelRu(), valueMinor = it.netWorth?.minor) },
)

/**
 * Столбцы под линией капитала: что именно изменилось за месяц.
 *
 * Прирост бывает отрицательным и рисуется вниз от нуля — просадка видна, а не спрятана
 * за столбцами взносов.
 */
internal fun netWorthBars(rows: List<NetWorthRow>): List<ChartSeries> = listOf(
    ChartSeries(
        id = "contributions",
        title = "Взносы",
        colorHex = ChartPalette.SUCCESS_HEX,
        points = rows.map { ChartPoint(label = it.period.chartLabelRu(), valueMinor = it.contributions?.minor) },
    ),
    ChartSeries(
        id = "gain",
        title = "Прирост",
        colorHex = ChartPalette.WARNING_HEX,
        points = rows.map { ChartPoint(label = it.period.chartLabelRu(), valueMinor = it.gain?.minor) },
    ),
)

/** Срезы круговой диаграммы из структуры раздела (F6.1.3). */
internal fun donutSlices(rows: List<CategoryRow>): List<DonutSlice> =
    rows.map { row -> DonutSlice(label = row.name, colorHex = row.color, valueMinor = row.amount.minor) }

/**
 * Серии stacked bar по категориям (F6.1.1).
 *
 * Категории уже отсортированы контроллером по убыванию итога, поэтому порядок стека
 * стабилен между периодами: крупные категории всегда внизу.
 */
internal fun categorySeries(rows: List<CategoryTrendRow>): List<ChartSeries> = rows.map { row ->
    ChartSeries(
        id = row.categoryId,
        title = row.name,
        colorHex = row.color,
        points = row.points.map { ChartPoint(label = it.period.chartLabelRu(), valueMinor = it.amount?.minor) },
    )
}
