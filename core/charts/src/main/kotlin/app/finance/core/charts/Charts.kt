package app.finance.core.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.finance.core.model.Money
import app.finance.core.ui.FinancePalette
import app.finance.core.ui.FinanceRadius
import app.finance.core.ui.FinanceSize
import app.finance.core.ui.FinanceSpace
import app.finance.core.ui.deltaPercent
import app.finance.core.ui.formatRu
import app.finance.core.ui.parseHexColor
import app.finance.core.ui.sharePercent
import androidx.compose.ui.geometry.Size
import kotlin.math.abs

/**
 * Графики на Canvas (SPEC.md §4, F6.1).
 *
 * Компоненты «глупые»: принимают готовые [ChartSeries]/[DonutSlice], а не выбирают данные и
 * не считают доли — расчёты живут в `core:analytics` (F6.4). Подписи оси X передаёт
 * вызывающий слой, поэтому графики не знают про `Period`.
 *
 * Точки, столбцы и подписи оси X привязаны к одним и тем же «слотам»: центр слота —
 * единственная точка отсчёта и для данных, и для подписи, и для курсора. Раньше линия
 * шла от края до края, а подписи центрировались по слотам, из-за чего месяц под точкой
 * и месяц в подписи расходились на полшага.
 *
 * Текст рисуется не внутри `DrawScope`, а обычными `Text` вокруг Canvas: измерение текста
 * в Skia потребовало бы `TextMeasurer` и ручного расклада, а колонки Compose дают то же
 * самое бесплатно и корректно реагируют на масштабирование шрифтов.
 */

/**
 * Линейный график с поддержкой пропусков.
 *
 * `valueMinor == null` разрывает линию, а каждый сегмент рисуется со своими маркерами —
 * иначе одиночный месяц без соседей был бы невидим (SPEC.md §5: пропуск активов — gap,
 * не ноль).
 *
 * [fillArea] добавляет полупрозрачную заливку под линией: тренд читается раньше, чем
 * конкретные значения. Ноль в диапазон линии не добавляется — шкала строится по фактическому
 * разбросу с запасом, иначе колебания капитала в пределах 5 % выглядели бы прямой у верха.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    currency: String = Money.DEFAULT_CURRENCY,
    height: Dp = DEFAULT_CHART_HEIGHT,
    showLegend: Boolean = true,
    fillArea: Boolean = false,
) {
    val scale = VerticalScale.of(series, includeZero = false)
    if (scale == null) {
        EmptyChart(hint = NO_DATA_HINT, modifier = modifier, height = height)
        return
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.outline

    ChartFrame(
        scale = scale,
        labels = labelsOf(series),
        legend = series,
        showLegend = showLegend,
        currency = currency,
        height = height,
        modifier = modifier,
    ) { plotModifier ->
        Canvas(modifier = plotModifier) {
            drawGrid(scale = scale, gridColor = gridColor, zeroColor = zeroColor)
            series.forEach { one ->
                drawSeriesLine(
                    series = one,
                    scale = scale,
                    color = parseHexColor(one.colorHex),
                    fillArea = fillArea,
                )
            }
        }
    }
}

/**
 * Линия поверх столбцов с накоплением — основной вид для Net Worth (F6.1.6).
 *
 * Столбцы показывают, из чего сложилось изменение (взносы и прирост), а линия — итоговый
 * капитал. Разделить их по разным графикам нельзя: смысл именно в сопоставлении «что
 * добавили» и «что стало».
 *
 * Шкала общая и включает ноль: столбцы без базовой линии повисли бы в воздухе.
 */
