package app.finance.core.data

import app.finance.core.domain.AccountInput
import app.finance.core.domain.AmountInput
import app.finance.core.domain.CategoryTarget
import app.finance.core.domain.CommentChange
import app.finance.core.domain.ConflictPolicy
import app.finance.core.domain.CreateAccount
import app.finance.core.domain.CreateCategory
import app.finance.core.domain.DeleteCell
import app.finance.core.domain.DuplicateOptions
import app.finance.core.domain.DuplicatePeriod
import app.finance.core.domain.EditCell
import app.finance.core.domain.IdGenerator
import app.finance.core.domain.ImportLedgerBatch
import app.finance.core.domain.ImportRequest
import app.finance.core.domain.ImportRowInput
import app.finance.core.domain.RecordAmount
import app.finance.core.domain.RecordSnapshot
import app.finance.core.domain.RestoreCell
import app.finance.core.domain.SnapshotInput
import app.finance.core.domain.UndoImportBatch
import app.finance.core.domain.UpdateCategory
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import app.finance.core.sync.CellReplay
import app.finance.core.sync.SyncConfig
import app.finance.core.sync.SyncEngine
import app.finance.core.sync.SyncOutcome
import app.finance.core.sync.SyncProblem
import app.finance.core.sync.SyncTransport
import app.finance.core.sync.SyncTransportException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Хранилище «Диска» в памяти, общее для нескольких компьютеров теста. */
class MemoryTransport : SyncTransport {
    val files = sortedMapOf<String, String>()
    var failWrites = 0

    override suspend fun read(path: String): String? = files[path]

    override suspend fun write(path: String, content: String) {
        if (failWrites > 0) {
            failWrites--
            throw SyncTransportException(SyncTransportException.Kind.NETWORK, "обрыв связи")
        }
        files[path] = content
    }

    override suspend fun list(path: String): List<String>? {
        val prefix = "$path/"
        val names = files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
        return names.ifEmpty { null }
    }

    override suspend fun delete(path: String) {
        files.keys.filter { it == path || it.startsWith("$path/") }.toList().forEach { files.remove(it) }
    }
}

/** UUID, как в приложении: от порядка id зависит выбор общего id при слиянии. */
private class UuidIds : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

/** Один «компьютер»: своя база в памяти и свой движок над общим «Диском». */
private class Device(name: String, transport: SyncTransport, val time: FixedTime, schemaVersion: Long = 6) : AutoCloseable {
    val session = DatabaseFactory.openInMemory()
    val db = session.database
    val ids = UuidIds()
    val transactions = SqlDelightTransactionRunner(db)
    val categories = SqlCategoryRepository(db, ids)
    val ledger = SqlLedgerRepository(db, ids)
    val assets = SqlAssetRepository(db, ids)
    val record = RecordAmount(categories, ledger, ids, time, transactions)
    val edit = EditCell(ledger, ids, time, transactions)
    val delete = DeleteCell(ledger, ids, time, transactions)
    val restore = RestoreCell(ledger, ids, time, transactions)
    val duplicate = DuplicatePeriod(categories, ledger, ids, time, transactions)
    val createCategory = CreateCategory(categories, ids, time)
    val updateCategory = UpdateCategory(categories, time)
    val createAccount = CreateAccount(categories, assets, ids, time, transactions)
    val recordSnapshot = RecordSnapshot(assets, categories, ids, time, transactions)
    val batches = SqlImportBatchRepository(db, ids, time)
    var snapshots = 0
    val import = ImportLedgerBatch(categories, ledger, batches, { snapshots++; "mem" }, ids, time, transactions)
    val undoImport = UndoImportBatch(categories, ledger, batches, ids, time, transactions)
    val store = SqlSyncStore(db, time)
    val engine = SyncEngine(
        store, transport, { snapshots++; "mem" }, ids, time,
        SyncConfig(schemaVersion = schemaVersion, appVersion = "1.0.0", deviceName = name, os = "test"),
    )

