package app.finance.core.domain

import app.finance.core.model.AssetAccount
import app.finance.core.model.AssetSnapshot
import app.finance.core.model.Category
import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.CategoryMonthRevision
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Period

/** Текущее время в UTC millis. Инжектится, чтобы тесты были детерминированными. */
fun interface TimeProvider {
    fun nowUtcMillis(): Long
}

/** Генератор UUIDv4. Инжектится по той же причине. */
fun interface IdGenerator {
    fun newId(): String
}

/**
 * Граница транзакции. Реализация в `core:data` открывает транзакцию SQLite;
 * требование SPEC.md §7 — мутация, ревизия и запись в `sync_outbox` атомарны.
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

interface CategoryRepository {
    suspend fun byId(id: String): Category?

    suspend fun bySection(section: CategorySection, includeArchived: Boolean = false): List<Category>

    suspend fun create(category: Category)

    suspend fun update(category: Category)

    /** Архивация вместо удаления: история и суммы категории сохраняются (SPEC.md F3.2). */
    suspend fun setArchived(categoryId: String, archived: Boolean, updatedAtUtc: Long)
}

/**
 * Хранилище ячеек «категория + месяц» и журнала ревизий.
 *
 * Реализация обязана сохранять инвариант: на пару (categoryId, period) существует
 * не более одной живой ячейки (в БД — частичный уникальный индекс
 * `uq_category_month_active ... WHERE deleted_at IS NULL`).
 */
interface LedgerRepository {
    suspend fun findCell(categoryId: String, period: Period): CategoryMonthCell?

    /** Только живые ячейки месяца (без tombstone), отсортированные по убыванию суммы. */
    suspend fun cellsForPeriod(section: LedgerSection, period: Period): List<CategoryMonthCell>

    /**
     * Живые ячейки диапазона `[from, to]` включительно (SPEC.md §5).
     *
     * Единственный источник данных аналитики. SQL здесь только выбирает строки: ячейка
     * `(category, period)` уникальна, поэтому агрегировать нечего, а все суммы, доли и
     * метрики считаются чистыми функциями `core:analytics` (F6.4).
     */
    suspend fun cellsInRange(section: LedgerSection, from: Period, to: Period): List<CategoryMonthCell>

    suspend fun cellById(id: String): CategoryMonthCell?

    suspend fun insertCell(cell: CategoryMonthCell)

    suspend fun updateCell(cell: CategoryMonthCell)

    /** Ревизии по ячейке, от старых к новым — для экрана истории и для Undo. */
    suspend fun revisionsOf(cellId: String): List<CategoryMonthRevision>

    suspend fun insertRevision(revision: CategoryMonthRevision)

    /** Ревизия по id — нужна отмене импорта: пакет хранит ссылки на свои ревизии. */
    suspend fun revisionById(id: String): CategoryMonthRevision?

    /**
     * Поиск ревизии по ключу идемпотентности. Нужен импорту: повторный прогон того же
     * файла не должен удваивать суммы (SPEC.md F10.2).
     */
    suspend fun revisionByIdempotencyKey(key: String): CategoryMonthRevision?
}

/**
 * Хранилище счетов и их месячных снапшотов (SPEC.md F4).
 *
 * Инвариант: на пару `(accountId, period)` существует не более одного живого снапшота —
 * в БД это частичный уникальный индекс `uq_asset_snapshot_active ... WHERE
 * deleted_at_utc IS NULL`. Именно поэтому повторный ввод остатка за тот же месяц
 * означает редактирование (F4.3).
 */
interface AssetRepository {
    suspend fun accountById(id: String): AssetAccount?

    /** Живые счета; при `includeClosed = false` закрытые исключаются (F4.5). */
    suspend fun accounts(includeClosed: Boolean = false): List<AssetAccount>

    suspend fun insertAccount(account: AssetAccount)

    suspend fun updateAccount(account: AssetAccount)

    /** Закрытие счёта: история снапшотов сохраняется, счёт выбывает из дублирования (F4.5). */
    suspend fun closeAccount(accountId: String, closedPeriod: Period, updatedAtUtc: Long)

    suspend fun findSnapshot(accountId: String, period: Period): AssetSnapshot?

    /** Живые снапшоты месяца, по убыванию баланса. */
    suspend fun snapshotsForPeriod(period: Period): List<AssetSnapshot>

    /** История счёта по месяцам, от старых к новым (F4.4). */
    suspend fun snapshotsByAccount(accountId: String): List<AssetSnapshot>

    /**
     * Живые снапшоты диапазона `[from, to]` включительно, упорядоченные по месяцу.
     *
     * Для серий капитала (F6.1.6). Месяцы без снапшотов в выборку не попадают: пропуск —
     * это gap, а не ноль (SPEC.md §5), и `core:analytics` сохраняет разрыв серии.
     */
    suspend fun snapshotsInRange(from: Period, to: Period): List<AssetSnapshot>

    /**
     * Живые снапшоты всех счетов до [period] включительно, от старых к новым.
     *
     * Нужны правилам перехода месяца (F4.6, D-21): прогноз вклада и перенос остатка
     * считаются от последнего введённого значения, поэтому одного месяца недостаточно.
     */
    suspend fun snapshotsUpTo(period: Period): List<AssetSnapshot>

    suspend fun insertSnapshot(snapshot: AssetSnapshot)

    suspend fun updateSnapshot(snapshot: AssetSnapshot)
}