@Composable
fun ComboChart(
    line: ChartSeries,
    bars: List<ChartSeries>,
    modifier: Modifier = Modifier,
    currency: String = Money.DEFAULT_CURRENCY,
    height: Dp = DEFAULT_CHART_HEIGHT,
    showLegend: Boolean = true,
    fillArea: Boolean = true,
) {
    val scale = VerticalScale.of(bars + line, includeZero = true)
    if (scale == null) {
        EmptyChart(hint = NO_DATA_HINT, modifier = modifier, height = height)
        return
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.outline

    ChartFrame(
        scale = scale,
        labels = labelsOf(bars + line),
        legend = bars + line,
        showLegend = showLegend,
        currency = currency,
        height = height,
        modifier = modifier,
    ) { plotModifier ->
        Canvas(modifier = plotModifier) {
            drawGrid(scale = scale, gridColor = gridColor, zeroColor = zeroColor)
            drawStackedBars(series = bars, scale = scale)
            drawSeriesLine(
                series = line,
                scale = scale,
                color = parseHexColor(line.colorHex),
                fillArea = fillArea,
                drawLast = true,
            )
        }
    }
}

/**
 * Столбцы рядом друг с другом — для сравнения серий в одном периоде
 * (например, доходы против расходов, F6.1.5).
 *
 * Отрицательные значения рисуются вниз от нулевой линии, поэтому дельта cash flow видна
 * без отдельных ухищрений.
 */
@Composable
fun GroupedBarChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    currency: String = Money.DEFAULT_CURRENCY,
    height: Dp = DEFAULT_CHART_HEIGHT,
    showLegend: Boolean = true,
) {
    val scale = VerticalScale.of(series)
    if (scale == null) {
        EmptyChart(hint = NO_DATA_HINT, modifier = modifier, height = height)
        return
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.outline

    ChartFrame(
        scale = scale,
        labels = labelsOf(series),
        legend = series,
        showLegend = showLegend,
        currency = currency,
        height = height,
        modifier = modifier,
    ) { plotModifier ->
        Canvas(modifier = plotModifier) {
            drawGrid(scale = scale, gridColor = gridColor, zeroColor = zeroColor)
            drawGroupedBars(series = series, scale = scale)
        }
    }
}

/**
 * Столбцы с накоплением — структура периода по категориям (F6.1.1, F6.1.6).
 *
 * Накопление раздельное по знаку: положительные значения складываются вверх от нуля,
 * отрицательные — вниз. Иначе кредитный баланс «съел» бы соседние категории и сумма
 * сегментов перестала бы совпадать с итогом месяца.
 *
 * [showTotalLine] рисует поверх столбцов линию итога: по высоте стека трудно оценить
 * суммарное движение, а линия делает его сравнимым между месяцами.
 */
@Composable
fun StackedBarChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    currency: String = Money.DEFAULT_CURRENCY,
    height: Dp = DEFAULT_CHART_HEIGHT,
    showLegend: Boolean = true,
    showTotalLine: Boolean = false,
    /** Пункты легенды. Отличаются от [series], когда график отфильтрован по одной серии. */
    legendItems: List<ChartSeries> = series,
    /** Выбранная серия: остальные пункты легенды приглушаются. */
    selectedSeriesId: String? = null,
    /** Нажатие на пункт легенды; null — легенда не кликабельна. */
    onLegendClick: ((String) -> Unit)? = null,
) {
    val scale = VerticalScale.ofStacked(series)
    if (scale == null) {
        EmptyChart(hint = NO_DATA_HINT, modifier = modifier, height = height)
        return
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.outline

    ChartFrame(
        scale = scale,
        labels = labelsOf(series),
        legend = series,
        showLegend = showLegend,
        currency = currency,
        height = height,
        modifier = modifier,
        legendItems = legendItems,
        selectedSeriesId = selectedSeriesId,
        onLegendClick = onLegendClick,
        // Категорий в столбце много: в подсказке крупные сверху, иначе их не найти.
        sortTooltipByValue = true,
    ) { plotModifier ->
        Canvas(modifier = plotModifier) {
            drawGrid(scale = scale, gridColor = gridColor, zeroColor = zeroColor)
            drawStackedBars(series = series, scale = scale)
            if (showTotalLine) {
                drawTotalLine(series = series, scale = scale)
            }
        }
    }
}

/**
 * Кольцевая диаграмма структуры (F6.1.3).
 *
 * Легенда только справа и сразу с долей и суммой: второй легенды (нижней) нет намеренно —
 * дублирование уводит взгляд и занимает место. Центр кольца пустой: итог показан в шапке
 * раздела, а число внутри кольца читалось бы как ещё одна категория.
 *
 * Секторы с неположительным значением отбрасываются: доля от отрицательной суммы не
 * интерпретируется, а рисовать «вывернутый» сектор значит обманывать глаз.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    currency: String = Money.DEFAULT_CURRENCY,
    size: Dp = DEFAULT_DONUT_SIZE,
    thickness: Dp = DEFAULT_DONUT_THICKNESS,
) {
    val visible = slices.filter { it.valueMinor > 0L }
    if (visible.isEmpty()) {
        EmptyChart(hint = NO_DATA_HINT, modifier = modifier, height = size)
        return
    }
    val total = visible.sumOf { it.valueMinor }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val strokeWidth = thickness.toPx()
            val arcSize = Size(width = this.size.width - strokeWidth, height = this.size.height - strokeWidth)
            val topLeft = Offset(x = strokeWidth / 2f, y = strokeWidth / 2f)
            // Подложка кольца: видна, если секторы не покрыли круг целиком.
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = FULL_CIRCLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth),
            )
            var startAngle = START_ANGLE
            visible.forEach { slice ->
                // Угол — единственная величина, где деньги становятся Float: это геометрия,
                // а не арифметика сумм (SPEC.md §5).
                val sweep = if (total == 0L) 0f else slice.valueMinor.toFloat() / total.toFloat() * FULL_CIRCLE
                drawArc(
                    color = parseHexColor(slice.colorHex),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth),
                )
                startAngle += sweep
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FinanceSpace.Sm),
        ) {
            visible.forEach { slice ->
                LegendRow(
                    colorHex = slice.colorHex,
                    label = slice.label,
                    value = Money(slice.valueMinor, currency).formatRu(),
                    share = percentOf(partMinor = slice.valueMinor, totalMinor = total, currency = currency),
                )
            }
        }
    }
}

/** Заполнитель, когда данных нет: честное сообщение вместо пустых осей. */
@Composable
fun EmptyChart(hint: String, modifier: Modifier = Modifier, height: Dp = DEFAULT_CHART_HEIGHT) {
    Box(
        modifier = modifier.fillMaxWidth().height(height),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = FinancePalette.TextSecondary,
        )
    }
}

