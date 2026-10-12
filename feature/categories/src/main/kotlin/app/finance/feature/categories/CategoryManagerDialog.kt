package app.finance.feature.categories

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.finance.core.model.AssetBalancePolicy
import app.finance.core.model.Category
import app.finance.core.model.CategorySection
import app.finance.core.ui.CategoryDot
import app.finance.core.ui.CheckboxRow
import app.finance.core.ui.EmptyState
import app.finance.core.ui.FinancePalette
import app.finance.core.ui.FinancePicker
import app.finance.core.ui.FinanceRadius
import app.finance.core.ui.FinanceSize
import app.finance.core.ui.FinanceSpace
import app.finance.core.ui.FinanceTextField
import app.finance.core.ui.MutedColor
import app.finance.core.ui.NoticeBar
import app.finance.core.ui.PrimaryButton
import app.finance.core.ui.QuietButton
import app.finance.core.ui.SectionHeader
import app.finance.core.ui.SecondaryButton
import app.finance.core.ui.hintRu
import app.finance.core.ui.parseHexColor
import app.finance.core.ui.titleRu

/**
 * Диалог управления категориями раздела (SPEC.md F3.1, F3.2).
 *
 * Один диалог на три раздела — расходы, доходы и активы: различается только заголовком,
 * который контроллер берёт из [CategoriesUiState.section]. Разделы подключают его вместо
 * собственной копии экрана (F2.2).
 *
 * Режимы внутри одного окна: список категорий и редактор одной категории. Отдельное окно
 * для редактора не нужно — правка короткая, а контекст списка (соседние имена и цвета)
 * при этом остаётся на виду.
 *
 * @param onClosed вызывается при закрытии, чтобы раздел-владелец перечитал свои категории:
 *   новая должна появиться в пикере ввода, архивированная — исчезнуть из него.
 */
@Composable
fun CategoryManagerDialog(
    controller: CategoriesController,
    modifier: Modifier = Modifier,
    onClosed: () -> Unit = {},
) {
    val state by controller.state.collectAsState()
    if (!state.isOpen) return

    val dismiss = {
        controller.close()
        onClosed()
    }

    Dialog(onDismissRequest = dismiss) {
        Surface(
            modifier = modifier.width(DIALOG_WIDTH),
            shape = FinanceRadius.cardShape,
            color = FinancePalette.Background,
            border = BorderStroke(1.dp, FinancePalette.Divider),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(FinanceSpace.Lg),
                verticalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
            ) {
                val editor = state.editor
                SectionHeader(
                    title = editor?.title ?: state.title,
                    subtitle = if (editor == null) state.subtitle else null,
                    actions = if (editor == null) {
                        {
                            PrimaryButton(
                                text = "Новая категория",
                                onClick = controller::startCreate,
                                enabled = !state.isBusy,
                            )
                        }
                    } else {
                        null
                    },
                )

                state.error?.let { message ->
                    NoticeBar(text = message, onDismiss = controller::dismissError, error = true)
                }
                state.notice?.let { message ->
                    NoticeBar(text = message, onDismiss = controller::dismissNotice)
                }

                if (editor != null) {
                    CategoryEditor(editor = editor, controller = controller)
                } else {
                    CategoryList(state = state, controller = controller)
                    CheckboxRow(
                        checked = state.showArchived,
                        onCheckedChange = controller::setShowArchived,
                        label = "Показывать архивные",
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    SecondaryButton(
                        text = if (editor != null) "Отмена" else "Закрыть",
                        onClick = if (editor != null) controller::cancelEditor else dismiss,
                        enabled = !(editor?.isSaving ?: false),
                    )
                }
            }
        }
    }
}

/** Список категорий раздела с действиями «Изменить» и «В архив» / «Вернуть». */
@Composable
private fun CategoryList(state: CategoriesUiState, controller: CategoriesController) {
    when {
        state.isLoading -> Box(
            modifier = Modifier.fillMaxWidth().height(LIST_MAX_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        state.visibleItems.isEmpty() -> EmptyState(
            title = if (state.showArchived) "Категорий нет" else "Активных категорий нет",
            hint = "Добавьте категорию — она сразу появится в пикере ввода.",
            modifier = Modifier.fillMaxWidth(),
        )

        else -> Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT).verticalScroll(rememberScrollState()),
        ) {
            state.visibleItems.forEach { item ->
                CategoryRow(
                    item = item,
                    enabled = !state.isBusy,
                    onEdit = { controller.startEdit(item.id) },
                    onArchive = { controller.archive(item.id) },
                    onRestore = { controller.restore(item.id) },
                )
                HorizontalDivider(color = FinancePalette.Divider)
            }
        }
    }
}

