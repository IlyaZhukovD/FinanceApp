package app.finance

import app.finance.core.data.AppDataLocations
import app.finance.core.data.CategorySeeder
import app.finance.core.data.DatabaseFactory
import app.finance.core.data.DatabaseSession
import app.finance.core.data.RandomUuidGenerator
import app.finance.core.data.SqlAssetRepository
import app.finance.core.data.SqlCategoryRepository
import app.finance.core.data.SqlImportBatchRepository
import app.finance.core.data.snapshotter
import app.finance.core.data.SqlDelightTransactionRunner
import app.finance.core.data.SqlLedgerRepository
import app.finance.core.data.SqlSyncStore
import app.finance.core.data.BackupEntry
import app.finance.core.data.LocalBackupService
import app.finance.core.data.CURRENT_SCHEMA_VERSION
import app.finance.core.data.localBackups
import app.finance.core.network.PlatformSecretStore
import app.finance.core.network.YandexDiskTransport
import app.finance.core.sync.SyncConfig
import app.finance.core.sync.SyncEngine
import app.finance.feature.settings.BackupController
import app.finance.feature.sync.SyncController
import app.finance.feature.sync.YandexAccount
import app.finance.core.data.SystemTimeProvider
import app.finance.core.domain.AssetSummary
import app.finance.core.domain.CloseAccount
import app.finance.core.domain.CreateAccount
import app.finance.core.domain.CreateCategory
import app.finance.core.domain.DeleteCell
import app.finance.core.domain.DuplicatePeriod
import app.finance.core.domain.EditCell
import app.finance.core.domain.ImportLedgerBatch
import app.finance.core.domain.RecordAmount
import app.finance.core.domain.RecordSnapshot
import app.finance.core.domain.RestoreCell
import app.finance.core.domain.SetCategoryArchived
import app.finance.core.domain.UndoImportBatch
import app.finance.core.domain.UpdateCategory
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Period
import app.finance.feature.analytics.AnalyticsController
import app.finance.feature.assets.AssetsController
import app.finance.feature.categories.CategoriesController
import app.finance.feature.importing.ImportController
import app.finance.feature.ledger.LedgerController
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * Граф зависимостей приложения.
 *
 * В v1 собирается вручную, без DI-фреймворка (docs/DECISIONS.md, D-10): объектов
 * немного, а явная сборка видна в одном файле. Все use-case'ы создаются один раз и
 * переиспользуются контроллерами разделов.
 *
 * Создание — suspend-функция: открытие базы и посев стартовых категорий не должны
 * блокировать поток UI (SPEC.md §7).
 */
