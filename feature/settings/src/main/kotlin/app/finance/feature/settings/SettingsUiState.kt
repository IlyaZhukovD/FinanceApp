package app.finance.feature.settings

import app.finance.core.data.AppSettings
import app.finance.core.data.DatabaseProfile

/**
 * Состояние экрана настроек (SPEC.md §4, feature:settings).
 *
 * В v1 настройки — это выбор базы данных: пользователю нужно держать отдельную базу для
 * экспериментов и основную с реальными данными. Профили и активный id не дублируются
 * полями — состояние хранит готовый [AppSettings], а экран читает его через короткие
 * доступы ниже.
 */
data class SettingsUiState(
    /** null до первой загрузки файла настроек. */
    val settings: AppSettings? = null,
    val isBusy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val editor: ProfileEditorState? = null,
) {
    val profiles: List<DatabaseProfile> get() = settings?.profiles.orEmpty()

    val activeProfileId: String? get() = settings?.activeProfileId

    val activeProfile: DatabaseProfile? get() = settings?.active
}

/**
 * Диалог базы данных: `id == null` — добавление новой, иначе переименование существующей.
 *
 * Путь редактируется только при добавлении: перенос файла базы за пределы приложения —
 * операция файловой системы, а не настройка, и молча менять путь у существующей базы
 * значит потерять связь с данными.
 */
data class ProfileEditorState(
    val id: String? = null,
    val name: String = "",
    val path: String = "",
    val error: String? = null,
    val isSaving: Boolean = false,
) {
    val isNew: Boolean get() = id == null

    val title: String get() = if (isNew) "Новая база данных" else "Переименовать базу"

    val saveLabel: String get() = if (isNew) "Добавить и открыть" else "Сохранить"
}