@Composable
private fun CategoryRow(
    item: CategoryItem,
    enabled: Boolean,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(FinanceSize.TableRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryDot(hexColor = item.colorHex)
        Text(
            text = item.name,
            modifier = Modifier
                .padding(horizontal = FinanceSpace.Sm)
                .weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.isArchived) MutedColor else FinancePalette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.isArchived) {
            Text(
                text = "в архиве",
                modifier = Modifier.padding(end = FinanceSpace.Sm),
                style = MaterialTheme.typography.labelSmall,
                color = MutedColor,
            )
        } else if (item.balancePolicy != AssetBalancePolicy.DEFAULT) {
            // У потоковых категорий правила баланса нет (всегда FLAT), поэтому подпись
            // появляется только в списке категорий активов.
            Text(
                text = item.balancePolicy.titleRu(),
                modifier = Modifier.padding(end = FinanceSpace.Sm),
                style = MaterialTheme.typography.labelSmall,
                color = MutedColor,
            )
        }
        QuietButton(text = "Изменить", onClick = onEdit, enabled = enabled)
        QuietButton(
            text = if (item.isArchived) "Вернуть" else "В архив",
            onClick = if (item.isArchived) onRestore else onArchive,
            enabled = enabled,
        )
    }
}

/** Редактор имени и цвета: создание и правка одной формой (F3.1). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditor(editor: CategoryEditorState, controller: CategoriesController) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
    ) {
        FinanceTextField(
            value = editor.name,
            onValueChange = controller::setEditorName,
            label = "Название",
            placeholder = "Например, Спорт",
            modifier = Modifier.fillMaxWidth(),
            isError = editor.error != null,
            supportingText = editor.error ?: "Имя должно быть уникальным в разделе",
        )

        // Метка цвета повторяет фиксированную высоту метки поля (FinanceSize.FieldLabelHeight),
        // поэтому блоки формы стоят на общей линии.
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().height(FinanceSize.FieldLabelHeight),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = "Цвет",
                    style = MaterialTheme.typography.labelMedium,
                    color = FinancePalette.TextSecondary,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm),
                verticalArrangement = Arrangement.spacedBy(FinanceSpace.Sm),
            ) {
                Category.DEFAULT_PALETTE.forEach { hexColor ->
                    ColorSwatch(
                        hexColor = hexColor,
                        selected = hexColor.equals(editor.colorHex, ignoreCase = true),
                        enabled = !editor.isSaving,
                        onClick = { controller.setEditorColor(hexColor) },
                    )
                }
            }
        }

        // Правило баланса и право на отрицательный остаток — свойства категорий активов
        // (SPEC.md F4.6, D-20). У категорий расходов и доходов баланса нет, поэтому
        // блок показывается только в разделе «Активы».
        if (controller.section == CategorySection.ASSET) {
            FinancePicker(
                label = "Что происходит с суммой в следующем месяце",
                current = editor.balancePolicy.titleRu(),
                options = AssetBalancePolicy.entries.map { it.name to it.titleRu() },
                onPick = { picked ->
                    AssetBalancePolicy.entries.firstOrNull { it.name == picked }
                        ?.let(controller::setEditorBalancePolicy)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !editor.isSaving,
            )
            Text(
                text = editor.balancePolicy.hintRu(),
                style = MaterialTheme.typography.labelSmall,
                color = MutedColor,
            )
            CheckboxRow(
                checked = editor.allowsNegativeBalance,
                onCheckedChange = controller::setEditorAllowsNegative,
                label = "Баланс может быть отрицательным (кредит, овердрафт)",
            )
        }

        PrimaryButton(
            text = if (editor.isSaving) "Сохраняем…" else editor.saveLabel,
            onClick = controller::saveEditor,
            enabled = !editor.isSaving && editor.name.isNotBlank(),
        )
    }
}

/** Кружок палитры; выбранный обведён акцентным цветом. */
@Composable
private fun ColorSwatch(hexColor: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(SWATCH_SIZE)
            .border(
                width = 2.dp,
                color = if (selected) FinancePalette.Primary else Color.Transparent,
                shape = CircleShape,
            )
            .padding(3.dp)
            .clip(CircleShape)
            .background(parseHexColor(hexColor))
            .clickable(enabled = enabled, onClick = onClick),
    )
}

private val DIALOG_WIDTH = 620.dp
private val LIST_MAX_HEIGHT = 380.dp
private val SWATCH_SIZE = 30.dp
