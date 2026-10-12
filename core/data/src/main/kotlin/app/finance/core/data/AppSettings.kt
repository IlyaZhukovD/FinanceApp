package app.finance.core.data

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * Каталоги приложения (SPEC.md §2).
 *
 * macOS — `~/Library/Application Support/app.finance`,
 * Windows — `%APPDATA%\app.finance`,
 * прочее — `~/.local/share/app.finance`.
 *
 * Живёт в слое данных, а не в `app:desktop`: путь к каталогу нужен и настройкам, и графу
 * зависимостей, а дублировать определение платформы в двух модулях — источник расхождения.
 */
object AppDataLocations {

    private const val APP_DIRECTORY = "app.finance"

    fun directory(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val home = Paths.get(System.getProperty("user.home"))
        val base = when {
            osName.contains("mac") || osName.contains("darwin") ->
                home.resolve("Library").resolve("Application Support")

            osName.contains("win") ->
                System.getenv("APPDATA")?.let { Paths.get(it) } ?: home.resolve("AppData").resolve("Roaming")

            else -> home.resolve(".local").resolve("share")
        }
        return base.resolve(APP_DIRECTORY)
    }

    /** Основная база данных: создаётся при первом запуске. */
    fun defaultDatabase(): Path = directory().resolve("finance.db")

    /** Файл настроек. Хранится рядом с базами, но не внутри них. */
    fun settingsFile(): Path = directory().resolve("settings.properties")
}

/**
 * Именованная база данных.
 *
 * Профиль — только ссылка на файл: ни подключения, ни данных он не держит. Удаление
 * профиля из списка НЕ удаляет файл на диске — стирать данные пользователя как побочный
 * эффект правки настроек приложение не имеет права (SPEC.md §8: данные неприкосновенны).
 */
data class DatabaseProfile(
    val id: String,
    val name: String,
    val path: String,
) {
    init {
        require(id.isNotBlank()) { "Идентификатор базы не может быть пустым" }
        require(name.isNotBlank()) { "Название базы не может быть пустым" }
        require(path.isNotBlank()) { "Путь к базе не может быть пустым" }
    }
}

/**
 * Настройки приложения: список баз и активная из них.
 *
 * Список не пуст по построению — [SettingsStore] всегда возвращает хотя бы основную базу,
 * поэтому «приложение без базы» невозможна и на старте не нужна отдельная ветка UI.
 */
data class AppSettings(
    val profiles: List<DatabaseProfile>,
    val activeProfileId: String,
) {
    init {
        require(profiles.isNotEmpty()) { "Список баз не может быть пустым" }
    }

    /**
     * Активная база. Если `activeProfileId` ссылается на удалённый профиль (файл настроек
     * правили руками), берётся первая — приложение должно запускаться в любом состоянии.
     */
    val active: DatabaseProfile
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()

    fun withActive(profileId: String): AppSettings {
        require(profiles.any { it.id == profileId }) { "База $profileId не найдена в списке" }
        return copy(activeProfileId = profileId)
    }

    /** Добавляет базу и сразу делает её активной: пользователь добавил её, чтобы работать. */
    fun withAdded(profile: DatabaseProfile): AppSettings {
        require(profiles.none { it.id == profile.id }) { "База с идентификатором ${profile.id} уже есть" }
        val clash = profiles.firstOrNull { it.path == profile.path }
        require(clash == null) { "База «${clash?.name.orEmpty()}» уже указывает на этот файл" }
        return copy(profiles = profiles + profile, activeProfileId = profile.id)
    }

    fun withRenamed(profileId: String, name: String): AppSettings {
        require(name.isNotBlank()) { "Название базы не может быть пустым" }
        return copy(
            profiles = profiles.map { if (it.id == profileId) it.copy(name = name.trim()) else it },
        )
    }

    /**
     * Убирает базу из списка настроек. Файл на диске остаётся.
     *
     * Активную и единственную базу убрать нельзя: иначе приложение осталось бы без
     * источника данных, а переключение — без цели.
     */
    fun without(profileId: String): AppSettings {
        require(profileId != activeProfileId) { "Сначала переключитесь на другую базу" }
        require(profiles.size > 1) { "Это единственная база в списке" }
        return copy(profiles = profiles.filterNot { it.id == profileId })
    }
}

