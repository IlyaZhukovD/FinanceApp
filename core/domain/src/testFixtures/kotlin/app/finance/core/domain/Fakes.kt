package app.finance.core.domain

import app.finance.core.model.DepositCapitalization
import app.finance.core.model.AssetAccount
import app.finance.core.model.AssetBalancePolicy
import app.finance.core.model.AssetSnapshot
import app.finance.core.model.Category
import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.CategoryMonthRevision
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period

class FakeTime(var now: Long = 1_770_000_000_000L) : TimeProvider {
    override fun nowUtcMillis(): Long = now
    fun advance(millis: Long) {
        now += millis
    }
}

class FakeIds(private val prefix: String = "id") : IdGenerator {
    private var counter = 0
    override fun newId(): String = "$prefix-${++counter}"
}

/**
 * Транзакция «как есть». Для домена важна не изоляция, а факт, что все операции
 * выполнены в одной границе транзакции — счётчик это проверяет.
 */
class DirectTransactionRunner : TransactionRunner {
    var transactionsStarted: Int = 0
        private set

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        transactionsStarted++
        return block()
    }
}

class FakeCategoryRepository(initial: List<Category> = emptyList()) : CategoryRepository {
    private val storage = initial.associateBy { it.id }.toMutableMap()

    fun put(category: Category) {
        storage[category.id] = category
    }

    override suspend fun byId(id: String): Category? = storage[id]

    override suspend fun bySection(section: CategorySection, includeArchived: Boolean): List<Category> =
        storage.values
            .filter { it.section == section && it.deletedAtUtc == null && (includeArchived || !it.isArchived) }
            .sortedBy { it.sortOrder }

    override suspend fun create(category: Category) {
        put(category)
    }

    override suspend fun update(category: Category) {
        require(storage.containsKey(category.id)) { "Категория ${category.id} отсутствует" }
        put(category)
    }

    override suspend fun setArchived(categoryId: String, archived: Boolean, updatedAtUtc: Long) {
        val existing = requireNotNull(storage[categoryId]) { "Категория $categoryId не найдена" }
        put(existing.copy(isArchived = archived, updatedAtUtc = updatedAtUtc, rowVersion = existing.rowVersion + 1))
    }
}

/**
 * Поведение повторяет контракт БД: уникальность живой ячейки на пару
 * (categoryId, period) и фильтрация tombstone из всех выборок.
 */
class FakeLedgerRepository : LedgerRepository {
    val cells = linkedMapOf<String, CategoryMonthCell>()
    val revisions = mutableListOf<CategoryMonthRevision>()

    private fun live(): List<CategoryMonthCell> = cells.values.filter { it.deletedAtUtc == null }

    override suspend fun findCell(categoryId: String, period: Period): CategoryMonthCell? =
        live().firstOrNull { it.categoryId == categoryId && it.period == period }

    override suspend fun cellsForPeriod(section: LedgerSection, period: Period): List<CategoryMonthCell> =
        live().filter { it.section == section && it.period == period }
            .sortedByDescending { it.amount.minor }

    override suspend fun cellsInRange(
        section: LedgerSection,
        from: Period,
        to: Period,
    ): List<CategoryMonthCell> =
        live().filter { it.section == section && it.period >= from && it.period <= to }
            // Порядок как в selectForRange: по месяцу, внутри месяца по убыванию суммы.
            .sortedWith(compareBy<CategoryMonthCell> { it.period }.thenByDescending { it.amount.minor })

    override suspend fun cellById(id: String): CategoryMonthCell? = cells[id]

    override suspend fun insertCell(cell: CategoryMonthCell) {
        val clash = live().firstOrNull {
            it.categoryId == cell.categoryId && it.period == cell.period && it.id != cell.id
        }
        check(clash == null) {
            "Нарушен инвариант уникальности: для ${cell.categoryId}/${cell.period} уже есть ячейка ${clash?.id}"
        }
        cells[cell.id] = cell
    }

