package app.finance.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.finance.core.data.AppDataLocations
import app.finance.core.data.DatabaseProfile
import app.finance.core.ui.FinanceCard
import app.finance.core.ui.FinancePalette
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
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Экран настроек (SPEC.md §4, feature:settings).
 *
 * В v1 настройка одна, но важная: выбор базы данных. Сценарий — держать отдельную базу
 * для экспериментов и основную с реальными данными, не боясь испортить учёт.
 *
 * Переключение не перезапускает приложение: хост (`app:desktop`) открывает новый граф
 * зависимостей и подменяет его в состоянии окна, поэтому все разделы перечитывают данные
 * из новой базы. При неудаче прежнее подключение сохраняется (см. [SettingsController]).
 */
@Composable
fun SettingsScreen(
    controller: SettingsController,
    modifier: Modifier = Modifier,
    /** Карточки других модулей (синхронизация, бэкапы): хост подставляет их снизу. */
    extraCards: @Composable ColumnScope.() -> Unit = {},
) {
    val state by controller.state.collectAsState()

    LaunchedEffect(controller) { controller.start() }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = FinanceSpace.Md),
        verticalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
    ) {
        state.error?.let { message ->
            NoticeBar(
                text = message,
                onDismiss = controller::dismissError,
                error = true,
                modifier = Modifier.padding(horizontal = FinanceSpace.Lg),
            )
        }
        state.notice?.let { message ->
            NoticeBar(
                text = message,
                onDismiss = controller::dismissNotice,
                modifier = Modifier.padding(horizontal = FinanceSpace.Lg),
            )
        }

        SectionHeader(
            title = "Настройки",
            subtitle = "База данных приложения",
            modifier = Modifier.fillMaxWidth().padding(horizontal = FinanceSpace.Lg),
        )

        FinanceCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = FinanceSpace.Lg),
            title = "База данных",
            subtitle = "Активная база открывается сразу: данные всех разделов относятся к ней",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(FinanceSpace.Xs)) {
                if (state.isBusy) {
                    Text(
                        text = "Открываем базу…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MutedColor,
                    )
                }

                state.profiles.forEach { profile ->
                    ProfileRow(
                        profile = profile,
                        isActive = profile.id == state.activeProfileId,
                        canRemove = !state.isBusy &&
                            profile.id != state.activeProfileId &&
                            state.profiles.size > 1,
                        enabled = !state.isBusy,
                        onSelect = { controller.setActive(profile.id) },
                        onRename = { controller.startRename(profile.id) },
                        onRemove = { controller.removeProfile(profile.id) },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = FinanceSpace.Sm),
                    horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm),
                ) {
                    SecondaryButton(
                        text = "Добавить базу…",
                        onClick = controller::startAdd,
                        enabled = !state.isBusy,
                    )
                    SecondaryButton(
                        text = "Создать тестовую базу",
                        onClick = controller::createTestDatabase,
                        enabled = !state.isBusy,
                    )
                }
            }
        }

        FinanceCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = FinanceSpace.Lg),
            title = "Файлы",
            subtitle = "Настройки лежат вне баз данных, поэтому смену базы можно откатить руками",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(FinanceSpace.Xs)) {
                FileRow(label = "Каталог данных", value = AppDataLocations.directory().toString())
                FileRow(label = "Файл настроек", value = AppDataLocations.settingsFile().toString())
                FileRow(label = "Активная база", value = state.activeProfile?.path ?: "—")
            }
        }

        extraCards()
    }

    state.editor?.let { editor -> ProfileEditorDialog(editor = editor, controller = controller) }
}

