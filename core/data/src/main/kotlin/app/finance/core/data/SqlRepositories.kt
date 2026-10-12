package app.finance.core.data

import app.finance.core.data.db.FinanceDatabase
import app.finance.core.domain.CategoryRepository
import app.finance.core.domain.IdGenerator
import app.finance.core.domain.LedgerRepository
import app.finance.core.model.Category
import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.CategoryMonthRevision
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Period

/**
 * Репозиторий категорий на SQLite.
 *
 * Все методы намеренно синхронны внутри и не переключают диспетчер: см. предупреждение
 * в [SqlDelightTransactionRunner] о вложенной смене потока внутри транзакции.
 */
class SqlCategoryRepository(
    private val database: FinanceDatabase,
) : CategoryRepository {

    private val queries get() = database.categoryQueries

    override suspend fun byId(id: String): Category? =
        queries.selectById(id).executeAsOneOrNull()?.toDomain()

    override suspend fun bySection(section: CategorySection, includeArchived: Boolean): List<Category> {
        val rows = if (includeArchived) {
            queries.selectAllBySection(section.name)
        } else {
            queries.selectActiveBySection(section.name)
        }
        return rows.executeAsList().map { it.toDomain() }
    }

    override suspend fun create(category: Category) {
        queries.insert(
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

    override suspend fun update(category: Category) {
        queries.update(
            name = category.name,
            color = category.color,
            icon = category.icon,
            sort_order = category.sortOrder.toLong(),
            balance_policy = category.balancePolicy.name,
            allows_negative_balance = category.allowsNegativeBalance,
            updated_at_utc = category.updatedAtUtc,
            id = category.id,
        )
    }

    override suspend fun setArchived(categoryId: String, archived: Boolean, updatedAtUtc: Long) {
        if (archived) {
            queries.archive(updated_at_utc = updatedAtUtc, id = categoryId)
        } else {
            queries.unarchive(updated_at_utc = updatedAtUtc, id = categoryId)
        }
    }

    /** Число категорий в базе — для диагностики и теста идемпотентности сида. */
    fun count(): Long = queries.countAll().executeAsOne()
}

/**
 * Репозиторий ячеек «категория + месяц» и журнала ревизий.
 *
 * Каждая мутация дополнительно кладёт запись в `sync_outbox` — требование SPEC.md §7:
 * мутация, ревизия и outbox должны оказаться в одной транзакции, иначе будущая
 * синхронизация с сервером потребует переписывания слоя данных.
 */
class SqlLedgerRepository(
    private val database: FinanceDatabase,
    private val ids: IdGenerator,
) : LedgerRepository {

    private val cells get() = database.categoryMonthQueries
    private val revisions get() = database.categoryMonthRevisionQueries
    private val outbox get() = database.syncQueries

    override suspend fun findCell(categoryId: String, period: Period): CategoryMonthCell? =
        cells.selectByCategoryAndPeriod(category_id = categoryId, period = period.toDbKey())
            .executeAsOneOrNull()?.toDomain()

    override suspend fun cellsForPeriod(section: LedgerSection, period: Period): List<CategoryMonthCell> =
        cells.selectForPeriod(section = section.name, period = period.toDbKey())
            .executeAsList().map { it.toDomain() }

    override suspend fun cellsInRange(
        section: LedgerSection,
        from: Period,
        to: Period,
    ): List<CategoryMonthCell> =
        // Параметры SQLDelight для двух плейсхолдеров одной колонки: period и period_.
        cells.selectForRange(section = section.name, period = from.toDbKey(), period_ = to.toDbKey())
            .executeAsList().map { it.toDomain() }

    override suspend fun cellById(id: String): CategoryMonthCell? =
        cells.selectById(id).executeAsOneOrNull()?.toDomain()

    override suspend fun insertCell(cell: CategoryMonthCell) {
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
        enqueue(cell, OPERATION_CREATE)
    }

    override suspend fun updateCell(cell: CategoryMonthCell) {
        cells.update(
            amount_minor = cell.amount.minor,
            currency = cell.amount.currency,
            comment = cell.comment,
            deleted_at_utc = cell.deletedAtUtc,
            updated_at_utc = cell.updatedAtUtc,
            row_version = cell.rowVersion.toLong(),
            id = cell.id,
        )
        // Для сервера удаление и правка — разные операции, хотя в БД это один UPDATE.
        enqueue(cell, if (cell.isDeleted) OPERATION_DELETE else OPERATION_UPDATE)
    }

    override suspend fun revisionsOf(cellId: String): List<CategoryMonthRevision> =
        revisions.selectByCell(cell_id = cellId).executeAsList().map { it.toDomain() }

    override suspend fun insertRevision(revision: CategoryMonthRevision) {
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

    override suspend fun revisionByIdempotencyKey(key: String): CategoryMonthRevision? =
        revisions.selectByIdempotencyKey(key).executeAsOneOrNull()?.toDomain()

    /** Число ячеек без фильтрации tombstone — нужно, чтобы тест видел «мёртвые» строки. */
    fun countCells(): Long = cells.countAll().executeAsOne()

    fun countRevisions(): Long = revisions.countAll().executeAsOne()

    private fun enqueue(cell: CategoryMonthCell, operation: String) {
        outbox.enqueue(
            change_id = ids.newId(),
            entity_type = ENTITY_TYPE,
            entity_id = cell.id,
            operation = operation,
            row_version = cell.rowVersion.toLong(),
            occurred_at_utc = cell.updatedAtUtc,
            payload_hash = null,
        )
    }

    companion object {
        const val ENTITY_TYPE = "category_month"
        const val OPERATION_CREATE = "CREATE"
        const val OPERATION_UPDATE = "UPDATE"
        const val OPERATION_DELETE = "DELETE"
    }
}
