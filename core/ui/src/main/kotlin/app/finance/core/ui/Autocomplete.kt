package app.finance.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle

/**
 * Поле с автодополнением: начинаешь печатать — под полем сразу появляются подходящие
 * варианты (F1.4, D-35).
 *
 * Поведение (keyboard-first, §1):
 * - фокус выделяет текущее значение целиком и раскрывает весь список — можно выбрать
 *   стрелками, ничего не печатая, или сразу начать набор: первая буква заменит старое имя;
 * - список фильтруется по [rankSuggestions] (регистр и «ё» не важны), совпадение подсвечено;
 * - `↑/↓` — выбор подсказки, `Enter` — подтвердить и перейти к следующему полю формы,
 *   `Tab` — подтвердить подсвеченную подсказку и уйти дальше, `Esc` — вернуть прежнее значение;
 * - уход из поля без выбора возвращает прежнее значение: в поле никогда не остаётся
 *   «полуимя», которому не соответствует ни один вариант.
 *
 * Компонент не хранит выбор — его держит вызывающий ([selected], [onPick]), как и у
 * [FinancePicker]. Ширину задаёт вызывающий через [modifier].
 *
 * @param optionIcon значок варианта (цвет категории); показывается и в поле у выбранного.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun <T> FinanceAutocomplete(
    label: String?,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Начните вводить…",
    enabled: Boolean = true,
    optionIcon: (@Composable (T) -> Unit)? = null,
) {
    val selectedLabel = selected?.let(optionLabel).orEmpty()
    val focusManager = LocalFocusManager.current

    var text by remember { mutableStateOf(TextFieldValue(selectedLabel)) }
    // null — пользователь ещё ничего не напечатал после фокуса: показываем весь список.
    var query by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var highlighted by remember { mutableIntStateOf(0) }

    val currentQuery = query
    val suggestions = remember(options, currentQuery) {
        if (currentQuery == null) options else rankSuggestions(options, currentQuery, optionLabel)
    }
    val active = highlighted.coerceIn(0, maxOf(0, suggestions.lastIndex))
    val menuVisible = expanded && enabled && focused

    // Выбор, сделанный снаружи (batch-ввод подставил прошлую категорию, категорию
    // архивировали), отражается в поле, пока пользователь его не редактирует.
    LaunchedEffect(selectedLabel, focused) {
        if (!focused) {
            text = TextFieldValue(selectedLabel)
            query = null
        }
    }

    fun revert() {
        text = TextFieldValue(selectedLabel, TextRange(selectedLabel.length))
        query = null
        expanded = false
    }

    fun pick(item: T, moveFocus: Boolean) {
        onPick(item)
        val name = optionLabel(item)
        text = TextFieldValue(name, TextRange(name.length))
        query = null
        expanded = false
        if (moveFocus) focusManager.moveFocus(FocusDirection.Next)
    }

    ExposedDropdownMenuBox(
        expanded = menuVisible,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        FinanceTextField(
            value = text,
            onValueChange = { new ->
                if (new.text != text.text) {
                    query = new.text
                    highlighted = 0
                    expanded = true
                }
                text = new
            },
            label = label,
            placeholder = placeholder,
            enabled = enabled,
            leading = if (currentQuery == null && selected != null && optionIcon != null) {
                { optionIcon(selected) }
            } else {
                null
            },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled)
                .onFocusChanged { state ->
                    if (state.hasFocus && !focused) {
                        // Весь текст выделен: первая же буква заменяет прежнюю категорию.
                        text = text.copy(selection = TextRange(0, text.text.length))
                        highlighted = options.indexOf(selected).coerceAtLeast(0)
                        expanded = true
                    } else if (!state.hasFocus && focused) {
                        revert()
                    }
                    focused = state.hasFocus
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            if (!expanded) expanded = true else highlighted = (active + 1).coerceAtMost(suggestions.lastIndex)
                            true
                        }
                        Key.DirectionUp -> {
                            if (expanded) highlighted = (active - 1).coerceAtLeast(0)
                            expanded
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            val item = suggestions.getOrNull(active)
                            if (expanded && item != null) {
                                pick(item, moveFocus = true)
                                true
                            } else {
                                false
                            }
                        }
                        Key.Tab -> {
                            // Фокус уходит штатно; подсвеченная подсказка принимается, только
                            // если пользователь что-то печатал, — иначе Tab просто проходит поле.
                            if (currentQuery != null) suggestions.getOrNull(active)?.let { pick(it, moveFocus = false) }
                            false
                        }
                        Key.Escape -> {
                            val handled = expanded || currentQuery != null
                            if (handled) revert()
                            handled
                        }
                        else -> false
                    }
                },
        )

        ExposedDropdownMenu(
            expanded = menuVisible,
            onDismissRequest = { expanded = false },
            scrollState = rememberScrollState(),
        ) {
            if (suggestions.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Ничего не найдено",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FinancePalette.TextSecondary,
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
            }
            suggestions.forEachIndexed { index, item ->
                key(index) {
                    val requester = remember { BringIntoViewRequester() }
                    if (index == active) {
                        // Подсказка, выбранная стрелками, не уходит за край списка.
                        LaunchedEffect(active, suggestions) { requester.bringIntoView() }
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = highlightMatch(optionLabel(item), currentQuery),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                        },
                        leadingIcon = optionIcon?.let { icon -> { icon(item) } },
                        onClick = { pick(item, moveFocus = true) },
                        modifier = Modifier
                            .bringIntoViewRequester(requester)
                            .background(if (index == active) FinancePalette.PrimaryLight else Color.Transparent),
                    )
                }
            }
        }
    }
}

/** Совпавшая с запросом часть имени — полужирным: видно, почему вариант попал в список. */
private fun highlightMatch(name: String, query: String?): AnnotatedString {
    val range = query?.let { matchRange(name, it) } ?: return AnnotatedString(name)
    return buildAnnotatedString {
        append(name.substring(0, range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name.substring(range.first, range.last + 1)) }
        append(name.substring(range.last + 1))
    }
}
