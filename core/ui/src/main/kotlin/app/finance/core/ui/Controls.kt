package app.finance.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Контролы дизайн-системы: кнопки, чипсы и поля ввода.
 *
 * Единые размеры и радиусы заданы токенами ([FinanceSize], [FinanceRadius]), а hover —
 * обязательное состояние, а не украшение: приложение десктопное, и курсор постоянно живёт
 * над таблицами. Переходы короткие ([FinanceMotion.HOVER_MS]), чтобы интерфейс не «плыл».
 */

/** Основная кнопка: 40px, радиус 8px, акцентный фон. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (() -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(FinanceSize.ControlHeight),
        enabled = enabled,
        shape = FinanceRadius.controlShape,
        contentPadding = PaddingValues(horizontal = FinanceSpace.Lg),
        colors = ButtonDefaults.buttonColors(
            containerColor = FinancePalette.Primary,
            contentColor = Color.White,
            disabledContainerColor = FinancePalette.Divider,
            disabledContentColor = FinancePalette.TextDisabled,
        ),
    ) {
        ButtonContent(text = text, icon = icon)
    }
}

/**
 * Вторичная кнопка: те же размеры, но граница вместо заливки.
 *
 * Граница рисуется явно [FinancePalette.Border], а не через `outline` темы: на карточке
 * #F8F9FA дефолтный контур M3 почти не виден, и кнопка теряется.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (() -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(FinanceSize.ControlHeight),
        enabled = enabled,
        shape = FinanceRadius.controlShape,
        contentPadding = PaddingValues(horizontal = FinanceSpace.Lg),
        border = BorderStroke(1.dp, if (enabled) FinancePalette.Border else FinancePalette.Divider),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = FinancePalette.TextPrimary,
            disabledContentColor = FinancePalette.TextDisabled,
        ),
    ) {
        ButtonContent(text = text, icon = icon)
    }
}

/** Кнопка-ссылка для второстепенных действий («Сегодня», «Отмена»). */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = if (hovered) FinancePalette.PrimaryLight else Color.Transparent,
        animationSpec = tween(durationMillis = FinanceMotion.HOVER_MS, easing = FastOutSlowInEasing),
        label = "quietButtonHover",
    )

    Box(
        modifier = modifier
            .height(FinanceSize.ControlHeight)
            .background(background, shape = FinanceRadius.controlShape)
            .clickableNoRipple(interactionSource = interactionSource, enabled = enabled, onClick = onClick)
            .padding(horizontal = FinanceSpace.Md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> FinancePalette.TextDisabled
                hovered -> FinancePalette.Primary
                else -> FinancePalette.TextSecondary
            },
        )
    }
}

@Composable
private fun ButtonContent(text: String, icon: (@Composable () -> Unit)?) {
    if (icon != null) {
        icon()
        Spacer(modifier = Modifier.width(FinanceSpace.Sm))
    }
    Text(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
}

/**
 * Чипс: 32px, радиус 20px, подпись и необязательная цветная точка категории.
 *
 * Без точки ([colorHex] = null) работает как фильтр — например, пресеты периода в
 * «Аналитике». Выбранное состояние показано заливкой [FinancePalette.PrimaryLight] и
 * акцентной границей, а не только цветом текста: состояние должно читаться и без наведения,
 * и при дейтеранопии.
 */
@Composable
fun CategoryChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colorHex: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = when {
            selected -> FinancePalette.PrimaryLight
            hovered -> FinancePalette.RowHover
            else -> FinancePalette.Background
        },
        animationSpec = tween(durationMillis = FinanceMotion.HOVER_MS, easing = FastOutSlowInEasing),
        label = "chipHover",
    )
    val borderColor = when {
        !enabled -> FinancePalette.Divider
        selected -> FinancePalette.Primary
        else -> FinancePalette.Border
    }

    Row(
        modifier = modifier
            .height(FinanceSize.ChipHeight)
            .background(background, shape = FinanceRadius.chipShape)
            .border(width = 1.dp, color = borderColor, shape = FinanceRadius.chipShape)
            .clickableNoRipple(interactionSource = interactionSource, enabled = enabled, onClick = onClick)
            .padding(horizontal = FinanceSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (colorHex != null) {
            Box(
                modifier = Modifier
                    .size(FinanceSize.LegendDot)
                    .background(color = parseHexColor(colorHex), shape = CircleShape),
            )
            Spacer(modifier = Modifier.width(FinanceSpace.Sm))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) FinancePalette.TextPrimary else FinancePalette.TextDisabled,
            maxLines = 1,
        )
    }
}

/**
 * Поле ввода высотой 40px с выраженной границей и акцентным фокусом.
 *
 * Построено на `BasicTextField`, а не на `OutlinedTextField`: у M3-поля минимальная высота
 * 56px и плавающая метка внутри, а плотной таблице нужен компактный контрол с подписью
 * сверху. Поддержка ошибок и подсказки сохранена — валидация остаётся в вызывающем слое.
 */