    override suspend fun updateCell(cell: CategoryMonthCell) {
        require(cells.containsKey(cell.id)) { "Ячейка ${cell.id} отсутствует в хранилище" }
        cells[cell.id] = cell
    }

    override suspend fun revisionsOf(cellId: String): List<CategoryMonthRevision> =
        revisions.filter { it.cellId == cellId }.sortedBy { it.createdAtUtc }

    override suspend fun insertRevision(revision: CategoryMonthRevision) {
        revisions += revision
    }

    override suspend fun revisionByIdempotencyKey(key: String): CategoryMonthRevision? =
        revisions.firstOrNull { it.idempotencyKey == key }

    override suspend fun revisionById(id: String): CategoryMonthRevision? =
        revisions.firstOrNull { it.id == id }
}

/**
 * Поведение повторяет контракт БД активов: уникальность живого снапшота на пару
 * (accountId, period) — частичный индекс `uq_asset_snapshot_active`, фильтрация
 * tombstone и сортировка выборок как в `Asset.sq`.
 */
class FakeAssetRepository : AssetRepository {
    val accountsById = linkedMapOf<String, AssetAccount>()
    val snapshotsById = linkedMapOf<String, AssetSnapshot>()

    private fun liveAccounts(): List<AssetAccount> = accountsById.values.filter { it.deletedAtUtc == null }

    private fun liveSnapshots(): List<AssetSnapshot> = snapshotsById.values.filter { it.deletedAtUtc == null }

    fun put(account: AssetAccount) {
        accountsById[account.id] = account
    }

    fun put(snapshot: AssetSnapshot) {
        snapshotsById[snapshot.id] = snapshot
    }

    override suspend fun accountById(id: String): AssetAccount? = accountsById[id]

    override suspend fun accounts(includeClosed: Boolean): List<AssetAccount> =
        liveAccounts()
            .filter { includeClosed || it.closedPeriod == null }
            .sortedWith(compareByDescending<AssetAccount> { it.closedPeriod != null }.thenBy { it.name })

    override suspend fun insertAccount(account: AssetAccount) {
        check(!accountsById.containsKey(account.id)) { "Счёт ${account.id} уже есть в хранилище" }
        put(account)
    }

    override suspend fun updateAccount(account: AssetAccount) {
        require(accountsById.containsKey(account.id)) { "Счёт ${account.id} отсутствует в хранилище" }
        put(account)
    }

    override suspend fun closeAccount(accountId: String, closedPeriod: Period, updatedAtUtc: Long) {
        val existing = requireNotNull(accountsById[accountId]) { "Счёт $accountId не найден" }
        put(
            existing.copy(
                closedPeriod = closedPeriod,
                updatedAtUtc = updatedAtUtc,
                rowVersion = existing.rowVersion + 1,
            ),
        )
    }

    override suspend fun findSnapshot(accountId: String, period: Period): AssetSnapshot? =
        liveSnapshots().firstOrNull { it.accountId == accountId && it.period == period }

    override suspend fun snapshotsForPeriod(period: Period): List<AssetSnapshot> =
        liveSnapshots().filter { it.period == period }.sortedByDescending { it.balance.minor }

    override suspend fun snapshotsByAccount(accountId: String): List<AssetSnapshot> =
        liveSnapshots().filter { it.accountId == accountId }.sortedBy { it.period }

    override suspend fun snapshotsInRange(from: Period, to: Period): List<AssetSnapshot> =
        liveSnapshots().filter { it.period >= from && it.period <= to }
            // Порядок как в selectSnapshotsInRange: по месяцу.
            .sortedWith(compareBy<AssetSnapshot> { it.period }.thenByDescending { it.balance.minor })

    override suspend fun snapshotsUpTo(period: Period): List<AssetSnapshot> =
        liveSnapshots().filter { it.period <= period }
            // Порядок как в selectSnapshotsUpTo: по месяцу.
            .sortedBy { it.period }

