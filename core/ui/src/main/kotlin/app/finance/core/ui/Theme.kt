package app.finance.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Светлая тема приложения. Тёмная тема в v1 не поддерживается (docs/DECISIONS.md),
 * поэтому `darkColorScheme` намеренно отсутствует.
 *
 * Палитра подобрана так, чтобы текст на светлом фоне проходил WCAG AA (SPEC.md §10).
 */
val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE9FF),
    onPrimaryContainer = Color(0xFF0B2C63),
    secondary = Color(0xFF4E79A7),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF1F3F5),
    onSurfaceVariant = Color(0xFF4A4F55),
    outline = Color(0xFFC7CCD1),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

/** Цвет дохода и расхода для строк и графиков: различается и по знаку, и по оттенку. */
val IncomeColor = Color(0xFF2E7D32)
val ExpenseColor = Color(0xFFC62828)
val MutedColor = Color(0xFF7A8189)

@Composable
fun FinanceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