/**
 * Хранилище настроек: `app.finance/settings.properties`.
 *
 * Формат — обычный properties-файл, который можно прочитать и поправить руками, если
 * приложение не запускается. Настройки живут ВНЕ любой из баз данных: иначе выбор базы
 * зависел бы от содержимого самой базы.
 *
 * Повреждённый или отсутствующий файл не является ошибкой запуска — [load] возвращает
 * базовые настройки с основной базой.
 */
class SettingsStore(
    private val file: Path,
    private val defaults: () -> AppSettings = ::defaultSettings,
) {

    fun load(): AppSettings = runCatching { read() }.getOrElse { defaults() }

    fun save(settings: AppSettings) {
        file.parent?.let { Files.createDirectories(it) }
        val props = Properties()
        props.setProperty(KEY_ACTIVE, settings.activeProfileId)
        // Порядок профилей сохраняется явно: Properties — неупорядоченная карта, а список
        // баз в настройках показывается в порядке добавления.
        props.setProperty(KEY_ORDER, settings.profiles.joinToString(SEPARATOR) { it.id })
        settings.profiles.forEach { profile ->
            props.setProperty(nameKey(profile.id), profile.name)
            props.setProperty(pathKey(profile.id), profile.path)
        }
        // UTF-8 Writer, а не OutputStream: Properties.store(OutputStream) кодирует
        // кириллицу как \uXXXX, и файл, который задуман редактируемым руками, становится
        // нечитаемым. load(Reader) читает тот же UTF-8 симметрично. Заголовок пишем сами:
        // в комментариях Properties экранирует не-Latin1 символы даже при записи в Writer.
        Files.newBufferedWriter(file, StandardCharsets.UTF_8).use { writer ->
            writer.write("# $HEADER\n")
            props.store(writer, null)
        }
    }

    private fun read(): AppSettings {
        if (!Files.exists(file)) return defaults()

        val props = Properties()
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader -> props.load(reader) }

        val ids = props.getProperty(KEY_ORDER).orEmpty()
            .split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val profiles = ids.mapNotNull { id ->
            val name = props.getProperty(nameKey(id))?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = props.getProperty(pathKey(id))?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DatabaseProfile(id = id, name = name, path = path)
        }
        if (profiles.isEmpty()) return defaults()

        val activeId = props.getProperty(KEY_ACTIVE)
            ?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id

        return AppSettings(profiles = profiles, activeProfileId = activeId)
    }

    private fun nameKey(id: String) = "$KEY_PREFIX$id.name"

    private fun pathKey(id: String) = "$KEY_PREFIX$id.path"

    companion object {

        /** Хранилище в штатном каталоге данных приложения. */
        fun default(): SettingsStore = SettingsStore(AppDataLocations.settingsFile())

        /**
         * Базовые настройки: одна «Основная» база в каталоге данных.
         *
         * Идентификатор `main` стабилен и не меняется между запусками, поэтому путь к
         * основной базе не «теряется» при обновлении приложения.
         */
        fun defaultSettings(): AppSettings {
            val path = AppDataLocations.defaultDatabase()
            return AppSettings(
                profiles = listOf(
                    DatabaseProfile(id = MAIN_PROFILE_ID, name = "Основная", path = path.toString()),
                ),
                activeProfileId = MAIN_PROFILE_ID,
            )
        }

        const val MAIN_PROFILE_ID = "main"

        private const val KEY_ACTIVE = "database.active"
        private const val KEY_ORDER = "database.profiles"
        private const val KEY_PREFIX = "database.profile."
        private const val SEPARATOR = ","
        private const val HEADER = "Настройки Finance. Файл можно править вручную при закрытом приложении."
    }
}