// ── Обвязка: оси, легенда, hover ─────────────────────────────────────────────

/**
 * Общий каркас графика: подписи Y слева, область отрисовки справа, подписи X снизу
 * и легенда, если серий больше одной.
 *
 * Область отрисовки одновременно служит зоной наведения: тултип и вертикальный маркер
 * живут здесь же, поэтому не разъезжаются с данными при изменении размера окна.
 */
@Composable
private fun ChartFrame(
    scale: VerticalScale,
    labels: List<String>,
    legend: List<ChartSeries>,
    showLegend: Boolean,
    currency: String,
    height: Dp,
    modifier: Modifier = Modifier,
    legendItems: List<ChartSeries> = legend,
    selectedSeriesId: String? = null,
    onLegendClick: ((String) -> Unit)? = null,
    sortTooltipByValue: Boolean = false,
    plot: @Composable (Modifier) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().height(height)) {
            YAxisLabels(
                scale = scale,
                currency = currency,
                modifier = Modifier.width(Y_AXIS_WIDTH).fillMaxHeight(),
            )
            Spacer(modifier = Modifier.width(FinanceSpace.Sm))
            HoverablePlot(
                labels = labels,
                series = legend,
                currency = currency,
                sortTooltipByValue = sortTooltipByValue,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                plot = plot,
            )
        }
        Spacer(modifier = Modifier.height(FinanceSpace.Xs))
        XAxisLabels(labels = thinLabels(labels, MAX_X_LABELS))
        if (showLegend && legendItems.size > 1) {
            Spacer(modifier = Modifier.height(FinanceSpace.Sm))
            Legend(series = legendItems, selectedId = selectedSeriesId, onClick = onLegendClick)
        }
    }
}

/**
 * Область графика с наведением.
 *
 * Тултип показывает период, значение каждой серии и изменение к предыдущей точке. Без
 * него график отвечает только на вопрос «какая форма», а не «какое число», и пользователю
 * приходится искать значение в таблице ниже.
 */
