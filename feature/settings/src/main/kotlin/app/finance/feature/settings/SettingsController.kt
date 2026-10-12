package app.finance.feature.settings

import app.finance.core.data.AppDataLocations
import app.finance.core.data.AppSettings
import app.finance.core.data.DatabaseProfile
import app.finance.core.data.SettingsStore
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Контроллер настроек: список баз данных и выбор активной.
 *
 * Само переключение базы контроллер не выполняет — у него нет доступа к графу зависимостей
 * (иначе `feature:settings` зависел бы от `app:desktop`, а это цикл). Он вызывает
 * [switchDatabase], а хост открывает новый граф и сообщает результат. Правило одно:
 * **при неудаче прежнее подключение остаётся открытым**, поэтому данные текущей сессии не
 * теряются из-за неверного пути или повреждённого файла.
 *
 * Настройки сохраняются в `app.finance/settings.properties` (см. [SettingsStore]) — вне
 * любой из баз, иначе выбор базы зависел бы от её содержимого.
 */
class SettingsController(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    /**
     * Открывает базу по пути и делает её источником данных приложения.
     *
     * @return true, если база открыта. При false хост обязан сохранить прежнее подключение.
     */
    private val switchDatabase: suspend (path: String) -> Boolean,
    initial: AppSettings,
) {

    private val _state = MutableStateFlow(SettingsUiState(settings = initial))

    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /**
     * Перечитывает файл настроек: его могли поправить руками при закрытом приложении.
     *
     * Активная база при этом НЕ меняется — истиной остаётся то подключение, которое сейчас
     * открыто в приложении, а не запись в файле.
     */
    fun start() {
        scope.launch {
            val loaded = runCatching { io { store.load() } }.getOrNull() ?: return@launch
            _state.update { current ->
                val openId = current.settings?.activeProfileId
                val settings = if (openId != null && loaded.profiles.any { it.id == openId }) {
                    loaded.withActive(openId)
                } else {
                    loaded
                }
                current.copy(settings = settings)
            }
        }
    }

    // ── Выбор активной базы ────────────────────────────────────────────────────────

    fun setActive(profileId: String) {
        val current = _state.value
        val settings = current.settings ?: return
        if (current.isBusy || settings.activeProfileId == profileId) return
        val profile = settings.profiles.firstOrNull { it.id == profileId } ?: return

        scope.launch {
            _state.update { it.copy(isBusy = true, error = null, notice = null) }
            val opened = runCatching { switchDatabase(profile.path) }.getOrDefault(false)
            if (opened) {
                val updated = settings.withActive(profileId)
                persist(updated)
                _state.update {
                    it.copy(isBusy = false, settings = updated, notice = "Активная база: ${profile.name}")
                }
            } else {
                _state.update {
                    it.copy(
                        isBusy = false,
                        error = "Не удалось открыть «${profile.name}»: файл недоступен или это не база " +
                            "Finance. Работа продолжается с прежней базой.",
                    )
                }
            }
        }
    }

    /**
     * Тестовая база рядом с основной — частый сценарий: «попробовать, не трогая свои данные».
     *
     * Файл создаётся в каталоге данных приложения, поэтому будущий бэкап (M6) заберёт его
     * вместе с основной базой. Если профиль с таким путём уже есть, просто переключаемся
     * на него: два профиля на один файл — источник путаницы.
     */
    fun createTestDatabase() {
        val current = _state.value
        if (current.isBusy || current.settings == null) return
        val path = AppDataLocations.directory().resolve(TEST_DATABASE_FILE).toString()

        val existing = current.profiles.firstOrNull { it.path == path }
        if (existing != null) {
            setActive(existing.id)
            return
        }
        addProfile(name = "Тестовая", path = path, preferredId = TEST_PROFILE_ID)
    }

    // ── Профили ────────────────────────────────────────────────────────────────────

    fun startAdd() {
        val suggested = AppDataLocations.directory().resolve(TEST_DATABASE_FILE).toString()
        _state.update {
            it.copy(editor = ProfileEditorState(name = "", path = suggested), error = null, notice = null)
        }
    }

    fun startRename(profileId: String) {
        val profile = _state.value.profiles.firstOrNull { it.id == profileId } ?: return
        _state.update {
            it.copy(
                editor = ProfileEditorState(id = profile.id, name = profile.name, path = profile.path),
                error = null,
                notice = null,
            )
        }
    }

    fun setEditorName(text: String) {
        _state.update { state -> state.copy(editor = state.editor?.copy(name = text, error = null)) }
    }

    fun setEditorPath(text: String) {
        _state.update { state -> state.copy(editor = state.editor?.copy(path = text, error = null)) }
    }

    fun cancelEditor() {
        _state.update { it.copy(editor = null) }
    }

    fun saveEditor() {
        val editor = _state.value.editor ?: return
        if (editor.isSaving) return

        if (editor.isNew) {
            val name = editor.name.trim()
            val path = editor.path.trim()
            when {
                name.isEmpty() -> editorError("Укажите название базы")
                path.isEmpty() -> editorError("Укажите путь к файлу базы")
                else -> addProfile(name = name, path = path)
            }
            return
        }

        val profileId = editor.id ?: return
        val name = editor.name.trim()
        if (name.isEmpty()) {
            editorError("Название не может быть пустым")
            return
        }
        val settings = _state.value.settings ?: return
        val updated = runCatching { settings.withRenamed(profileId, name) }
            .onFailure { error -> editorError(describe(error)) }
            .getOrNull() ?: return

        scope.launch {
            persist(updated)
            _state.update { it.copy(settings = updated, editor = null, notice = "Название обновлено") }
        }
    }

    /**
     * Убирает базу из списка настроек. Файл на диске остаётся: удаление данных не должно
     * быть побочным эффектом правки настроек.
     */
    fun removeProfile(profileId: String) {
        val current = _state.value
        val settings = current.settings ?: return
        if (current.isBusy) return
        val profile = settings.profiles.firstOrNull { it.id == profileId } ?: return

        val updated = runCatching { settings.without(profileId) }
            .onFailure { error -> _state.update { it.copy(error = describe(error)) } }
            .getOrNull() ?: return

        scope.launch {
            persist(updated)
            _state.update {
                it.copy(
                    settings = updated,
                    notice = "«${profile.name}» убрана из списка. Файл базы остался на диске: ${profile.path}",
                )
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    // ── Служебное ──────────────────────────────────────────────────────────────────

    /**
     * Добавляет базу в список, делает её активной и открывает.
     *
     * Если открыть не удалось, профиль остаётся в списке (путь уже введён, терять его
     * незачем), но активной возвращается прежняя база.
     */
    private fun addProfile(name: String, path: String, preferredId: String? = null) {
        val settings = _state.value.settings ?: return
        val profile = DatabaseProfile(id = newProfileId(preferredId), name = name, path = path)

        val updated = runCatching { settings.withAdded(profile) }
            .onFailure { error -> editorError(describe(error)) }
            .getOrNull() ?: return

        scope.launch {
            _state.update { it.copy(isBusy = true, editor = null, error = null, notice = null) }
            persist(updated)

            val opened = runCatching { switchDatabase(profile.path) }.getOrDefault(false)
            if (opened) {
                _state.update {
                    it.copy(isBusy = false, settings = updated, notice = "База «${profile.name}» добавлена и открыта")
                }
            } else {
                val reverted = updated.withActive(settings.activeProfileId)
                persist(reverted)
                _state.update {
                    it.copy(
                        isBusy = false,
                        settings = reverted,
                        error = "База «${profile.name}» добавлена в список, но открыть её не удалось. " +
                            "Проверьте путь и права на запись — работа продолжается с прежней базой.",
                    )
                }
            }
        }
    }

    private suspend fun persist(settings: AppSettings) {
        runCatching { io { store.save(settings) } }
            .onFailure { error ->
                _state.update { it.copy(error = "Настройки не сохранены: ${describe(error)}") }
            }
    }

    private fun editorError(message: String) {
        _state.update { state -> state.copy(editor = state.editor?.copy(error = message, isSaving = false)) }
    }

    /**
     * Идентификатор профиля: стабильный ключ в properties-файле, поэтому без пробелов и точек.
     * Предпочтительный id используется, если свободен, — так «Тестовая» получает читаемый `test`.
     */
    private fun newProfileId(preferred: String?): String {
        val taken = _state.value.profiles.map { it.id }.toSet()
        preferred?.takeIf { it.isNotBlank() && it !in taken }?.let { return it }
        var candidate = randomProfileId()
        while (candidate in taken) candidate = randomProfileId()
        return candidate
    }

    private fun randomProfileId(): String = "db-" + UUID.randomUUID().toString().take(8)

    private suspend fun <T> io(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private companion object {
        const val TEST_PROFILE_ID = "test"
        const val TEST_DATABASE_FILE = "finance-test.db"
    }
}