@Composable
fun FinanceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    isError: Boolean = false,
    supportingText: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    textAlign: TextAlign = TextAlign.Start,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()

    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> FinancePalette.Divider
            isError -> FinancePalette.Error
            focused -> FinancePalette.Primary
            hovered -> FinancePalette.TextDisabled
            else -> FinancePalette.Border
        },
        animationSpec = tween(durationMillis = FinanceMotion.HOVER_MS, easing = FastOutSlowInEasing),
        label = "fieldBorder",
    )

    Column(modifier = modifier) {
        if (label != null) {
            // Метка занимает фиксированную высоту, поэтому контролы без метки (кнопки,
            // выпадающие списки) выравниваются в строке формы по самому полю, а не по
            // блоку «метка + поле».
            Box(
                modifier = Modifier.fillMaxWidth().height(FinanceSize.FieldLabelHeight),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) FinancePalette.TextSecondary else FinancePalette.TextDisabled,
                    maxLines = 1,
                )
            }
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(FinanceSize.ControlHeight)
                .background(FinancePalette.Background, shape = FinanceRadius.controlShape)
                .border(width = if (focused) 1.5.dp else 1.dp, color = borderColor, shape = FinanceRadius.controlShape)
                .padding(horizontal = FinanceSpace.Md),
            enabled = enabled,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (enabled) FinancePalette.TextPrimary else FinancePalette.TextDisabled,
                textAlign = textAlign,
            ),
            cursorBrush = SolidColor(FinancePalette.Primary),
            keyboardOptions = keyboardOptions,
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(FinanceSize.ControlHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = FinancePalette.TextDisabled,
                                textAlign = textAlign,
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                }
            },
        )

        if (supportingText != null) {
            Spacer(modifier = Modifier.height(FinanceSpace.Xs))
            Text(
                text = supportingText,
                style = MaterialTheme.typography.labelSmall,
                color = if (isError) FinancePalette.Error else FinancePalette.TextSecondary,
            )
        }
    }
}

/**
 * Обёртка, ставящая контрол без метки (кнопку, выпадающий список) на одну линию с
 * [FinanceTextField], у которого подпись расположена сверху.
 *
 * Без неё кнопка центрируется по всему блоку «метка + поле» и визуально повисает между
 * подписью и полем ввода. Место под метку резервируется всегда — пустым блоком той же
 * высоты [FinanceSize.FieldLabelHeight], — поэтому поля одной формы стоят на общей линии.
 *
 * Ширина обёртки определяется содержимым или [modifier] от вызывающего: внутри себя она
 * ничего не растягивает, иначе в `Row` с полями она забирала бы всю строку и выталкивала
 * соседние контролы за правый край.
 *
 * @param label подпись; при `null` резервируется только место под неё.
 */
@Composable
fun LabeledControl(
    modifier: Modifier = Modifier,
    label: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier.height(FinanceSize.FieldLabelHeight),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = FinancePalette.TextSecondary,
                    maxLines = 1,
                )
            }
        }
        content()
    }
}

/**
 * Выпадающий список с подписью сверху — встаёт на одну линию с [FinanceTextField] (D-17).
 *
 * Общий компонент для всех форм: раньше свой приватный `Picker` жил в экране активов,
 * а копипаста контролов запрещена (F2.2, D-17).
 *
 * Ширина задаётся вызывающим через [modifier] (в колонке формы — `fillMaxWidth`):
 * кнопка растягивается на ширину компонента, поэтому в `Row` без явной ширины или `weight`
 * список заберёт всю строку — та же ловушка, что у [LabeledControl].
 *
 * @param label подпись сверху; `null` — без подписи и без резерва места (ячейка таблицы).
 * @param current подпись выбранного значения.
 * @param options пары `идентификатор -> подпись`; [onPick] получает идентификатор.
 */
@Composable
fun FinancePicker(
    label: String?,
    current: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    val picker: @Composable () -> Unit = {
        Box {
            SecondaryButton(
                text = current,
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, title) ->
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = {
                            onPick(id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }

    // Без подписи (ячейка таблицы) место под неё не резервируется: иначе список съезжает
    // вниз относительно соседних полей строки, у которых подписи тоже нет.
    if (label == null) {
        Box(modifier = modifier) { picker() }
    } else {
        LabeledControl(modifier = modifier, label = label) { picker() }
    }
}

/**
 * Клик без ripple: на десктопе волна выглядит чужеродно, а состояние уже передают hover
 * и цвет. `interactionSource` передаёт вызывающий — по нему же он читает hover/press.
 */
@Composable
internal fun Modifier.clickableNoRipple(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = interactionSource,
    indication = null,
    enabled = enabled,
    onClick = onClick,
)