class AppGraph private constructor(
    /** Фактически открытый путь к базе: нужен для диагностики и экрана ошибки запуска. */
    val databasePath: Path,
    private val session: DatabaseSession,
    private val controllers: Map<LedgerSection, LedgerController>,
    /**
     * Раздел «Активы»: один контроллер на раздел, потому что у активов своя единица
     * учёта — снапшот счёта, а не ячейка «категория + месяц» (SPEC.md F4, D-12).
     */
    val assets: AssetsController,
    /**
     * Раздел «Аналитика»: read-only срез тех же репозиториев. Отдельный контроллер, а не
     * расширение ledger, потому что единица учёта здесь — диапазон месяцев, а расчёты
     * живут в `core:analytics` (SPEC.md F6.4).
     */
    val analytics: AnalyticsController,
    /**
     * Управление категориями (SPEC.md F3.1): по контроллеру на секцию — расходы, доходы
     * и активы. Диалог один и тот же, различается только набором категорий раздела.
     */
    private val categoryManagers: Map<CategorySection, CategoriesController>,
    /** Мастер импорта из Notion (F10.1, docs/IMPORT-NOTION.md). */
    val importer: ImportController,
    /** Синхронизация через Яндекс.Диск (docs/SYNC-YANDEX.md, D-29). */
    val sync: SyncController,
    private val syncEngine: SyncEngine,
    /** Локальные бэкапы (SPEC.md F7, D-30). */
    val backupService: LocalBackupService,
    val backups: BackupController,
) : AutoCloseable {

    fun controller(section: LedgerSection): LedgerController =
        requireNotNull(controllers[section]) { "Контроллер для секции $section не создан" }

    /** Контроллер управления категориями раздела (F3.1, F3.2). */
    fun categories(section: CategorySection): CategoriesController =
        requireNotNull(categoryManagers[section]) { "Контроллер категорий для секции $section не создан" }

    /** Закрывает базу: SQLite держит файловые блокировки до конца процесса. */
    override fun close() {
        sync.stop()
        backups.stop()
        session.close()
    }

    /**
     * Сразу после открытия базы, восстановленной из снапшота (F7.7): её номера пакетов
     * синхронизации устарели, поэтому компьютер заново читает Диск под новым именем.
     */
    suspend fun afterRestore() {
        if (syncEngine.isEnabled) syncEngine.resetAfterRestore()
    }

    companion object {

        suspend fun create(
            scope: CoroutineScope,
            ioDispatcher: CoroutineDispatcher,
            zone: ZoneId = ZoneId.systemDefault(),
            databasePath: Path = defaultDatabasePath(),
            /** Каталог данных приложения: секреты и Client ID Яндекса (D-28). */
            dataDirectory: Path = AppDataLocations.directory(),
            isPackaged: Boolean = AppDataLocations.isPackaged,
            /** Восстановление из бэкапа: хост закрывает граф, подменяет файл и открывает заново; null — успех. */
            restore: suspend (AppGraph, BackupEntry) -> String? = { _, _ -> "восстановление недоступно" },
        ): AppGraph = withContext(ioDispatcher) {
            val session = DatabaseFactory.openFile(databasePath)
            val clock = Clock.system(zone)
            val time = SystemTimeProvider()
            val ids = RandomUuidGenerator()
            val transactions = SqlDelightTransactionRunner(session.database)

            val categories = SqlCategoryRepository(session.database)
            val ledger = SqlLedgerRepository(session.database, ids)

            // Идемпотентно: при повторном запуске ничего не создаёт (SPEC.md §6, «Seed»).
            CategorySeeder(categories, ids, time).seedIfEmpty()

            val recordAmount = RecordAmount(categories, ledger, ids, time, transactions)
            val editCell = EditCell(ledger, ids, time, transactions)
            val deleteCell = DeleteCell(ledger, ids, time, transactions)
            val restoreCell = RestoreCell(ledger, ids, time, transactions)
            val duplicatePeriod = DuplicatePeriod(categories, ledger, ids, time, transactions)

            // Категории (F3.1): создание, правка имени и цвета, архивация вместо удаления (F3.2).
            val createCategory = CreateCategory(categories, ids, time)
            val updateCategory = UpdateCategory(categories, time)
            val setCategoryArchived = SetCategoryArchived(categories, time)

            val controllers = LedgerSection.entries.associateWith { section ->
                LedgerController(
                    section = section,
                    categories = categories,
                    ledger = ledger,
                    recordAmount = recordAmount,
                    editCell = editCell,
                    deleteCell = deleteCell,
                    restoreCell = restoreCell,
                    duplicatePeriod = duplicatePeriod,
                    initialPeriod = Period.now(clock, zone),
                    clock = clock,
                    zone = zone,
                    scope = scope,
                    ioDispatcher = ioDispatcher,
                )
            }

            // Активы — отдельная ветка графа: свой репозиторий и свои use-case'ы (SPEC.md F4).
            val assets = SqlAssetRepository(session.database, ids)
            val assetsController = AssetsController(
                assets = assets,
                categories = categories,
                recordSnapshot = RecordSnapshot(assets, categories, ids, time, transactions),
                createAccount = CreateAccount(categories, assets, ids, time, transactions),
                closeAccount = CloseAccount(assets, time, transactions),
                assetSummary = AssetSummary(assets, categories),
                initialPeriod = Period.now(clock, zone),
                clock = clock,
                zone = zone,
                scope = scope,
                ioDispatcher = ioDispatcher,
            )

            // Аналитика читает те же репозитории, что и разделы ввода, но не мутирует их:
            // ни ревизий, ни записей в sync_outbox (F6.4).
            val analyticsController = AnalyticsController(
                ledger = ledger,
                assets = assets,
                categories = categories,
                initialPeriod = Period.now(clock, zone),
                clock = clock,
                zone = zone,
                scope = scope,
                ioDispatcher = ioDispatcher,
            )

            // Диалог управления категориями один на три раздела, поэтому контроллер
            // создаётся на каждую секцию, а не на каждый экран (F2.2, F3.1).
            val categoryManagers = CategorySection.entries.associateWith { categorySection ->
                CategoriesController(
                    section = categorySection,
                    categories = categories,
                    createCategory = createCategory,
                    updateCategory = updateCategory,
                    setArchived = setCategoryArchived,
                    scope = scope,
                    ioDispatcher = ioDispatcher,
                )
            }

            // Импорт из Notion (F10.1): снапшот перед записью кладётся в backups/ рядом с базой.
            val backupsDirectory = (databasePath.toAbsolutePath().parent ?: databasePath).resolve("backups")
            val importBatches = SqlImportBatchRepository(session.database, ids, time)
            val snapshotter = session.snapshotter(
                backupsDirectory = backupsDirectory,
                time = time,
            )
            val importer = ImportController(
                categories = categories,
                batches = importBatches,
                importLedger = ImportLedgerBatch(categories, ledger, importBatches, snapshotter, ids, time, transactions),
                undoImport = UndoImportBatch(categories, ledger, importBatches, ids, time, transactions),
                clock = clock,
                zone = zone,
                scope = scope,
                ioDispatcher = ioDispatcher,
                // Суммы месяцев изменились: перечитываем разделы потоков и аналитику.
                onImported = {
                    controllers.values.forEach { it.start() }
                    analyticsController.start()
                },
            )

            // Синхронизация (docs/SYNC-YANDEX.md): у сборки для разработки своя папка на Диске
            // и свои секреты (D-28), поэтому эксперименты не доезжают до рабочих компьютеров.
            val secrets = PlatformSecretStore(
                directory = dataDirectory,
                namespace = if (isPackaged) "app.finance" else "app.finance.dev",
            )
            val account = YandexAccount(dataDirectory, secrets)
            val syncEngine = SyncEngine(
                store = SqlSyncStore(session.database, time, transactions),
                transport = YandexDiskTransport(
                    token = account::accessToken,
                    root = if (isPackaged) YandexDiskTransport.ROOT else YandexDiskTransport.DEV_ROOT,
                ),
                snapshotter = snapshotter,
                ids = ids,
                time = time,
                config = SyncConfig(
                    schemaVersion = CURRENT_SCHEMA_VERSION,
                    appVersion = APP_VERSION,
                    deviceName = deviceName(),
                    os = System.getProperty("os.name").orEmpty(),
                ),
            )
            val syncController = SyncController(
                engine = syncEngine,
                account = account,
                scope = scope,
                ioDispatcher = ioDispatcher,
                folderLabel = if (isPackaged) "Приложения/Finance" else "Приложения/Finance/dev",
                isDevBuild = !isPackaged,
                // Пришли изменения с другого компьютера: перечитываем все разделы.
                onReceived = {
                    controllers.values.forEach { it.start() }
                    assetsController.start()
                    analyticsController.start()
                },
            )

            val backupService = session.localBackups(backupsDirectory, time, APP_VERSION, zone)
            lateinit var graph: AppGraph
            val backupController = BackupController(
                service = backupService,
                scope = scope,
                ioDispatcher = ioDispatcher,
                restore = { entry -> restore(graph, entry) },
            )

            graph = AppGraph(
                databasePath = databasePath,
                session = session,
                controllers = controllers,
                assets = assetsController,
                analytics = analyticsController,
                categoryManagers = categoryManagers,
                importer = importer,
                sync = syncController,
                syncEngine = syncEngine,
                backupService = backupService,
                backups = backupController,
            )
            graph
        }

        /** Версия приложения (SPEC.md §2); пишется в пакеты синхронизации и журнал бэкапов. */
        const val APP_VERSION = "1.0.0"

        /** Имя компьютера в сообщениях синхронизации («более новая версия на …»). */
        private fun deviceName(): String =
            System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
                ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.removeSuffix(".local")
                ?: "компьютер"

        /**
         * Путь основной базы в каталоге данных приложения (SPEC.md §2):
         * macOS — `~/Library/Application Support/app.finance`,
         * Windows — `%APPDATA%\app.finance`.
         *
         * Определение каталога живёт в `core:data` (`AppDataLocations`): его же читает
         * хранилище настроек, поэтому двух версий правила для платформ в проекте нет.
         */
        fun defaultDatabasePath(): Path = AppDataLocations.defaultDatabase()
    }
}