    suspend fun seed() = CategorySeeder(categories, ids, time).seedIfEmpty()

    suspend fun category(name: String, section: CategorySection = CategorySection.EXPENSE) =
        categories.bySection(section, includeArchived = true).first { it.name == name }

    suspend fun add(categoryName: String, period: Period, rubles: Long, comment: String? = null) {
        val category = category(categoryName)
        time.advance(1_000)
        record(AmountInput(LedgerSection.EXPENSE, category.id, period, Money.ofUnits(rubles), comment))
    }

    suspend fun cell(categoryName: String, period: Period) = ledger.findCell(category(categoryName).id, period)

    override fun close() = session.close()
}

class SyncEngineTest {

    private val disk = MemoryTransport()
    private val clockA = FixedTime(1_800_000_000_000L)
    private val clockB = FixedTime(1_800_000_000_000L)
    private val a = Device("Ноутбук", disk, clockA)
    private val b = Device("Мак", disk, clockB)
    private val jan = Period(2026, 1)
    private val feb = Period(2026, 2)

    @AfterTest
    fun tearDown() {
        a.close()
        b.close()
    }

    private suspend fun ok(outcome: SyncOutcome): SyncOutcome.Done = assertIs<SyncOutcome.Done>(outcome, "итог: $outcome")

    /** Первый компьютер с данными, второй подключается с пустой базой. */
    private suspend fun connected() {
        a.seed()
        b.seed()
        a.add("Продукты", jan, 1000, "магазин")
        ok(a.engine.enable())
        ok(b.engine.enable())
    }

    @Test
    fun `второй компьютер получает данные первого, стартовые категории сливаются`() = runTest {
        connected()

        assertEquals(Money.ofUnits(1000), b.cell("Продукты", jan)?.amount)
        assertEquals("магазин", b.cell("Продукты", jan)?.comment)
        // Категории не задвоились: на B те же 22 стартовые категории, что и на A.
        assertEquals(
            a.categories.bySection(CategorySection.EXPENSE).map { it.name }.sorted(),
            b.categories.bySection(CategorySection.EXPENSE).map { it.name }.sorted(),
        )
        assertEquals(a.category("Продукты").id, b.category("Продукты").id)
    }

    @Test
    fun `прибавки с двух компьютеров складываются без потерь`() = runTest {
        connected()
        // Оба компьютера без сети: A и B добавляют в одну ячейку и в новую.
        a.add("Продукты", jan, 300)
        b.add("Продукты", jan, 500)
        a.add("Транспорт", feb, 70)
        b.add("Транспорт", feb, 30)

        ok(a.engine.sync())
        ok(b.engine.sync())
        ok(a.engine.sync())

        for (device in listOf(a, b)) {
            assertEquals(Money.ofUnits(1800), device.cell("Продукты", jan)?.amount)
            assertEquals(Money.ofUnits(100), device.cell("Транспорт", feb)?.amount)
        }
        // Ячейка «Транспорт, февраль» — одна, хотя создана на двух компьютерах.
        assertEquals(1, b.ledger.cellsForPeriod(LedgerSection.EXPENSE, feb).size)
    }

    @Test
    fun `после восстановления из бэкапа компьютер перечитывает Диск без задвоений`() = runTest {
        connected()
        a.add("Продукты", jan, 300)
        b.add("Транспорт", feb, 70)
        ok(a.engine.sync())
        ok(b.engine.sync())
        ok(a.engine.sync())
        val oldId = a.engine.deviceId

        a.engine.resetAfterRestore()
        val again = ok(a.engine.sync())
        assertTrue(again.received > 0, "все пакеты перечитаны, включая свои прежние")
        assertTrue(a.engine.deviceId != oldId)
        assertEquals(Money.ofUnits(1300), a.cell("Продукты", jan)?.amount)
        assertEquals(Money.ofUnits(70), a.cell("Транспорт", feb)?.amount)

        // Новые изменения уходят под новым именем и не затирают прежние пакеты.
        a.add("Продукты", jan, 5)
        ok(a.engine.sync())
        ok(b.engine.sync())
        assertEquals(Money.ofUnits(1305), b.cell("Продукты", jan)?.amount)
        assertEquals(Money.ofUnits(70), b.cell("Транспорт", feb)?.amount)
        assertEquals(3, disk.list("devices")!!.size)
    }

