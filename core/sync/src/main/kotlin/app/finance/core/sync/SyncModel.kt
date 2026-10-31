package app.finance.core.sync

import app.finance.core.model.AssetAccount
import app.finance.core.model.AssetSnapshot
import app.finance.core.model.Category
import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.CategoryMonthRevision
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Period

/**
 * Изменение, которое уходит на Диск (docs/SYNC-YANDEX.md §5).
 *
 * Категории, счета и снапшоты передаются целиком — сливаются по правилу «кто последний,
 * тот и прав». Ячейки «категория + месяц» не передаются вовсе: передаются их ревизии, и
 * значение ячейки на другом компьютере восстанавливается их повтором — так прибавки с
 * разных компьютеров складываются, а не затирают друг друга.
 */
sealed interface SyncChange {
    /** Порядок применения внутри пакета: родители раньше детей. */
    val order: Int

    data class CategoryChange(val category: Category) : SyncChange {
        override val order: Int get() = 0
    }

    data class AccountChange(val account: AssetAccount) : SyncChange {
        override val order: Int get() = 1
    }

    data class SnapshotChange(val snapshot: AssetSnapshot) : SyncChange {
        override val order: Int get() = 2
    }

    /** Ревизия вместе с ключом ячейки: ячейку на другом компьютере может понадобиться создать. */
    data class RevisionChange(
        val revision: CategoryMonthRevision,
        val section: LedgerSection,
        val categoryId: String,
        val period: Period,
    ) : SyncChange {
        override val order: Int get() = 3
    }
}

/** Файл пакета: заголовок и изменения. Один пакет — одна отправка одного компьютера. */
data class SyncPack(
    val format: Int,
    val deviceId: String,
    val seq: Long,
    val schemaVersion: Long,
    val appVersion: String,
    val createdAtUtc: Long,
    val changes: List<SyncChange>,
)

/** Описание компьютера в `devices/<id>/device.json`. */
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val os: String,
    val appVersion: String,
    val schemaVersion: Long,
    val lastSeq: Long,
    val updatedAtUtc: Long,
)

/** `sync.json` в корне хранилища. `storeId` меняется, если хранилище создают заново. */
data class StoreInfo(
    val format: Int,
    val storeId: String,
    val createdAtUtc: Long,
)

/** Запись журнала изменений, ещё не отправленная на Диск. */
data class OutboxEntry(
    val changeId: String,
    val entityType: String,
    val entityId: String,
)

/** Типы сущностей в sync_outbox и sync_alias (совпадают с константами core:data). */
object SyncEntity {
    const val CATEGORY = "category"
    const val CELL = "category_month"
    const val REVISION = "category_month_revision"
    const val ACCOUNT = "asset_account"
    const val SNAPSHOT = "asset_snapshot"
}

/**
 * Локальная база с точки зрения синхронизации. Реализация — `SqlSyncStore` в `core:data`.
 *
 * Методы записи пишут строки как есть, мимо репозиториев: применённые чужие изменения не
 * должны попадать в sync_outbox и уходить обратно на Диск.
 */
interface SyncStore {
    suspend fun <T> transaction(block: suspend () -> T): T

    // Состояние синхронизации (sync_state).
    fun state(key: String): String?
    fun setState(key: String, value: String)
    fun removeState(key: String)

    // Журнал изменений.
    fun pendingOutbox(): List<OutboxEntry>
    fun markSynced(changeIds: Collection<String>, atUtc: Long)
    fun markAllSynced(atUtc: Long)
    fun clearPendingOutbox()

    /** Есть ли в базе данные пользователя: ячейки или счета. Категории не в счёт. */
    fun hasUserData(): Boolean

    /** Удаляет категории без ячеек и счетов — стартовые категории пустой базы. */
    fun deleteUnusedCategories()

    // Выгрузка: строки с локальными id; переводом в общие id занимается движок.
    fun exportAll(): List<SyncChange>
    fun export(entry: OutboxEntry): SyncChange?

    // Соответствие чужих id своим.
    fun aliasOf(entityType: String, remoteId: String): String?
    fun aliasesTo(entityType: String, localId: String): List<String>
    fun putAlias(entityType: String, remoteId: String, localId: String)

    // Применение.
    fun categoryById(id: String): Category?
    fun categoriesInSection(section: CategorySection): List<Category>
    fun insertCategory(category: Category)
    fun replaceCategory(category: Category)

    fun accountById(id: String): AssetAccount?
    fun insertAccount(account: AssetAccount)
    fun replaceAccount(account: AssetAccount)

    fun snapshotById(id: String): AssetSnapshot?
    fun snapshotByKey(accountId: String, period: Period): AssetSnapshot?
    fun insertSnapshot(snapshot: AssetSnapshot)
    fun replaceSnapshot(snapshot: AssetSnapshot)

    fun cellById(id: String): CategoryMonthCell?
    fun cellByKey(categoryId: String, period: Period): CategoryMonthCell?
    fun insertCell(cell: CategoryMonthCell)
    fun replaceCell(cell: CategoryMonthCell)

    fun revisionExists(id: String): Boolean
    fun insertRevision(revision: CategoryMonthRevision)
    fun revisionsOfCell(cellId: String): List<CategoryMonthRevision>
}

/**
 * Хранилище на Диске: папка приложения в REST API или папка в памяти в тестах.
 * Пути — относительные от корня хранилища, через «/».
 */
interface SyncTransport {
    /** Содержимое файла или null, если файла нет. */
    suspend fun read(path: String): String?

    /** Записывает файл целиком, перезаписывая существующий. Папки создаются сами. */
    suspend fun write(path: String, content: String)

    /** Имена папок и файлов внутри папки; null, если папки нет. */
    suspend fun list(path: String): List<String>?

    /** Удаляет файл или папку со всем содержимым; отсутствие — не ошибка. */
    suspend fun delete(path: String)
}

/** Ошибка обмена с Диском. [kind] определяет, что показать пользователю. */
class SyncTransportException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        /** Нет сети или сервер недоступен — тихо повторить позже. */
        NETWORK,

        /** Токен недействителен — войти заново. */
        AUTH,

        /** Слишком много запросов (429) — повторить позже. */
        RATE_LIMIT,

        /** Загрузка запрещена (423: лимит трафика) или нет места (507). */
        QUOTA,

        /** Прочее: неожиданный ответ сервера. */
        OTHER,
    }
}