@Composable
private fun HoverablePlot(
    labels: List<String>,
    series: List<ChartSeries>,
    currency: String,
    sortTooltipByValue: Boolean = false,
    modifier: Modifier = Modifier,
    plot: @Composable (Modifier) -> Unit,
) {
    var hoverIndex by remember(labels.size) { mutableStateOf<Int?>(null) }
    var plotWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val tooltipWidthPx = with(density) { TOOLTIP_WIDTH.toPx() }

    Box(
        modifier = modifier
            .onSizeChanged { plotWidthPx = it.width }
            .pointerInput(labels.size, plotWidthPx) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Exit || labels.isEmpty()) {
                            hoverIndex = null
                            continue
                        }
                        val change = event.changes.firstOrNull() ?: continue
                        hoverIndex = slotIndexAt(
                            x = change.position.x,
                            widthPx = size.width.toFloat(),
                            count = labels.size,
                        )
                    }
                }
            },
    ) {
        plot(Modifier.fillMaxSize())

        val index = hoverIndex
        if (index != null && plotWidthPx > 0) {
            // Вертикальный маркер: без него непонятно, к какому именно месяцу относится тултип.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val x = centerOf(index = index, count = labels.size, widthPx = size.width)
                drawLine(
                    color = CROSSHAIR_COLOR,
                    start = Offset(x = x, y = 0f),
                    end = Offset(x = x, y = size.height),
                    strokeWidth = CROSSHAIR_STROKE_WIDTH,
                )
            }

            // Тултип держится правее маркера, но не вылезает за область графика:
            // на узком окне он иначе ушёл бы под обрез.
            val centerX = centerOf(index = index, count = labels.size, widthPx = plotWidthPx.toFloat())
            val offsetX = (centerX + TOOLTIP_GAP_PX).coerceIn(
                minimumValue = 0f,
                maximumValue = (plotWidthPx - tooltipWidthPx).coerceAtLeast(0f),
            )

            ChartTooltip(
                label = labels[index],
                // Сортировка по значению месяца под курсором: от большего к меньшему, пустые в конце.
                series = if (sortTooltipByValue) {
                    series.sortedWith(
                        compareByDescending<ChartSeries> { it.points.getOrNull(index)?.valueMinor != null }
                            .thenByDescending { it.points.getOrNull(index)?.valueMinor ?: 0L },
                    )
                } else {
                    series
                },
                index = index,
                currency = currency,
                modifier = Modifier
                    .offset { IntOffset(x = offsetX.toInt(), y = 0) }
                    .width(TOOLTIP_WIDTH),
            )
        }
    }
}

/** Карточка тултипа: период, затем по строке на серию со значением и дельтой. */
@Composable
private fun ChartTooltip(
    label: String,
    series: List<ChartSeries>,
    index: Int,
    currency: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = FinanceRadius.controlShape,
        color = FinancePalette.Background,
        border = androidx.compose.foundation.BorderStroke(1.dp, FinancePalette.Border),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(FinanceSpace.Md),
            verticalArrangement = Arrangement.spacedBy(FinanceSpace.Xs),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            // Подсказка ограничена высотой графика: длинный список обрезался. Показываем первые
            // строки, остальное — одной строкой «ещё N» с суммой.
            val shown = if (series.size > MAX_TOOLTIP_ROWS) series.take(MAX_TOOLTIP_ROWS - 1) else series
            val rest = series.drop(shown.size)
            shown.forEach { one ->
                val value = one.points.getOrNull(index)?.valueMinor
                val previous = one.points.getOrNull(index - 1)?.valueMinor
                val percent = if (value != null && previous != null) {
                    deltaPercent(Money(value, currency), Money(previous, currency))
                } else {
                    null
                }
                LegendRow(
                    colorHex = one.colorHex,
                    label = one.title,
                    value = value?.let { Money(it, currency).formatRu() } ?: "—",
                    share = percent?.let { "${if (it > 0) "+" else ""}$it %" } ?: "",
                )
            }
            if (rest.isNotEmpty()) {
                val restTotal = rest.sumOf { it.points.getOrNull(index)?.valueMinor ?: 0L }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ещё ${rest.size}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = FinancePalette.TextSecondary,
                    )
                    Text(
                        text = Money(restTotal, currency).formatRu(),
                        style = MaterialTheme.typography.bodySmall,
                        color = FinancePalette.TextSecondary,
                    )
                }
            }
        }
    }
}

/** Сколько строк помещается в подсказку по высоте графика (вместе со строкой «ещё N»). */
private const val MAX_TOOLTIP_ROWS = 8

