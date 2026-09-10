package app.finance.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.finance.core.model.Money
import app.finance.core.model.Period

/**
 * Общие компоненты оболочки. Бизнес-логики здесь нет: компоненты принимают данные
 * и колбэки, решения принимает вызывающий слой (SPEC.md §4: core:ui — только UI).
 */

/** Пункт боковой навигации. `enabled = false` — раздел ещё не реализован. */
data class SidebarItem(
    val id: String,
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

@Composable
fun AppSidebar(
    title: String,
    items: List<SidebarItem>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
) {
    // fillMaxHeight: в Row дочерние элементы не растягиваются сами, а весовой Spacer
    // внизу панели требует ограниченной высоты, иначе прижимает footer к списку.
    Surface(
        modifier = modifier.width(220.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))
            items.forEach { item ->
                SidebarRow(item = item, onClick = { onSelect(item.id) })
            }
            Spacer(modifier = Modifier.weight(1f))
            footer()
        }
    }
}

@Composable
private fun SidebarRow(item: SidebarItem, onClick: () -> Unit) {
    val background = if (item.selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val contentColor = when {
        !item.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        item.selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.enabled, onClick = onClick)
            .background(background, shape = MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Переключение периода: стрелки, название месяца и «Сегодня» (SPEC.md F1.7). */
@Composable
fun PeriodNavigator(
    period: Period,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPrevious) { Text("‹") }
        Text(
            text = period.titleRu(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedButton(onClick = onNext) { Text("›") }
        TextButton(onClick = onToday) { Text("Сегодня") }
    }
}

/** Цветной маркер категории. Недопустимый HEX показывается нейтральным серым. */
@Composable
fun CategoryDot(hexColor: String, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .background(color = parseHexColor(hexColor), shape = CircleShape),
    )
}

/** Сумма в формате «1 234,50 ₽». */
@Composable
fun MoneyText(
    money: Money,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    signed: Boolean = false,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Text(
        text = if (signed) money.formatSignedRu() else money.formatRu(),
        modifier = modifier,
        color = color,
        style = style,
    )
}

/** Поле ввода суммы. Валидация и разбор — в вызывающем слое, здесь только отображение ошибки. */
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
    )
}

/** Диалог подтверждения. `destructive` окрашивает подтверждающее действие в цвет ошибки. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surface,
    )
}

@Composable
fun EmptyState(title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Строка уведомления (замена снекбара: на десктопе панель под шапкой заметнее). */
@Composable
fun NoticeBar(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, error: Boolean = false) {
    val container = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer
    val content = if (error) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimaryContainer

    Surface(modifier = modifier.fillMaxWidth(), color = container) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, modifier = Modifier.weight(1f), color = content, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("Закрыть", color = content) }
        }
    }
}

/** Раздел, который ещё не реализован: честная заглушка вместо пустого экрана. */
@Composable
fun SectionNotReady(title: String, milestone: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Раздел появится на этапе $milestone (docs/SPEC.md, раздел 12).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Разбор цвета категории из БД (`#RRGGBB`). Значение приходит из пользовательских
 * данных, поэтому недопустимый формат не должен ронять экран.
 */
fun parseHexColor(value: String): Color {
    val normalized = value.trim().removePrefix("#")
    if (normalized.length != 6) return MutedColor
    val parsed = normalized.toLongOrNull(radix = 16) ?: return MutedColor
    return Color(0xFF000000 or parsed)
}

/** Строка с выбором (чекбокс + подпись) — используется в диалоге дублирования. */
@Composable
fun CheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