/** Строка базы в списке: выбор активной, название, путь и действия. */
@Composable
private fun ProfileRow(
    profile: DatabaseProfile,
    isActive: Boolean,
    canRemove: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(FinanceSize.TableRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isActive, onClick = onSelect, enabled = enabled)
        Column(modifier = Modifier.weight(1f).padding(horizontal = FinanceSpace.Sm)) {
            Text(
                text = profile.name,
                style = if (isActive) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                color = FinancePalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = profile.path,
                style = MaterialTheme.typography.labelSmall,
                color = MutedColor,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        if (isActive) {
            Text(
                text = "активная",
                modifier = Modifier.padding(end = FinanceSpace.Sm),
                style = MaterialTheme.typography.labelMedium,
                color = FinancePalette.Primary,
            )
        }
        QuietButton(text = "Переименовать", onClick = onRename, enabled = enabled)
        QuietButton(text = "Убрать из списка", onClick = onRemove, enabled = canRemove)
    }
}

@Composable
private fun FileRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            modifier = Modifier.width(LABEL_WIDTH),
            style = MaterialTheme.typography.labelMedium,
            color = MutedColor,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = FinancePalette.TextPrimary,
        )
    }
}

/** Диалог добавления базы или переименования существующей. */
@Composable
private fun ProfileEditorDialog(editor: ProfileEditorState, controller: SettingsController) {
    Dialog(onDismissRequest = controller::cancelEditor) {
        Surface(
            modifier = Modifier.width(DIALOG_WIDTH),
            shape = FinanceRadius.cardShape,
            color = FinancePalette.Background,
            border = BorderStroke(1.dp, FinancePalette.Divider),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(FinanceSpace.Lg),
                verticalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
            ) {
                Text(text = editor.title, style = MaterialTheme.typography.titleMedium)

                FinanceTextField(
                    value = editor.name,
                    onValueChange = controller::setEditorName,
                    label = "Название",
                    placeholder = if (editor.isNew) "Например, Тестовая" else "Основная",
                    modifier = Modifier.fillMaxWidth(),
                    isError = editor.error != null,
                    supportingText = editor.error,
                )

                if (editor.isNew) {
                    FinanceTextField(
                        value = editor.path,
                        onValueChange = controller::setEditorPath,
                        label = "Файл базы",
                        placeholder = "/путь/к/файлу.db",
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = "Файл будет создан, если его нет; существующая база Finance откроется как есть",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
                        SecondaryButton(
                            text = "Выбрать файл…",
                            onClick = {
                                pickDatabasePath(save = true, suggestedName = NEW_DATABASE_FILE)?.let { path ->
                                    controller.setEditorPath(path)
                                }
                            },
                            enabled = !editor.isSaving,
                        )
                        SecondaryButton(
                            text = "Открыть существующую…",
                            onClick = {
                                pickDatabasePath(save = false)?.let(controller::setEditorPath)
                            },
                            enabled = !editor.isSaving,
                        )
                    }
                } else {
                    // Путь существующей базы не редактируется: перенос файла — операция
                    // файловой системы, а молча поменять путь значит потерять связь с данными.
                    Text(
                        text = editor.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedColor,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm),
                ) {
                    PrimaryButton(
                        text = if (editor.isSaving) "Открываем…" else editor.saveLabel,
                        onClick = controller::saveEditor,
                        enabled = !editor.isSaving && editor.name.isNotBlank(),
                    )
                    SecondaryButton(text = "Отмена", onClick = controller::cancelEditor, enabled = !editor.isSaving)
                }
            }
        }
    }
}

/**
 * Системный диалог выбора файла базы.
 *
 * AWT `FileDialog` модальный и показывается в потоке EDT, в котором работают обработчики
 * Compose Desktop, — переключения потоков не нужно. Возвращает null при отмене.
 *
 * @param save true — можно задать имя нового файла (создание базы), false — выбор существующего.
 */
internal fun pickDatabasePath(save: Boolean, suggestedName: String? = null): String? {
    val title = if (save) "Файл новой базы данных" else "Открыть базу данных"
    val dialog = FileDialog(null as Frame?, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (save && suggestedName != null) dialog.file = suggestedName
    dialog.isVisible = true

    val directory = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(directory, file).absolutePath
}

private val DIALOG_WIDTH = 560.dp
private val LABEL_WIDTH = 160.dp
private const val NEW_DATABASE_FILE = "finance-new.db"
