package app.finance.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Светлая тема приложения. Тёмная тема в v1 не поддерживается (docs/DECISIONS.md),
 * поэтому `darkColorScheme` намеренно отсутствует.
 *
 * Значения берутся из токенов [FinancePalette], а роли Material 3 — лишь способ донести их
 * до существующих экранов: `surface` — карточки и панели, `background` — полотно окна,
 * `outline` — границы контролов, `outlineVariant` — разделители и сетка графиков.
 *
 * Контраст подобран под WCAG AA: `onSurface` (#202124) на `surface` (#F8F9FA) — 15.4:1,
 * `onSurfaceVariant` (#5F6368) — 5.9:1, `primary` (#1967D2) на белом — 5.2:1.
 */
val LightColors = lightColorScheme(
    primary = FinancePalette.Primary,
    onPrimary = Color.White,
    primaryContainer = FinancePalette.PrimaryLight,
    onPrimaryContainer = FinancePalette.Primary,
    secondary = FinancePalette.TextSecondary,
    onSecondary = Color.White,
    secondaryContainer = FinancePalette.Surface,
    onSecondaryContainer = FinancePalette.TextPrimary,
    tertiary = FinancePalette.Warning,
    onTertiary = FinancePalette.TextPrimary,
    background = FinancePalette.Background,
    onBackground = FinancePalette.TextPrimary,
    surface = FinancePalette.Surface,
    onSurface = FinancePalette.TextPrimary,
    surfaceVariant = FinancePalette.Surface,
    onSurfaceVariant = FinancePalette.TextSecondary,
    surfaceTint = FinancePalette.Primary,
    inverseSurface = FinancePalette.TextPrimary,
    inverseOnSurface = Color.White,
    outline = FinancePalette.Border,
    outlineVariant = FinancePalette.Divider,
    error = FinancePalette.Error,
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = FinancePalette.Error,
    scrim = Color.Black,
)

/**
 * Цвет дохода и расхода для строк и графиков.
 *
 * Совпадают с семантическими [FinancePalette.Success] и [FinancePalette.Error]: знак суммы
 * и её смысл в разделе должны читаться одним и тем же оттенком.
 */
val IncomeColor = FinancePalette.Success
val ExpenseColor = FinancePalette.Error

/**
 * Приглушённый текст: подписи, прочерки, второстепенные колонки.
 *
 * Это [FinancePalette.TextSecondary], а не «disabled»: значение остаётся читаемым
 * (5.9:1 на белом), поэтому проходит AA. Настоящий disabled — [FinancePalette.TextDisabled].
 */
val MutedColor = FinancePalette.TextSecondary

/**
 * Цвет дельты с учётом семантики раздела, а не знака числа.
 *
 * Рост доходов и падение расходов — одинаково хороший исход, поэтому оба зелёные; обратное
 * — красное. Нулевое изменение нейтрально: красить «0 ₽» в зелёный значит показывать успех,
 * которого нет.
 *
 * @param favorableWhenPositive `true` для доходов и капитала, `false` для расходов.
 */
fun deltaColor(deltaMinor: Long, favorableWhenPositive: Boolean): Color = when {
    deltaMinor == 0L -> MutedColor
    (deltaMinor > 0L) == favorableWhenPositive -> FinancePalette.Success
    else -> FinancePalette.Error
}

@Composable
fun FinanceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = FinanceTypography,
        shapes = FinanceShapes,
        content = content,
    )
}