    @Test
    fun `правка суммы и удаление — кто последний, тот и прав`() = runTest {
        connected()
        val cellA = assertNotNull(a.cell("Продукты", jan))
        clockA.advance(10_000)
        a.edit(cellA.id, amount = Money.ofUnits(700), comment = CommentChange.Set("исправлено"))
        ok(a.engine.sync())
        ok(b.engine.sync())
        assertEquals(Money.ofUnits(700), b.cell("Продукты", jan)?.amount)
        assertEquals("исправлено", b.cell("Продукты", jan)?.comment)

        clockB.advance(20_000)
        b.delete(assertNotNull(b.cell("Продукты", jan)).id)
        ok(b.engine.sync())
        ok(a.engine.sync())
        assertNull(a.cell("Продукты", jan))

        clockA.advance(30_000)
        a.restore(cellA.id)
        ok(a.engine.sync())
        ok(b.engine.sync())
        assertEquals(Money.ofUnits(700), b.cell("Продукты", jan)?.amount)
    }

    @Test
    fun `одноимённые новые категории сливаются, переименование доезжает`() = runTest {
        connected()
        a.createCategory(CategorySection.EXPENSE, "Кошка")
        b.createCategory(CategorySection.EXPENSE, "кошка")
        a.add("Кошка", jan, 100)
        b.add("кошка", jan, 50)

        ok(a.engine.sync())
        ok(b.engine.sync())
        ok(a.engine.sync())

        for (device in listOf(a, b)) {
            val cats = device.categories.bySection(CategorySection.EXPENSE).filter { it.name.equals("кошка", true) }
            assertEquals(1, cats.size, "категорий «кошка» на ${device.engine}")
            assertEquals(Money.ofUnits(150), device.ledger.findCell(cats.single().id, jan)?.amount)
        }

        // Переименование на A позже правок B — побеждает A.
        val food = a.category("Продукты")
        clockA.advance(60_000)
        a.updateCategory(food.id, "Еда", "#117733", food.balancePolicy, food.allowsNegativeBalance)
        ok(a.engine.sync())
        ok(b.engine.sync())
        assertEquals("Еда", b.categories.byId(food.id)?.name)
        assertEquals("#117733", b.categories.byId(food.id)?.color)
    }

    @Test
    fun `счета и снапшоты активов`() = runTest {
        connected()
        val deposits = a.category("Вклады", CategorySection.ASSET)
        val account = a.createAccount(AccountInput(deposits.id, "Сбер", openedPeriod = jan, annualRateBp = 1600, termMonths = 12))
        a.recordSnapshot(SnapshotInput(account.id, jan, Money.ofUnits(100_000)))
        ok(a.engine.sync())
        ok(b.engine.sync())
        assertEquals(Money.ofUnits(100_000), b.assets.findSnapshot(account.id, jan)?.balance)

        clockB.advance(5_000)
        b.recordSnapshot(SnapshotInput(account.id, jan, Money.ofUnits(101_000)))
        ok(b.engine.sync())
        ok(a.engine.sync())
        assertEquals(Money.ofUnits(101_000), a.assets.findSnapshot(account.id, jan)?.balance)
        assertEquals(12, a.assets.accountById(account.id)?.termMonths)
    }

    @Test
    fun `второй компьютер с данными не подключается`() = runTest {
        a.seed()
        b.seed()
        a.add("Продукты", jan, 10)
        b.add("Продукты", jan, 20)
        ok(a.engine.enable())
        val outcome = assertIs<SyncOutcome.Failed>(b.engine.enable())
        assertEquals(SyncProblem.LocalNotEmpty, outcome.problem)
        assertEquals(Money.ofUnits(20), b.cell("Продукты", jan)?.amount)
    }

