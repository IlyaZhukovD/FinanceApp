package app.finance.core.data

import app.finance.core.data.db.FinanceDatabase
import app.finance.core.model.AssetAccount
import app.finance.core.model.AssetSnapshot
import app.finance.core.model.Category
import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.CategoryMonthRevision
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import app.finance.core.model.RevisionOp
import app.finance.core.model.RevisionSource
import app.finance.core.sync.OutboxEntry
import app.finance.core.sync.SyncChange
import app.finance.core.sync.SyncEntity
import app.finance.core.sync.SyncStore
import app.finance.core.domain.TimeProvider
import app.finance.core.domain.TransactionRunner

/**
 * Локальная база для движка синхронизации (docs/SYNC-YANDEX.md, D-29).
 *
 * Пишет строки напрямую, мимо репозиториев: применённые чужие изменения не попадают в
 * sync_outbox. Как и остальные репозитории `core:data`, работает синхронно.
 */
class SqlSyncStore(
    private val database: FinanceDatabase,
    private val time: TimeProvider,
    private val transactions: TransactionRunner = SqlDelightTransactionRunner(database),
) : SyncStore {

    private val sync get() = database.syncQueries
    private val apply get() = database.syncApplyQueries
    private val categories get() = database.categoryQueries
    private val cells get() = database.categoryMonthQueries
    private val revisions get() = database.categoryMonthRevisionQueries
    private val assets get() = database.assetQueries

    override suspend fun <T> transaction(block: suspend () -> T): T = transactions.inTransaction(block)

    // ── Состояние ─────────────────────────────────────────────────────────────────

    override fun state(key: String): String? = sync.stateValue(key).executeAsOneOrNull()

    override fun setState(key: String, value: String) {
        sync.upsertState(key = key, value_ = value, updated_at_utc = time.nowUtcMillis())
    }

    override fun removeState(key: String) {
        sync.deleteState(key)
    }

    // ── Журнал изменений ──────────────────────────────────────────────────────────

    override fun pendingOutbox(): List<OutboxEntry> =
        sync.selectPendingAll().executeAsList().map { OutboxEntry(it.change_id, it.entity_type, it.entity_id) }

    override fun markSynced(changeIds: Collection<String>, atUtc: Long) {
        changeIds.forEach { sync.markSynced(synced_at_utc = atUtc, change_id = it) }
    }

    override fun markAllSynced(atUtc: Long) {
        sync.markAllSynced(atUtc)
    }

    override fun clearPendingOutbox() {
        apply.clearOutbox()
    }

    override fun hasUserData(): Boolean {
        val counts = apply.dataCounts().executeAsOne()
        return counts.cells > 0 || counts.accounts > 0
    }

    override fun deleteUnusedCategories() {
        apply.deleteUnusedCategories()
    }

    // ── Выгрузка ──────────────────────────────────────────────────────────────────

    override fun exportAll(): List<SyncChange> {
        val currencies = currencies()
        return apply.allCategories().executeAsList().map { SyncChange.CategoryChange(it.toDomain()) } +
            apply.allAccounts().executeAsList().map { SyncChange.AccountChange(it.toDomain()) } +
            apply.allSnapshots().executeAsList().map {
                SyncChange.SnapshotChange(it.toDomain(currencies[it.account_id] ?: Money.DEFAULT_CURRENCY))
            } +
            apply.revisionsWithCell().executeAsList().map {
                revisionChange(
                    it.id, it.cell_id, it.op, it.delta_minor, it.amount_before_minor, it.amount_after_minor,
                    it.currency, it.comment_before, it.comment_after, it.source, it.source_period,
                    it.idempotency_key, it.created_at_utc, it.cell_section, it.cell_category_id, it.cell_period,
                )
            }
    }

    override fun export(entry: OutboxEntry): SyncChange? = when (entry.entityType) {
        SyncEntity.CATEGORY -> categoryById(entry.entityId)?.let { SyncChange.CategoryChange(it) }
        SyncEntity.ACCOUNT -> accountById(entry.entityId)?.let { SyncChange.AccountChange(it) }
        SyncEntity.SNAPSHOT -> snapshotById(entry.entityId)?.let { SyncChange.SnapshotChange(it) }
        SyncEntity.REVISION -> apply.revisionWithCellById(entry.entityId).executeAsOneOrNull()?.let {
            revisionChange(
                it.id, it.cell_id, it.op, it.delta_minor, it.amount_before_minor, it.amount_after_minor,
                it.currency, it.comment_before, it.comment_after, it.source, it.source_period,
                it.idempotency_key, it.created_at_utc, it.cell_section, it.cell_category_id, it.cell_period,
            )
        }
        // Ячейки не выгружаются: их значение восстанавливается по ревизиям.
        else -> null
    }

    @Suppress("LongParameterList")
    private fun revisionChange(
        id: String, cellId: String, op: String, delta: Long?, before: Long?, after: Long, currency: String,
        commentBefore: String?, commentAfter: String?, source: String, sourcePeriod: Long?,
        idempotencyKey: String?, createdAtUtc: Long, section: String, categoryId: String, period: Long,
    ) = SyncChange.RevisionChange(
        revision = CategoryMonthRevision(
            id = id,
            cellId = cellId,
            op = RevisionOp.valueOf(op),
            amountAfter = Money(after, currency),
            amountBefore = before?.let { Money(it, currency) },
            delta = delta?.let { Money(it, currency) },
            commentBefore = commentBefore,
            commentAfter = commentAfter,
            source = RevisionSource.valueOf(source),
            sourcePeriod = sourcePeriod?.toInt()?.let(Period::ofKey),
            idempotencyKey = idempotencyKey,
            createdAtUtc = createdAtUtc,
        ),
        section = LedgerSection.valueOf(section),
        categoryId = categoryId,
        period = Period.ofKey(period.toInt()),
    )

    // ── Соответствие id ───────────────────────────────────────────────────────────

    override fun aliasOf(entityType: String, remoteId: String): String? =
        sync.aliasOf(entityType, remoteId).executeAsOneOrNull()

    override fun aliasesTo(entityType: String, localId: String): List<String> =
        sync.aliasesTo(entityType, localId).executeAsList()

    override fun putAlias(entityType: String, remoteId: String, localId: String) {
        if (remoteId != localId) sync.putAlias(entityType, remoteId, localId)
    }

    // ── Категории ─────────────────────────────────────────────────────────────────

    override fun categoryById(id: String): Category? = categories.selectById(id).executeAsOneOrNull()?.toDomain()

    override fun categoriesInSection(section: CategorySection): List<Category> =
        categories.selectAllBySection(section.name).executeAsList().map { it.toDomain() }

    override fun insertCategory(category: Category) {
        categories.insert(
            id = category.id,
            section = category.section.name,
            name = category.name,
            parent_id = category.parentId,
            color = category.color,
            icon = category.icon,
            sort_order = category.sortOrder.toLong(),
            balance_policy = category.balancePolicy.name,
            allows_negative_balance = category.allowsNegativeBalance,
            is_archived = category.isArchived,
            created_at_utc = category.createdAtUtc,
            updated_at_utc = category.updatedAtUtc,
            row_version = category.rowVersion.toLong(),
            deleted_at_utc = category.deletedAtUtc,
        )
    }

    override fun replaceCategory(category: Category) {
        apply.updateCategoryFull(
            section = category.section.name,
            name = category.name,
            parent_id = category.parentId,
            color = category.color,
            icon = category.icon,
            sort_order = category.sortOrder.toLong(),
            is_archived = category.isArchived,
            created_at_utc = category.createdAtUtc,
            updated_at_utc = category.updatedAtUtc,
            row_version = category.rowVersion.toLong(),
            deleted_at_utc = category.deletedAtUtc,
            balance_policy = category.balancePolicy.name,
            allows_negative_balance = category.allowsNegativeBalance,
            id = category.id,
        )
    }

    // ── Активы ────────────────────────────────────────────────────────────────────

    override fun accountById(id: String): AssetAccount? = assets.selectAccountById(id).executeAsOneOrNull()?.toDomain()

    override fun insertAccount(account: AssetAccount) {
        assets.insertAccount(
            id = account.id,
            category_id = account.categoryId,
            name = account.name,
            currency = account.currency,
            opened_period = account.openedPeriod.toDbKey(),
            closed_period = account.closedPeriod?.toDbKey(),
            annual_rate_bp = account.annualRateBp?.toLong(),
            note = account.note,
            created_at_utc = account.createdAtUtc,
            updated_at_utc = account.updatedAtUtc,
            row_version = account.rowVersion.toLong(),
            deleted_at_utc = account.deletedAtUtc,
            term_months = account.termMonths?.toLong(),
            interest_day = account.interestDay?.toLong(),
            capitalization = account.capitalization.name,
            monthly_reminder = account.monthlyReminder,
        )
    }

    override fun replaceAccount(account: AssetAccount) {
        apply.updateAccountFull(
            category_id = account.categoryId,
            name = account.name,
            currency = account.currency,
            opened_period = account.openedPeriod.toDbKey(),
            closed_period = account.closedPeriod?.toDbKey(),
            annual_rate_bp = account.annualRateBp?.toLong(),
            note = account.note,
            created_at_utc = account.createdAtUtc,
            updated_at_utc = account.updatedAtUtc,
            row_version = account.rowVersion.toLong(),
            deleted_at_utc = account.deletedAtUtc,
            term_months = account.termMonths?.toLong(),
            interest_day = account.interestDay?.toLong(),
            capitalization = account.capitalization.name,
            monthly_reminder = account.monthlyReminder,
            id = account.id,
        )
    }

    override fun snapshotById(id: String): AssetSnapshot? =
        apply.snapshotById(id).executeAsOneOrNull()?.let { it.toDomain(currencyOf(it.account_id)) }

    override fun snapshotByKey(accountId: String, period: Period): AssetSnapshot? =
        apply.snapshotByKeyAny(accountId, period.toDbKey()).executeAsOneOrNull()?.let { it.toDomain(currencyOf(accountId)) }

    override fun insertSnapshot(snapshot: AssetSnapshot) {
        assets.insertSnapshot(
            id = snapshot.id,
            account_id = snapshot.accountId,
            period = snapshot.period.toDbKey(),
            balance_minor = snapshot.balance.minor,
            contribution_minor = snapshot.contribution?.minor,
            note = snapshot.note,
            created_at_utc = snapshot.createdAtUtc,
            updated_at_utc = snapshot.updatedAtUtc,
            row_version = snapshot.rowVersion.toLong(),
            deleted_at_utc = snapshot.deletedAtUtc,
        )
    }

    override fun replaceSnapshot(snapshot: AssetSnapshot) {
        apply.updateSnapshotFull(
            account_id = snapshot.accountId,
            period = snapshot.period.toDbKey(),
            balance_minor = snapshot.balance.minor,
            contribution_minor = snapshot.contribution?.minor,
            note = snapshot.note,
            created_at_utc = snapshot.createdAtUtc,
            updated_at_utc = snapshot.updatedAtUtc,
            row_version = snapshot.rowVersion.toLong(),
            deleted_at_utc = snapshot.deletedAtUtc,
            id = snapshot.id,
        )
    }

    private fun currencyOf(accountId: String): String =
        assets.selectAccountById(accountId).executeAsOneOrNull()?.currency ?: Money.DEFAULT_CURRENCY

    private fun currencies(): Map<String, String> =
        apply.allAccounts().executeAsList().associate { it.id to it.currency }

    // ── Ячейки и ревизии ──────────────────────────────────────────────────────────

    override fun cellById(id: String): CategoryMonthCell? = cells.selectById(id).executeAsOneOrNull()?.toDomain()

    override fun cellByKey(categoryId: String, period: Period): CategoryMonthCell? =
        apply.cellByKeyAny(categoryId, period.toDbKey()).executeAsOneOrNull()?.toDomain()

    override fun insertCell(cell: CategoryMonthCell) {
        cells.insert(
            id = cell.id,
            section = cell.section.name,
            category_id = cell.categoryId,
            period = cell.period.toDbKey(),
            amount_minor = cell.amount.minor,
            currency = cell.amount.currency,
            comment = cell.comment,
            created_at_utc = cell.createdAtUtc,
            updated_at_utc = cell.updatedAtUtc,
            row_version = cell.rowVersion.toLong(),
            deleted_at_utc = cell.deletedAtUtc,
        )
    }

    override fun replaceCell(cell: CategoryMonthCell) {
        cells.update(
            amount_minor = cell.amount.minor,
            currency = cell.amount.currency,
            comment = cell.comment,
            deleted_at_utc = cell.deletedAtUtc,
            updated_at_utc = cell.updatedAtUtc,
            row_version = cell.rowVersion.toLong(),
            id = cell.id,
        )
    }

    override fun revisionExists(id: String): Boolean = apply.revisionExists(id).executeAsOne() > 0

    override fun insertRevision(revision: CategoryMonthRevision) {
        revisions.insert(
            id = revision.id,
            cell_id = revision.cellId,
            op = revision.op.name,
            delta_minor = revision.delta?.minor,
            amount_before_minor = revision.amountBefore?.minor,
            amount_after_minor = revision.amountAfter.minor,
            currency = revision.amountAfter.currency,
            comment_before = revision.commentBefore,
            comment_after = revision.commentAfter,
            source = revision.source.name,
            source_period = revision.sourcePeriod?.toDbKey(),
            idempotency_key = revision.idempotencyKey,
            created_at_utc = revision.createdAtUtc,
        )
    }

    override fun revisionsOfCell(cellId: String): List<CategoryMonthRevision> =
        revisions.selectByCell(cellId).executeAsList().map { it.toDomain() }
}
