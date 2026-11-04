package app.finance.core.data

import app.finance.core.data.db.FinanceDatabase
import app.finance.core.domain.AssetRepository
import app.finance.core.domain.IdGenerator
import app.finance.core.model.AssetAccount
import app.finance.core.model.AssetSnapshot
import app.finance.core.model.Money
import app.finance.core.model.Period

/**
 * Репозиторий счетов и снапшотов на SQLite (SPEC.md F4).
 *
 * Как и [SqlLedgerRepository], каждая мутация дополнительно кладёт запись в
 * `sync_outbox` в той же транзакции (SPEC.md §7). Журнала ревизий у активов нет:
 * таблица `asset_snapshot_revision` в схеме v1 не предусмотрена, поэтому ввод
 * остатка обратим только повторным вводом (в отличие от ячеек расходов).
 *
 * **Валюта снапшота не хранится отдельно.** В `asset_snapshot` нет колонки currency:
 * валюта остатка по определению равна валюте счёта, и это проверяет домен
 * (`RecordSnapshot`). Поэтому при чтении валюта подставляется из `asset_account` —
 * добавление колонки означало бы избыточность и миграцию схемы.
 *
 * Методы синхронны внутри и не переключают диспетчер: см. предупреждение в
 * [SqlDelightTransactionRunner] о вложенной смене потока внутри транзакции.
 */