    @Test
    fun `хранилище удалили — остановка, затем пересоздание`() = runTest {
        connected()
        disk.files.clear()
        assertEquals(SyncProblem.RemoteMissing, assertIs<SyncOutcome.Failed>(a.engine.sync()).problem)

        ok(a.engine.recreateRemote())
        assertEquals(SyncProblem.RemoteReplaced, assertIs<SyncOutcome.Failed>(b.engine.sync()).problem)
    }

    @Test
    fun `более новая версия на другом компьютере блокирует синхронизацию`() = runTest {
        val newer = Device("Новый", disk, FixedTime(), schemaVersion = 7)
        try {
            connected()
            newer.seed()
            ok(newer.engine.enable())
            val problem = assertIs<SyncOutcome.Failed>(a.engine.sync()).problem
            assertEquals(SyncProblem.NeedsUpdate("Новый"), problem)
        } finally {
            newer.close()
        }
    }

    @Test
    fun `оборванная отправка повторяется тем же пакетом`() = runTest {
        connected()
        a.add("Продукты", jan, 5)
        disk.failWrites = 1
        assertIs<SyncOutcome.Failed>(a.engine.sync())
        ok(a.engine.sync())
        ok(b.engine.sync())
        assertEquals(Money.ofUnits(1005), b.cell("Продукты", jan)?.amount)
        // Повторная синхронизация без изменений ничего не отправляет и не меняет.
        assertEquals(SyncOutcome.Done(0, 0), a.engine.sync())
        assertEquals(0, a.engine.pendingCount())
    }

    @Test
    fun `повтор ревизий воспроизводит значение ячейки при любых операциях`() = runTest {
        a.seed()
        a.add("Продукты", jan, 100, "первый")
        a.add("Продукты", jan, 50, "второй")
        val cell = assertNotNull(a.cell("Продукты", jan))
        a.edit(cell.id, amount = Money.ofUnits(120), comment = CommentChange.Set("правка"))
        a.duplicate(LedgerSection.EXPENSE, jan, feb, DuplicateOptions(ConflictPolicy.SKIP, copyComments = true))
        a.add("Продукты", feb, 10)
        a.duplicate(LedgerSection.EXPENSE, jan, feb, DuplicateOptions(ConflictPolicy.ADD))
        a.duplicate(LedgerSection.EXPENSE, jan, feb, DuplicateOptions(ConflictPolicy.OVERWRITE))
        a.add("Транспорт", jan, 40)
        a.delete(assertNotNull(a.cell("Транспорт", jan)).id)
        val report = a.import(
            ImportRequest(
                emptyList(),
                listOf(
                    ImportRowInput("k1", LedgerSection.EXPENSE, CategoryTarget.New("Продукты"), jan, Money.ofUnits(7)),
                    ImportRowInput("k2", LedgerSection.EXPENSE, CategoryTarget.New("Новая"), jan, Money.ofUnits(9)),
                ),
                rowsTotal = 2,
            ),
        )
        a.undoImport(assertNotNull(report.batch).id)

        val all = a.ledger.cellsInRange(LedgerSection.EXPENSE, jan, feb) +
            listOfNotNull(a.ledger.cellById(assertNotNull(a.db.syncApplyQueries.cellByKeyAny(a.category("Транспорт").id, jan.key.toLong()).executeAsOneOrNull()).id))
        assertTrue(all.size >= 3)
        for (c in all) {
            val replayed = CellReplay.replay(a.ledger.revisionsOf(c.id), c.amount.currency)
            assertEquals(c.amount, replayed.amount, "сумма ячейки ${c.period} ${c.categoryId}")
            assertEquals(c.comment, replayed.comment, "комментарий ячейки ${c.period}")
            assertEquals(c.isDeleted, replayed.deleted, "удаление ячейки ${c.period}")
        }
    }
}