    override suspend fun insertSnapshot(snapshot: AssetSnapshot) {
        val clash = liveSnapshots().firstOrNull {
            it.accountId == snapshot.accountId && it.period == snapshot.period && it.id != snapshot.id
        }
        check(clash == null) {
            "Нарушен инвариант уникальности: для ${snapshot.accountId}/${snapshot.period} " +
                "уже есть снапшот ${clash?.id}"
        }
        snapshotsById[snapshot.id] = snapshot
    }

    override suspend fun updateSnapshot(snapshot: AssetSnapshot) {
        require(snapshotsById.containsKey(snapshot.id)) { "Снапшот ${snapshot.id} отсутствует в хранилище" }
        snapshotsById[snapshot.id] = snapshot
    }
}

/** Общая обвязка тестов: фейки и собранные на них use-case'ы. */
class DomainFixture {
    val time = FakeTime()
    val ids = FakeIds()
    val transactions = DirectTransactionRunner()
    val categories = FakeCategoryRepository()
    val ledger = FakeLedgerRepository()
    val assets = FakeAssetRepository()

    val recordAmount = RecordAmount(categories, ledger, ids, time, transactions)
    val editCell = EditCell(ledger, ids, time, transactions)
    val deleteCell = DeleteCell(ledger, ids, time, transactions)
    val restoreCell = RestoreCell(ledger, ids, time, transactions)
    val duplicatePeriod = DuplicatePeriod(categories, ledger, ids, time, transactions)
    val recordSnapshot = RecordSnapshot(assets, categories, ids, time, transactions)
    val createAccount = CreateAccount(categories, assets, ids, time, transactions)
    val closeAccount = CloseAccount(assets, time, transactions)
    val assetSummary = AssetSummary(assets, categories)

    private var created = 0

    /** Регистрирует категорию в фейковом репозитории и возвращает её. */
    fun category(
        id: String,
        name: String = id,
        section: CategorySection = CategorySection.EXPENSE,
        archived: Boolean = false,
        balancePolicy: AssetBalancePolicy = AssetBalancePolicy.DEFAULT,
        allowsNegativeBalance: Boolean = false,
    ): Category {
        val category = Category(
            id = id,
            section = section,
            name = name,
            color = Category.defaultColor(created++),
            balancePolicy = balancePolicy,
            allowsNegativeBalance = allowsNegativeBalance,
            isArchived = archived,
            createdAtUtc = time.now,
            updatedAtUtc = time.now,
        )
        categories.put(category)
        return category
    }

    /** Регистрирует счёт активов напрямую — в обход `CreateAccount`, для подготовки сценария. */
    fun account(
        id: String,
        name: String = id,
        categoryId: String = "assets",
        currency: String = Money.DEFAULT_CURRENCY,
        openedPeriod: Period = Period(2026, 1),
        closedPeriod: Period? = null,
        annualRateBp: Int? = null,
        termMonths: Int? = null,
        interestDay: Int? = null,
        capitalization: DepositCapitalization = DepositCapitalization.MONTHLY,
    ): AssetAccount {
        val account = AssetAccount(
            id = id,
            categoryId = categoryId,
            name = name,
            currency = currency,
            openedPeriod = openedPeriod,
            closedPeriod = closedPeriod,
            annualRateBp = annualRateBp,
            termMonths = termMonths,
            interestDay = interestDay,
            capitalization = capitalization,
            createdAtUtc = time.now,
            updatedAtUtc = time.now,
        )
        assets.put(account)
        return account
    }

    /** Регистрирует снапшот напрямую — в обход `RecordSnapshot`. */
    fun snapshot(
        accountId: String,
        period: Period,
        balanceMinor: Long,
        contributionMinor: Long? = null,
        currency: String = Money.DEFAULT_CURRENCY,
        id: String = "$accountId-${period.key}",
    ): AssetSnapshot {
        val snapshot = AssetSnapshot(
            id = id,
            accountId = accountId,
            period = period,
            balance = Money(balanceMinor, currency),
            contribution = contributionMinor?.let { Money(it, currency) },
            createdAtUtc = time.now,
            updatedAtUtc = time.now,
        )
        assets.put(snapshot)
        return snapshot
    }
}