class SqlAssetRepository(
    private val database: FinanceDatabase,
    private val ids: IdGenerator,
) : AssetRepository {

    private val assets get() = database.assetQueries
    private val outbox get() = database.syncQueries

    // ── Счета ────────────────────────────────────────────────────────────────

    override suspend fun accountById(id: String): AssetAccount? =
        assets.selectAccountById(id).executeAsOneOrNull()?.toDomain()

    override suspend fun accounts(includeClosed: Boolean): List<AssetAccount> {
        val rows = if (includeClosed) assets.selectAllAccounts() else assets.selectActiveAccounts()
        return rows.executeAsList().map { it.toDomain() }
    }

    override suspend fun insertAccount(account: AssetAccount) {
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
        )
        enqueue(ENTITY_ACCOUNT, account.id, OPERATION_CREATE, account.rowVersion, account.updatedAtUtc)
    }

    /**
     * Правка счёта. Запрос сам увеличивает `row_version`, поэтому в outbox попадает
     * версия, перечитанная из базы, а не из переданного объекта.
     */
    override suspend fun updateAccount(account: AssetAccount) {
        assets.updateAccount(
            name = account.name,
            category_id = account.categoryId,
            currency = account.currency,
            annual_rate_bp = account.annualRateBp?.toLong(),
            note = account.note,
            term_months = account.termMonths?.toLong(),
            interest_day = account.interestDay?.toLong(),
            capitalization = account.capitalization.name,
            updated_at_utc = account.updatedAtUtc,
            id = account.id,
        )
        val stored = assets.selectAccountById(account.id).executeAsOneOrNull()
        enqueue(
            entityType = ENTITY_ACCOUNT,
            entityId = account.id,
            operation = if (account.deletedAtUtc != null) OPERATION_DELETE else OPERATION_UPDATE,
            rowVersion = stored?.row_version?.toInt() ?: account.rowVersion,
            occurredAtUtc = account.updatedAtUtc,
        )
    }

    override suspend fun closeAccount(accountId: String, closedPeriod: Period, updatedAtUtc: Long) {
        assets.closeAccount(
            closed_period = closedPeriod.toDbKey(),
            updated_at_utc = updatedAtUtc,
            id = accountId,
        )
        val stored = assets.selectAccountById(accountId).executeAsOneOrNull()
        enqueue(
            entityType = ENTITY_ACCOUNT,
            entityId = accountId,
            operation = OPERATION_CLOSE,
            rowVersion = stored?.row_version?.toInt() ?: 1,
            occurredAtUtc = updatedAtUtc,
        )
    }

    // ── Снапшоты ─────────────────────────────────────────────────────────────

    override suspend fun findSnapshot(accountId: String, period: Period): AssetSnapshot? {
        val row = assets.selectSnapshot(account_id = accountId, period = period.toDbKey())
            .executeAsOneOrNull() ?: return null
        return row.toDomain(currencyOf(accountId))
    }

    override suspend fun snapshotsForPeriod(period: Period): List<AssetSnapshot> {
        val currencies = currencies()
        return assets.selectSnapshotsForPeriod(period.toDbKey())
            .executeAsList()
            .mapNotNull { row -> currencies[row.account_id]?.let { row.toDomain(it) } }
    }

    override suspend fun snapshotsByAccount(accountId: String): List<AssetSnapshot> {
        val currency = currencyOf(accountId)
        return assets.selectSnapshotsByAccount(accountId)
            .executeAsList()
            .map { it.toDomain(currency) }
    }

    override suspend fun snapshotsInRange(from: Period, to: Period): List<AssetSnapshot> {
        val currencies = currencies()
        return assets.selectSnapshotsInRange(period = from.toDbKey(), period_ = to.toDbKey())
            .executeAsList()
            // Валюта берётся из счёта: в asset_snapshot её нет (D-14). Снапшот без счёта
            // невозможен при включённом контроле FK, но молча подставлять RUB было бы хуже.
            .mapNotNull { row -> currencies[row.account_id]?.let { row.toDomain(it) } }
    }

    override suspend fun snapshotsUpTo(period: Period): List<AssetSnapshot> {
        val currencies = currencies()
        return assets.selectSnapshotsUpTo(period = period.toDbKey())
            .executeAsList()
            .mapNotNull { row -> currencies[row.account_id]?.let { row.toDomain(it) } }
    }

    override suspend fun insertSnapshot(snapshot: AssetSnapshot) {
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
        enqueue(ENTITY_SNAPSHOT, snapshot.id, OPERATION_CREATE, snapshot.rowVersion, snapshot.updatedAtUtc)
    }

    override suspend fun updateSnapshot(snapshot: AssetSnapshot) {
        assets.updateSnapshot(
            balance_minor = snapshot.balance.minor,
            contribution_minor = snapshot.contribution?.minor,
            note = snapshot.note,
            updated_at_utc = snapshot.updatedAtUtc,
            row_version = snapshot.rowVersion.toLong(),
            id = snapshot.id,
        )
        enqueue(
            entityType = ENTITY_SNAPSHOT,
            entityId = snapshot.id,
            operation = if (snapshot.deletedAtUtc != null) OPERATION_DELETE else OPERATION_UPDATE,
            rowVersion = snapshot.rowVersion,
            occurredAtUtc = snapshot.updatedAtUtc,
        )
    }

    // ── Служебное ────────────────────────────────────────────────────────────

    private fun currencyOf(accountId: String): String =
        assets.selectAccountById(accountId).executeAsOneOrNull()?.currency ?: FALLBACK_CURRENCY

    private fun currencies(): Map<String, String> =
        assets.selectAllAccounts().executeAsList().associate { it.id to it.currency }

    private fun enqueue(
        entityType: String,
        entityId: String,
        operation: String,
        rowVersion: Int,
        occurredAtUtc: Long,
    ) {
        outbox.enqueue(
            change_id = ids.newId(),
            entity_type = entityType,
            entity_id = entityId,
            operation = operation,
            row_version = rowVersion.toLong(),
            occurred_at_utc = occurredAtUtc,
            payload_hash = null,
        )
    }

    companion object {
        const val ENTITY_ACCOUNT = "asset_account"
        const val ENTITY_SNAPSHOT = "asset_snapshot"
        const val OPERATION_CREATE = "CREATE"
        const val OPERATION_UPDATE = "UPDATE"
        const val OPERATION_DELETE = "DELETE"
        const val OPERATION_CLOSE = "CLOSE"

        /** Валюта счёта обязательна; значение используется, только если счёт пропал из базы. */
        private const val FALLBACK_CURRENCY = Money.DEFAULT_CURRENCY
    }
}
