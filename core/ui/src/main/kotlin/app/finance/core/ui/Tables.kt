package app.finance.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.finance.core.model.Money

/**
 * Каркас страниц: карточки, заголовки разделов и таблицы.
 *
 * Таблицы собраны из ячеек с весом, а не из фиксированных колонок: разделы разные, но ритм
 * один — строка 48px, заголовки столбцов `Caption`, деньги справа и табличными цифрами.
 * Совпадение ширины заголовка и данных обеспечивается тем, что оба используют одинаковый
 * вес в одной и той же сетке [RowScope].
 */

/**
 * Карточка раздела: радиус 12px, внутренние отступы 24px, мягкая тень.
 *
 * Граница добавлена к тени намеренно: на светлом фоне #F8F9FA одна лишь тень почти не
 * читается, и карточка сливается с полотном.
 */
@Composable
fun FinanceCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = FinanceRadius.cardShape,
        color = FinancePalette.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, FinancePalette.Divider),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(FinanceSpace.Lg),
            verticalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
        ) {
            if (title != null || actions != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (title != null) {
                            Text(text = title, style = MaterialTheme.typography.titleMedium)
                        }
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = FinancePalette.TextSecondary,
                            )
                        }
                    }
                    if (actions != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) { actions() }
                    }
                }
            }
            content()
        }
    }
}

/** Заголовок раздела: H1 слева, действия справа, подпись под заголовком. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = FinancePalette.TextSecondary,
                )
            }
        }
        if (actions != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
    }
}

/**
 * Строка заголовка таблицы.
 *
 * Высота меньше строки данных (40px против 48px): заголовок — служебный элемент, и он не
 * должен спорить с числами. Цвет подписей приглушённый, стиль единый для всех разделов.
 */
@Composable
fun TableHeaderRow(modifier: Modifier = Modifier, cells: @Composable RowScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(FinanceSize.ControlHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cells()
        }
        HorizontalDivider(color = FinancePalette.Border, thickness = 1.dp)
    }
}

/**
 * Строка таблицы: 48px, разделитель снизу и подсветка при наведении.
 *
 * [alternate] задаёт едва заметный фон чётных строк — на длинных таблицах это помогает
 * вести взгляд по строке, не добавляя линий. [onClick] делает строку кликабельной
 * (drill-down); hover-подсветка при этом остаётся, чтобы было видно, что строка активна.
 */
@Composable
fun TableRow(
    modifier: Modifier = Modifier,
    alternate: Boolean = false,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    cells: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = when {
            hovered -> FinancePalette.RowHover
            alternate -> FinancePalette.RowAlternate
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = FinanceMotion.HOVER_MS, easing = FastOutSlowInEasing),
        label = "tableRowHover",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FinanceSize.TableRowHeight)
                .background(background)
                .then(
                    if (onClick == null) {
                        Modifier
                    } else {
                        Modifier.clickableNoRipple(interactionSource = interactionSource, onClick = onClick)
                    }
                )
                .padding(horizontal = FinanceSpace.Md, vertical = FinanceSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cells()
        }
        if (showDivider) {
            HorizontalDivider(color = FinancePalette.Divider, thickness = 1.dp)
        }
    }
}

/** Ячейка заголовка столбца. `alignEnd` — для денежных колонок. */
@Composable
fun RowScope.HeaderCell(text: String, weight: Float = 1f, alignEnd: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.weight(weight).padding(horizontal = FinanceSpace.Xs),
        style = MaterialTheme.typography.labelSmall,
        color = FinancePalette.TextSecondary,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Текстовая ячейка. */
@Composable
fun RowScope.TextCell(
    text: String,
    weight: Float = 1f,
    alignEnd: Boolean = false,
    strong: Boolean = false,
    color: Color = FinancePalette.TextPrimary,
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight).padding(horizontal = FinanceSpace.Xs),
        style = if (strong) Numbers else MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Денежная ячейка: всегда справа и табличными цифрами.
 *
 * `null` показывается прочерком, а не нулём: отсутствие значения (например, капитал за
 * месяц без остатков) и ноль — разные факты, и подменять один другим нельзя (SPEC.md §5).
 */
@Composable
fun RowScope.MoneyCell(
    money: Money?,
    weight: Float = 1f,
    signed: Boolean = false,
    color: Color = FinancePalette.TextPrimary,
) {
    if (money == null) {
        Text(
            text = "—",
            modifier = Modifier.weight(weight).padding(horizontal = FinanceSpace.Xs),
            style = Numbers,
            color = FinancePalette.TextDisabled,
            textAlign = TextAlign.End,
        )
    } else {
        Text(
            text = if (signed) money.formatSignedRu() else money.formatRu(),
            modifier = Modifier.weight(weight).padding(horizontal = FinanceSpace.Xs),
            style = Numbers,
            color = color,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

/**
 * Дельта к прошлому периоду: «▲ 8 %», «▼ 3 %» или прочерк.
 *
 * Стрелка обязательна: цвет сам по себе не несёт смысла для части пользователей, а знак
 * числа не говорит, хорошо это или плохо. Семантику задаёт [favorableWhenPositive] —
 * для расходов рост плох, для доходов хорош.
 */
@Composable
fun DeltaText(
    percent: Int?,
    favorableWhenPositive: Boolean,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.End,
) {
    val text = when {
        percent == null -> "—"
        percent > 0 -> "▲ $percent %"
        percent < 0 -> "▼ ${-percent} %"
        else -> "0 %"
    }
    val color = if (percent == null) {
        FinancePalette.TextDisabled
    } else {
        deltaColor(deltaMinor = percent.toLong(), favorableWhenPositive = favorableWhenPositive)
    }

    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        textAlign = textAlign,
        maxLines = 1,
    )
}

/** Ячейка с дельтой, выровненная как остальные денежные колонки. */
@Composable
fun RowScope.DeltaCell(percent: Int?, favorableWhenPositive: Boolean, weight: Float = 1f) {
    Box(
        modifier = Modifier.weight(weight).padding(horizontal = FinanceSpace.Xs),
        contentAlignment = Alignment.CenterEnd,
    ) {
        DeltaText(percent = percent, favorableWhenPositive = favorableWhenPositive)
    }
}

/**
 * Пустое состояние с действием.
 *
 * Пустой экран обязан объяснять, что делать дальше: «нет данных» без кнопки оставляет
 * пользователя в тупике, особенно в разделе, куда он зашёл впервые.
 */
@Composable
fun EmptyStateCard(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = FinanceSpace.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FinanceSpace.Sm),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = FinancePalette.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(FinanceSpace.Xs))
            SecondaryButton(text = actionLabel, onClick = onAction)
        }
    }
}