/** Строка легенды и тултипа: маркер, название, значение и доля/дельта справа. */
@Composable
private fun LegendRow(colorHex: String, label: String, value: String, share: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(FinanceSize.LegendDot)
                .background(color = parseHexColor(colorHex), shape = CircleShape),
        )
        Spacer(modifier = Modifier.width(FinanceSpace.Sm))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(FinanceSpace.Sm))
        if (share.isNotEmpty()) {
            Text(
                text = share,
                style = MaterialTheme.typography.bodySmall,
                color = FinancePalette.TextSecondary,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(FinanceSpace.Sm))
        }
        Text(
            text = value,
            style = app.finance.core.ui.Numbers,
            maxLines = 1,
        )
    }
}

@Composable
private fun YAxisLabels(scale: VerticalScale, currency: String, modifier: Modifier = Modifier) {
    // Подписи идут сверху вниз: старшая граница шкалы — вверху графика.
    Column(modifier = modifier, verticalArrangement = Arrangement.SpaceBetween) {
        scale.ticks.reversed().forEach { tick ->
            Text(
                text = Money(tick, currency).formatRu(),
                style = MaterialTheme.typography.labelSmall,
                color = FinancePalette.TextSecondary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun XAxisLabels(labels: List<String>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        // Отступ на ширину подписей оси Y: слоты подписей должны совпадать со слотами
        // столбцов, а область графика начинается правее оси (иначе подписи съезжали влево).
        Spacer(modifier = Modifier.width(Y_AXIS_WIDTH + FinanceSpace.Sm))
        labels.forEach { label ->
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = FinancePalette.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Legend(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    onClick: ((String) -> Unit)? = null,
) {
    // Перенос по строкам: в одну строку десятки категорий не помещались и обрезались.
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
        verticalArrangement = Arrangement.spacedBy(FinanceSpace.Xs),
    ) {
        series.forEach { one ->
            val dimmed = selectedId != null && selectedId != one.id
            Row(
                modifier = Modifier
                    .alpha(if (dimmed) DIMMED_ALPHA else 1f)
                    .then(
                        if (onClick == null) Modifier else Modifier.clickable { onClick(one.id) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Xs + 2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(FinanceSize.LegendDot)
                        .background(color = parseHexColor(one.colorHex), shape = CircleShape),
                )
                Text(
                    text = one.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selectedId == one.id) FinancePalette.TextPrimary else FinancePalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Прозрачность невыбранных пунктов легенды при фильтре по одной серии. */
private const val DIMMED_ALPHA = 0.4f

// ── Отрисовка ────────────────────────────────────────────────────────────────

private fun DrawScope.drawGrid(scale: VerticalScale, gridColor: Color, zeroColor: Color) {
    scale.ticks.forEach { tick ->
        val y = size.height * (1f - scale.fractionOf(tick))
        drawLine(
            color = if (tick == 0L) zeroColor else gridColor,
            start = Offset(x = 0f, y = y),
            end = Offset(x = size.width, y = y),
            strokeWidth = GRID_STROKE_WIDTH,
        )
    }
}

/**
 * Линия по точкам с маркерами и, опционально, заливкой под сегментом.
 *
 * Маркер ставится на каждую точку, а не только на начало сегмента: при данных с пропусками
 * одиночный месяц иначе выглядит как точка в пустоте, и непонятно, линия это или артефакт.
 * Заливка строится по каждому непрерывному сегменту отдельно — через пропуск она не
 * переносится, иначе gap выглядел бы как сплошные данные.
 */
private fun DrawScope.drawSeriesLine(
    series: ChartSeries,
    scale: VerticalScale,
    color: Color,
    fillArea: Boolean = false,
    drawLast: Boolean = false,
) {
    val points = series.points
    if (points.isEmpty()) return

    val offsets = points.mapIndexed { index, point ->
        point.valueMinor?.let { value ->
            Offset(
                x = centerOf(index = index, count = points.size, widthPx = size.width),
                y = size.height * (1f - scale.fractionOf(value)),
            )
        }
    }

    if (fillArea) {
        val areaColor = color.copy(alpha = AREA_ALPHA)
        // Непрерывные участки идут подряд в `offsets`, поэтому достаточно группировать
        // соседние непустые значения.
        var segment = mutableListOf<Offset>()
        (offsets + null).forEach { offset ->
            if (offset == null) {
                if (segment.size >= 2) drawArea(segment = segment, color = areaColor)
                segment = mutableListOf()
            } else {
                segment.add(offset)
            }
        }
    }

    offsets.forEachIndexed { index, offset ->
        if (offset == null) return@forEachIndexed
        val isSegmentStart = index == 0 || offsets[index - 1] == null
        val isSegmentEnd = index == offsets.lastIndex || offsets[index + 1] == null
        if (!isSegmentStart && !drawLast) {
            val start = offsets[index - 1]
            if (start != null) {
                drawLine(color = color, start = start, end = offset, strokeWidth = LINE_STROKE_WIDTH)
            }
        }
        // Одиночная точка сегмента обязана быть видимой: линия из одной точки не рисуется.
        if (isSegmentStart && isSegmentEnd) {
            drawCircle(color = color, radius = MARKER_RADIUS * 1.4f, center = offset)
        } else {
            drawCircle(color = color, radius = MARKER_RADIUS, center = offset)
        }
    }

    // Линия поверх столбцов обводится светлым контуром, иначе на пёстром стеке она теряется.
    if (drawLast) {
        offsets.filterNotNull().zipWithNext { from, to ->
            drawLine(color = Color.White, start = from, end = to, strokeWidth = LINE_STROKE_WIDTH + 2.5f)
            drawLine(color = color, start = from, end = to, strokeWidth = LINE_STROKE_WIDTH)
        }
    }
}

private fun DrawScope.drawArea(segment: List<Offset>, color: Color) {
    if (segment.size < 2) return
    val path = Path().apply {
        moveTo(x = segment.first().x, y = size.height)
        segment.forEach { lineTo(x = it.x, y = it.y) }
        lineTo(x = segment.last().x, y = size.height)
        close()
    }
    drawPath(path = path, color = color)
}

private fun DrawScope.drawGroupedBars(series: List<ChartSeries>, scale: VerticalScale) {
    val count = series.maxOfOrNull { it.points.size } ?: return
    if (count == 0) return
    val slotWidth = size.width / count
    val maxBarWidth = MAX_BAR_WIDTH.toPx()
    val groupWidth = minOf(slotWidth * GROUP_FILL, maxBarWidth * series.size)
    val barWidth = groupWidth / series.size
    val zeroY = size.height * (1f - scale.zeroFraction)

    series.forEachIndexed { seriesIndex, one ->
        val color = parseHexColor(one.colorHex)
        one.points.forEachIndexed { pointIndex, point ->
            val value = point.valueMinor ?: return@forEachIndexed
            val valueY = size.height * (1f - scale.fractionOf(value))
            val slotLeft = centerOf(index = pointIndex, count = count, widthPx = size.width) - slotWidth / 2f
            val left = slotLeft + (slotWidth - groupWidth) / 2f + seriesIndex * barWidth
            drawBar(color = color, left = left, width = barWidth * BAR_INNER_FILL, zeroY = zeroY, valueY = valueY)
        }
    }
}

private fun DrawScope.drawStackedBars(series: List<ChartSeries>, scale: VerticalScale) {
    val count = series.maxOfOrNull { it.points.size } ?: return
    if (count == 0) return
    val slotWidth = size.width / count
    // Ограничение ширины: на широком окне столбец в пол-экрана перестаёт читаться как столбец.
    val barWidth = minOf(slotWidth * GROUP_FILL, MAX_BAR_WIDTH.toPx())
    val zeroY = size.height * (1f - scale.zeroFraction)

    for (pointIndex in 0 until count) {
        val left = centerOf(index = pointIndex, count = count, widthPx = size.width) - barWidth / 2f
        var positiveAccum = 0L
        var negativeAccum = 0L
        series.forEach { one ->
            val value = one.points.getOrNull(pointIndex)?.valueMinor ?: return@forEach
            val baseAccum = if (value >= 0L) positiveAccum else negativeAccum
            // Переполнение недостижимо на денежных суммах: шкала строится по тем же величинам.
            val topAccum = baseAccum + value
            val baseY = size.height * (1f - scale.fractionOf(baseAccum))
            val topY = size.height * (1f - scale.fractionOf(topAccum))
            drawBar(color = parseHexColor(one.colorHex), left = left, width = barWidth, zeroY = baseY, valueY = topY)
            if (value >= 0L) positiveAccum = topAccum else negativeAccum = topAccum
        }
    }
}

/** Линия итога по нетто-сумме столбцов каждого месяца. */
private fun DrawScope.drawTotalLine(series: List<ChartSeries>, scale: VerticalScale) {
    val count = series.maxOfOrNull { it.points.size } ?: return
    if (count == 0) return

    val totals = (0 until count).map { index ->
        series.sumOf { one -> one.points.getOrNull(index)?.valueMinor ?: 0L }
    }
    totals.mapIndexed { index, total ->
        Offset(
            x = centerOf(index = index, count = count, widthPx = size.width),
            y = size.height * (1f - scale.fractionOf(total)),
        )
    }.zipWithNext { from, to ->
        drawLine(color = Color.White, start = from, end = to, strokeWidth = TOTAL_LINE_STROKE_WIDTH + 2f)
        drawLine(color = TOTAL_LINE_COLOR, start = from, end = to, strokeWidth = TOTAL_LINE_STROKE_WIDTH)
    }
}

/** Столбец от базовой линии к значению; нулевая величина рисуется тонкой полосой. */
private fun DrawScope.drawBar(color: Color, left: Float, width: Float, zeroY: Float, valueY: Float) {
    val top = minOf(zeroY, valueY)
    val barHeight = abs(valueY - zeroY).coerceAtLeast(MIN_BAR_HEIGHT)
    drawRect(color = color, topLeft = Offset(x = left, y = top), size = Size(width = width, height = barHeight))
}

/** Центр слота по индексу: единая формула для данных, подписей оси и наведения. */
private fun centerOf(index: Int, count: Int, widthPx: Float): Float =
    if (count <= 1) widthPx / 2f else (index + 0.5f) * (widthPx / count)

/** Индекс слота под курсором; `null`, если курсор вне области. */
private fun slotIndexAt(x: Float, widthPx: Float, count: Int): Int? {
    if (count <= 0 || widthPx <= 0f || x < 0f || x > widthPx) return null
    val slotWidth = widthPx / count
    return (x / slotWidth).toInt().coerceIn(0, count - 1)
}

/** Подписи X прореживаются, но позиции сохраняются: пустая строка занимает своё место. */
private fun thinLabels(labels: List<String>, maxLabels: Int): List<String> {
    if (labels.size <= maxLabels || maxLabels <= 0) return labels
    val stride = (labels.size + maxLabels - 1) / maxLabels
    return labels.mapIndexed { index, label ->
        if (index % stride == 0 || index == labels.lastIndex) label else ""
    }
}

private fun labelsOf(series: List<ChartSeries>): List<String> =
    series.firstOrNull { it.points.isNotEmpty() }?.points?.map { it.label } ?: emptyList()

private fun percentOf(partMinor: Long, totalMinor: Long, currency: String): String =
    sharePercent(Money(partMinor, currency), Money(totalMinor, currency))?.let { "$it %" } ?: "—"

private val DEFAULT_CHART_HEIGHT: Dp = 220.dp
private val DEFAULT_DONUT_SIZE: Dp = 160.dp
private val DEFAULT_DONUT_THICKNESS: Dp = FinanceSize.DonutThickness
private val Y_AXIS_WIDTH: Dp = 108.dp
private val TOOLTIP_WIDTH: Dp = 320.dp
private val MAX_BAR_WIDTH: Dp = FinanceSize.BarWidth
private const val NO_DATA_HINT = "Нет данных за период"
private const val MAX_X_LABELS = 8
private const val GRID_STROKE_WIDTH = 1f
private const val LINE_STROKE_WIDTH = 2f
private const val TOTAL_LINE_STROKE_WIDTH = 2f
private const val CROSSHAIR_STROKE_WIDTH = 1f
private const val MARKER_RADIUS = 3f
private const val GROUP_FILL = 0.68f
private const val BAR_INNER_FILL = 0.86f
private const val MIN_BAR_HEIGHT = 1.5f
private const val START_ANGLE = -90f
private const val FULL_CIRCLE = 360f
private const val AREA_ALPHA = 0.1f
private const val TOOLTIP_GAP_PX = 12f
private val TOTAL_LINE_COLOR = Color(0xFF202124)
private val CROSSHAIR_COLOR = Color(0xFF9AA0A6)
