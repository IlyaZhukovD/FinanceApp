package app.finance

import app.finance.core.data.CategorySeeder
import app.finance.core.data.DatabaseFactory
import app.finance.core.data.DatabaseSession
import app.finance.core.data.RandomUuidGenerator
import app.finance.core.data.SqlAssetRepository
import app.finance.core.data.SqlCategoryRepository
import app.finance.core.data.SqlDelightTransactionRunner
import app.finance.core.data.SqlLedgerRepository
import app.finance.core.data.SystemTimeProvider
import app.finance.core.domain.AssetSummary
import app.finance.core.domain.CloseAccount
import app.finance.core.domain.CreateAccount
import app.finance.core.domain.DeleteCell
import app.finance.core.domain.DuplicatePeriod
import app.finance.core.domain.EditCell
import app.finance.core.domain.RecordAmount
import app.finance.core.domain.RecordSnapshot
import app.finance.core.domain.RestoreCell
import app.finance.core.model.LedgerSection
import app.finance.core.model.Period
import app.finance.feature.assets.AssetsController
import app.finance.feature.ledger.LedgerController
import java.nio.file.Path
import java.nio.file.Paths
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
) : AutoCloseable {

    fun controller(section: LedgerSection): LedgerController =
        requireNotNull(controllers[section]) { "Контроллер для секции $section не создан" }

    /** Закрывает базу: SQLite держит файловые блокировки до конца процесса. */
    override fun close() {
        session.close()
    }

    companion object {

        suspend fun create(
            scope: CoroutineScope,
            ioDispatcher: CoroutineDispatcher,
            zone: ZoneId = ZoneId.systemDefault(),
            databasePath: Path = defaultDatabasePath(),
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
                recordSnapshot = RecordSnapshot(assets, ids, time, transactions),
                createAccount = CreateAccount(categories, assets, ids, time, transactions),
                closeAccount = CloseAccount(assets, time, transactions),
                assetSummary = AssetSummary(assets),
                initialPeriod = Period.now(clock, zone),
                clock = clock,
                zone = zone,
                scope = scope,
                ioDispatcher = ioDispatcher,
            )

            AppGraph(
                databasePath = databasePath,
                session = session,
                controllers = controllers,
                assets = assetsController,
            )
        }

        /**
         * Каталог данных приложения (SPEC.md §2):
         * macOS — `~/Library/Application Support/app.finance`,
         * Windows — `%APPDATA%\app.finance`.
         */
        fun defaultDatabasePath(): Path = appDataDirectory().resolve("finance.db")

        private fun appDataDirectory(): Path {
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

        private const val APP_DIRECTORY = "app.finance"
    }
}
