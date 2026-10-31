package app.finance.core.sync

import app.finance.core.domain.DatabaseSnapshotter
import app.finance.core.domain.IdGenerator
import app.finance.core.domain.TimeProvider
import app.finance.core.model.AssetAccount
import app.finance.core.model.AssetSnapshot
import app.finance.core.model.Category
import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.CategoryMonthRevision
import app.finance.core.model.Money
import app.finance.core.model.RevisionOp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Параметры этого компьютера и этой сборки. */
data class SyncConfig(
    /** Версия схемы локальной базы: пакеты от более новой схемы не принимаются. */
    val schemaVersion: Long,
    val appVersion: String,
    val deviceName: String,
    val os: String,
)

/** Почему синхронизация остановилась. Каждый вариант — своё сообщение и свои действия. */
sealed interface SyncProblem {
    /** На Диске нет хранилища или его удалили (решение владельца: остановиться и спросить). */
    data object RemoteMissing : SyncProblem

    /** Хранилище на Диске создано заново с другого компьютера. */
    data object RemoteReplaced : SyncProblem

    /** Файл на Диске не читается. */
    data class RemoteCorrupt(val details: String) : SyncProblem

    /** На другом компьютере стоит более новая версия: этот не синхронизируется до обновления. */
    data class NeedsUpdate(val deviceName: String) : SyncProblem

    /** Подключение второго компьютера с непустой базой (решение владельца: только пустая). */
    data object LocalNotEmpty : SyncProblem

    /** Ошибка связи с Диском. */
    data class Transport(val kind: SyncTransportException.Kind, val message: String) : SyncProblem
}

/** Итог синхронизации. */
sealed interface SyncOutcome {
    data class Done(val received: Int, val sent: Int) : SyncOutcome

    data object NotEnabled : SyncOutcome

    data class Failed(val problem: SyncProblem) : SyncOutcome
}

/**
 * Синхронизация через хранилище на Диске (docs/SYNC-YANDEX.md, D-29).
 *
 * На Диске у каждого компьютера своя папка `devices/<id>/` с неизменяемыми пакетами
 * `changes-NNNNNN.jsonl`. Компьютер пишет только в свою папку и читает чужие, поэтому
 * файлы на Диске никогда не конфликтуют. Порядок: сначала получить чужие изменения,
 * потом отправить свои.
 */
class SyncEngine(
    private val store: SyncStore,
    private val transport: SyncTransport,
    private val snapshotter: DatabaseSnapshotter,
    private val ids: IdGenerator,
    private val time: TimeProvider,
    private val config: SyncConfig,
) {
    private val mutex = Mutex()

    val isEnabled: Boolean get() = store.state(KEY_ENABLED) == "true"

    val deviceId: String? get() = store.state(KEY_DEVICE_ID)

    val lastSyncAtUtc: Long? get() = store.state(KEY_LAST_SYNC)?.toLongOrNull()

    /** Сколько своих изменений ещё не отправлено (без служебных записей ячеек). */
    fun pendingCount(): Int = store.pendingOutbox().count { it.entityType != SyncEntity.CELL }

    /**
     * Включение синхронизации на этом компьютере (docs/SYNC-YANDEX.md §6).
     *
     * Хранилища нет — этот компьютер первый: создаётся `sync.json`, и вся база уходит первым
     * пакетом. Хранилище есть — компьютер подключается: база обязана быть пустой, её
     * стартовые категории уступают место категориям с Диска.
     */
    suspend fun enable(): SyncOutcome = mutex.withLock {
        guard {
            val existing = transport.read(STORE_FILE)
            val deviceId = store.state(KEY_DEVICE_ID) ?: ids.newId().also { store.setState(KEY_DEVICE_ID, it) }
            if (existing == null) {
                createStore(deviceId)
            } else {
                val info = SyncCodec.decodeStore(existing)
                if (info.format > SyncCodec.FORMAT) return@guard SyncOutcome.Failed(SyncProblem.NeedsUpdate("Диск"))
                if (store.hasUserData()) return@guard SyncOutcome.Failed(SyncProblem.LocalNotEmpty)
                store.transaction {
                    store.deleteUnusedCategories()
                    store.clearPendingOutbox()
                    store.setState(KEY_STORE_ID, info.storeId)
                    store.setState(KEY_SEQ, "0")
                    store.setState(KEY_ENABLED, "true")
                }
                val outcome = syncLocked()
                // Всё, что пришло с Диска, уже там; свои стартовые записи не отправляем.
                if (outcome is SyncOutcome.Done) store.markAllSynced(time.nowUtcMillis())
                outcome
            }
        }
    }

    /** Отключение: локальные данные остаются, на Диске ничего не удаляется. */
    suspend fun disable() = mutex.withLock {
        store.setState(KEY_ENABLED, "false")
    }

    /**
     * Пересоздание хранилища с этого компьютера — ответ на [SyncProblem.RemoteMissing] или
     * [SyncProblem.RemoteCorrupt]. Старые файлы на Диске удаляются, вся база этого компьютера
     * уходит первым пакетом. Остальным компьютерам придётся подключиться заново.
     */
    suspend fun recreateRemote(): SyncOutcome = mutex.withLock {
        guard {
            transport.delete(DEVICES_DIR)
            transport.delete(STORE_FILE)
            val deviceId = store.state(KEY_DEVICE_ID) ?: ids.newId().also { store.setState(KEY_DEVICE_ID, it) }
            store.transaction {
                // Курсоры чужих компьютеров больше не имеют смысла.
                store.state(KEY_CURSORS)?.split(',')?.filter { it.isNotBlank() }?.forEach { store.removeState(cursorKey(it)) }
                store.removeState(KEY_CURSORS)
                store.removeState(KEY_INFLIGHT)
            }
            createStore(deviceId)
        }
    }

    /**
     * База восстановлена из снапшота (SPEC.md F7.7, D-30): номера пакетов и курсоры в ней
     * устарели, и следующая отправка затёрла бы на Диске уже выложенный пакет. Поэтому
     * компьютер получает новое имя на Диске и заново читает все пакеты, включая свои прежние:
     * повтор безопасен — ревизии не дублируются, остальное решает «кто последний».
     */
    suspend fun resetAfterRestore() = mutex.withLock {
        store.transaction {
            store.state(KEY_CURSORS)?.split(',')?.filter { it.isNotBlank() }?.forEach { store.removeState(cursorKey(it)) }
            listOf(KEY_CURSORS, KEY_INFLIGHT, KEY_INFLIGHT_SEQ, KEY_INFLIGHT_IDS, KEY_DEVICE_SCHEMA).forEach(store::removeState)
            store.setState(KEY_DEVICE_ID, ids.newId())
            store.setState(KEY_SEQ, "0")
        }
    }

    /** Обычная синхронизация: получить чужие изменения, отправить свои. */
    suspend fun sync(): SyncOutcome = mutex.withLock {
        if (!isEnabled) return@withLock SyncOutcome.NotEnabled
        guard { syncLocked() }
    }

    // ── Внутреннее ────────────────────────────────────────────────────────────────

    private suspend fun createStore(deviceId: String): SyncOutcome {
        val now = time.nowUtcMillis()
        val info = StoreInfo(format = SyncCodec.FORMAT, storeId = ids.newId(), createdAtUtc = now)
        val changes = translateForExport(store.exportAll())
        val pack = SyncPack(SyncCodec.FORMAT, deviceId, 1, config.schemaVersion, config.appVersion, now, changes)
        transport.write(packPath(deviceId, 1), SyncCodec.encodePack(pack))
        transport.write(STORE_FILE, SyncCodec.encodeStore(info))
        store.transaction {
            store.markAllSynced(now)
            store.setState(KEY_STORE_ID, info.storeId)
            store.setState(KEY_SEQ, "1")
            store.setState(KEY_ENABLED, "true")
            store.setState(KEY_LAST_SYNC, now.toString())
        }
        writeDevice(deviceId, 1)
        return SyncOutcome.Done(received = 0, sent = changes.size)
    }

    private suspend fun syncLocked(): SyncOutcome {
        val deviceId = store.state(KEY_DEVICE_ID) ?: return SyncOutcome.NotEnabled

        // 1. Хранилище на месте и то же самое.
        val storeText = transport.read(STORE_FILE) ?: return SyncOutcome.Failed(SyncProblem.RemoteMissing)
        val info = SyncCodec.decodeStore(storeText)
        if (info.format > SyncCodec.FORMAT) return SyncOutcome.Failed(SyncProblem.NeedsUpdate("Диск"))
        if (info.storeId != store.state(KEY_STORE_ID)) return SyncOutcome.Failed(SyncProblem.RemoteReplaced)

        // 2. Чужие компьютеры: версии и новые пакеты.
        val others = (transport.list(DEVICES_DIR) ?: emptyList()).filter { it != deviceId }
        val incoming = mutableListOf<Pair<String, SyncPack>>()
        for (other in others) {
            val deviceText = transport.read("$DEVICES_DIR/$other/$DEVICE_FILE") ?: continue
            val device = SyncCodec.decodeDevice(deviceText)
            if (device.schemaVersion > config.schemaVersion) {
                return SyncOutcome.Failed(SyncProblem.NeedsUpdate(device.name))
            }
            val cursor = store.state(cursorKey(other))?.toLongOrNull() ?: 0L
            for (seq in (cursor + 1)..device.lastSeq) {
                val text = transport.read(packPath(other, seq))
                    ?: return SyncOutcome.Failed(SyncProblem.RemoteCorrupt("нет файла пакета $seq компьютера «${device.name}»"))
                val pack = SyncCodec.decodePack(text)
                if (pack.format > SyncCodec.FORMAT || pack.schemaVersion > config.schemaVersion) {
                    return SyncOutcome.Failed(SyncProblem.NeedsUpdate(device.name))
                }
                incoming += other to pack
            }
        }

        // 3. Применение — одной транзакцией, после снимка базы.
        var received = 0
        if (incoming.isNotEmpty()) {
            snapshotter.snapshot("pre-sync")
            store.transaction {
                val merger = Merger(store, time)
                incoming.flatMap { it.second.changes }
                    .sortedBy { it.order } // стабильная сортировка: порядок внутри типа сохраняется
                    .forEach { merger.apply(it) }
                merger.recomputeTouchedCells()
                received = incoming.sumOf { it.second.changes.size }
                val known = store.state(KEY_CURSORS)?.split(',')?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
                incoming.groupBy { it.first }.forEach { (device, packs) ->
                    store.setState(cursorKey(device), packs.maxOf { it.second.seq }.toString())
                    known += device
                }
                store.setState(KEY_CURSORS, known.joinToString(","))
            }
        }

        // 4. Свои изменения.
        val sent = push(deviceId)
        // Версию этого компьютера видят остальные даже без его изменений: иначе компьютер
        // со старой версией не узнал бы, что пора обновиться.
        if (store.state(KEY_DEVICE_SCHEMA) != config.schemaVersion.toString()) {
            writeDevice(deviceId, store.state(KEY_SEQ)?.toLongOrNull() ?: 0L)
        }
        store.setState(KEY_LAST_SYNC, time.nowUtcMillis().toString())
        return SyncOutcome.Done(received = received, sent = sent)
    }

    /**
     * Отправка. Пакет сначала сохраняется локально как «в пути» и только потом загружается:
     * если загрузка оборвётся, следующая попытка отправит тот же пакет под тем же номером, и
     * компьютер, успевший прочитать первую попытку, не пропустит изменения.
     */
    private suspend fun push(deviceId: String): Int {
        val inflight = store.state(KEY_INFLIGHT)
        val seq: Long
        val text: String
        val changeIds: List<String>
        val count: Int
        if (inflight != null) {
            seq = store.state(KEY_INFLIGHT_SEQ)?.toLongOrNull() ?: return 0
            changeIds = store.state(KEY_INFLIGHT_IDS)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            text = inflight
            count = SyncCodec.decodePack(text).changes.size
        } else {
            val pending = store.pendingOutbox()
            if (pending.isEmpty()) return 0
            val exported = pending.mapNotNull { store.export(it) }
            val changes = translateForExport(exported.distinct())
            if (changes.isEmpty()) {
                store.markSynced(pending.map { it.changeId }, time.nowUtcMillis())
                return 0
            }
            seq = (store.state(KEY_SEQ)?.toLongOrNull() ?: 0L) + 1
            text = SyncCodec.encodePack(
                SyncPack(SyncCodec.FORMAT, deviceId, seq, config.schemaVersion, config.appVersion, time.nowUtcMillis(), changes),
            )
            changeIds = pending.map { it.changeId }
            count = changes.size
            store.transaction {
                store.setState(KEY_INFLIGHT, text)
                store.setState(KEY_INFLIGHT_SEQ, seq.toString())
                store.setState(KEY_INFLIGHT_IDS, changeIds.joinToString(","))
            }
        }
        transport.write(packPath(deviceId, seq), text)
        store.transaction {
            store.markSynced(changeIds, time.nowUtcMillis())
            store.setState(KEY_SEQ, seq.toString())
            store.removeState(KEY_INFLIGHT)
            store.removeState(KEY_INFLIGHT_SEQ)
            store.removeState(KEY_INFLIGHT_IDS)
        }
        writeDevice(deviceId, seq)
        return count
    }

    private suspend fun writeDevice(deviceId: String, lastSeq: Long) {
        val info = DeviceInfo(
            deviceId = deviceId,
            name = config.deviceName,
            os = config.os,
            appVersion = config.appVersion,
            schemaVersion = config.schemaVersion,
            lastSeq = lastSeq,
            updatedAtUtc = time.nowUtcMillis(),
        )
        transport.write("$DEVICES_DIR/$deviceId/$DEVICE_FILE", SyncCodec.encodeDevice(info))
        store.setState(KEY_DEVICE_SCHEMA, config.schemaVersion.toString())
    }

    /**
     * Перевод локальных id в общие. Если запись слита с чужой (одноимённая категория, ячейка
     * того же месяца), у неё несколько id; наружу уходит наименьший — тот же самый на всех
     * компьютерах, знающих оба id.
     */
    private fun translateForExport(changes: List<SyncChange>): List<SyncChange> {
        fun canon(entity: String, id: String): String = (store.aliasesTo(entity, id) + id).min()
        return changes.map { change ->
            when (change) {
                is SyncChange.CategoryChange -> change.copy(category = change.category.copy(id = canon(SyncEntity.CATEGORY, change.category.id)))
                is SyncChange.AccountChange -> change.copy(
                    account = change.account.copy(
                        id = canon(SyncEntity.ACCOUNT, change.account.id),
                        categoryId = canon(SyncEntity.CATEGORY, change.account.categoryId),
                    ),
                )
                is SyncChange.SnapshotChange -> change.copy(
                    snapshot = change.snapshot.copy(
                        id = canon(SyncEntity.SNAPSHOT, change.snapshot.id),
                        accountId = canon(SyncEntity.ACCOUNT, change.snapshot.accountId),
                    ),
                )
                is SyncChange.RevisionChange -> change.copy(
                    revision = change.revision.copy(cellId = canon(SyncEntity.CELL, change.revision.cellId)),
                    categoryId = canon(SyncEntity.CATEGORY, change.categoryId),
                )
            }
        }
    }

    /** Ошибки транспорта и формата превращаются в понятный итог, а не в исключение наверх. */
    private suspend fun guard(block: suspend () -> SyncOutcome): SyncOutcome = try {
        block()
    } catch (e: SyncTransportException) {
        SyncOutcome.Failed(SyncProblem.Transport(e.kind, e.message ?: "ошибка связи"))
    } catch (e: SyncFormatException) {
        SyncOutcome.Failed(SyncProblem.RemoteCorrupt(e.message ?: "файл не читается"))
    }

    companion object {
        const val STORE_FILE = "sync.json"
        const val DEVICES_DIR = "devices"
        const val DEVICE_FILE = "device.json"

        const val KEY_ENABLED = "sync.enabled"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_STORE_ID = "sync.storeId"
        const val KEY_SEQ = "sync.seq"
        const val KEY_LAST_SYNC = "sync.lastSyncAtUtc"
        const val KEY_CURSORS = "sync.cursors"
        const val KEY_INFLIGHT = "sync.inflight"
        const val KEY_INFLIGHT_SEQ = "sync.inflightSeq"
        const val KEY_INFLIGHT_IDS = "sync.inflightIds"
        const val KEY_DEVICE_SCHEMA = "sync.publishedSchema"

        fun cursorKey(deviceId: String) = "sync.cursor.$deviceId"

        fun packPath(deviceId: String, seq: Long) = "$DEVICES_DIR/$deviceId/changes-%06d.jsonl".format(seq)
    }
}

/**
 * Применение чужих изменений (docs/SYNC-YANDEX.md §5). Вызывается внутри транзакции.
 *
 * «Кто последний, тот и прав» — больше `updatedAtUtc`, при равенстве больше `rowVersion`;
 * при полном равенстве остаётся своё.
 */
internal class Merger(private val store: SyncStore, private val time: TimeProvider) {

    private val touchedCells = linkedSetOf<String>()

    fun apply(change: SyncChange) {
        when (change) {
            is SyncChange.CategoryChange -> applyCategory(change.category)
            is SyncChange.AccountChange -> applyAccount(change.account)
            is SyncChange.SnapshotChange -> applySnapshot(change.snapshot)
            is SyncChange.RevisionChange -> applyRevision(change)
        }
    }

    private fun resolve(entity: String, id: String): String = store.aliasOf(entity, id) ?: id

    private fun newer(updatedA: Long, versionA: Int, updatedB: Long, versionB: Int): Boolean =
        updatedA > updatedB || (updatedA == updatedB && versionA > versionB)

    private fun applyCategory(remote: Category) {
        val localId = resolve(SyncEntity.CATEGORY, remote.id)
        val local = store.categoryById(localId)
        if (local != null) {
            if (newer(remote.updatedAtUtc, remote.rowVersion, local.updatedAtUtc, local.rowVersion)) {
                store.replaceCategory(remote.copy(id = local.id))
            }
            return
        }
        // Одноимённая категория того же раздела — сливаем автоматически (решение владельца).
        val twin = if (remote.deletedAtUtc == null) {
            store.categoriesInSection(remote.section)
                .filter { it.deletedAtUtc == null }
                .sortedBy { it.isArchived } // активная важнее архивной
                .firstOrNull { it.name.trim().equals(remote.name.trim(), ignoreCase = true) }
        } else {
            null
        }
        if (twin != null) {
            store.putAlias(SyncEntity.CATEGORY, remote.id, twin.id)
            if (newer(remote.updatedAtUtc, remote.rowVersion, twin.updatedAtUtc, twin.rowVersion)) {
                store.replaceCategory(remote.copy(id = twin.id))
            }
        } else {
            store.insertCategory(remote)
        }
    }

    private fun applyAccount(remote: AssetAccount) {
        val categoryId = requireLocal(SyncEntity.CATEGORY, remote.categoryId) { store.categoryById(it) != null }
        val mapped = remote.copy(id = resolve(SyncEntity.ACCOUNT, remote.id), categoryId = categoryId)
        val local = store.accountById(mapped.id)
        if (local == null) {
            store.insertAccount(mapped)
        } else if (newer(remote.updatedAtUtc, remote.rowVersion, local.updatedAtUtc, local.rowVersion)) {
            store.replaceAccount(mapped)
        }
    }

    private fun applySnapshot(remote: AssetSnapshot) {
        val accountId = requireLocal(SyncEntity.ACCOUNT, remote.accountId) { store.accountById(it) != null }
        val byId = store.snapshotById(resolve(SyncEntity.SNAPSHOT, remote.id))
        val local = byId ?: store.snapshotByKey(accountId, remote.period)?.also {
            // Снапшот того же месяца, введённый на другом компьютере, — одна запись.
            store.putAlias(SyncEntity.SNAPSHOT, remote.id, it.id)
        }
        if (local == null) {
            store.insertSnapshot(remote.copy(accountId = accountId))
        } else if (newer(remote.updatedAtUtc, remote.rowVersion, local.updatedAtUtc, local.rowVersion)) {
            store.replaceSnapshot(remote.copy(id = local.id, accountId = accountId))
        }
    }

    private fun applyRevision(change: SyncChange.RevisionChange) {
        val revision = change.revision
        if (store.revisionExists(revision.id)) return
        val categoryId = requireLocal(SyncEntity.CATEGORY, change.categoryId) { store.categoryById(it) != null }

        val cell = store.cellById(resolve(SyncEntity.CELL, revision.cellId))
            ?: store.cellByKey(categoryId, change.period)?.also {
                // Ячейка того же месяца и категории с другого компьютера — та же ячейка.
                store.putAlias(SyncEntity.CELL, revision.cellId, it.id)
            }
            ?: CategoryMonthCell(
                id = revision.cellId,
                section = change.section,
                categoryId = categoryId,
                period = change.period,
                amount = Money(0, revision.amountAfter.currency),
                createdAtUtc = revision.createdAtUtc,
                updatedAtUtc = revision.createdAtUtc,
            ).also { store.insertCell(it) }

        store.insertRevision(revision.copy(cellId = cell.id))
        touchedCells += cell.id
    }

    /** Значение затронутых ячеек — повтор всех их ревизий (своих и чужих) по порядку. */
    fun recomputeTouchedCells() {
        for (cellId in touchedCells) {
            val cell = store.cellById(cellId) ?: continue
            val replayed = CellReplay.replay(store.revisionsOfCell(cellId), cell.amount.currency)
            val lastChange = store.revisionsOfCell(cellId).maxOfOrNull { it.createdAtUtc } ?: cell.updatedAtUtc
            val updated = cell.copy(
                amount = replayed.amount,
                comment = replayed.comment,
                deletedAtUtc = if (replayed.deleted) cell.deletedAtUtc ?: lastChange else null,
                updatedAtUtc = maxOf(cell.updatedAtUtc, lastChange),
                rowVersion = cell.rowVersion + 1,
            )
            if (updated != cell.copy(rowVersion = cell.rowVersion + 1)) store.replaceCell(updated)
        }
    }

    private fun requireLocal(entity: String, remoteId: String, exists: (String) -> Boolean): String {
        val id = resolve(entity, remoteId)
        if (!exists(id)) throw SyncFormatException("Ссылка на неизвестную запись $entity/$remoteId")
        return id
    }
}

/**
 * Значение ячейки по её ревизиям (D-29). Тот же расчёт на всех компьютерах даёт одинаковый
 * результат при одинаковом наборе ревизий.
 *
 * - создание (нет суммы «до»): прибавка дельты (у удалённой ячейки — сумма = дельта), ячейка живая;
 * - явная правка суммы (`SET_AMOUNT`): сумма = итог ревизии;
 * - удаление / восстановление: только признак удаления;
 * - остальное с дельтой (прибавка, импорт, дублирование, отмена): сумма += дельта;
 * - комментарий: если ревизия его поменяла — берётся итог ревизии.
 */
object CellReplay {

    data class State(val amount: Money, val comment: String?, val deleted: Boolean)

    /**
     * Порядок повтора — по времени ревизии. Сортировка устойчивая: ревизии одного момента
     * (правка суммы и комментария одним действием) остаются в порядке записи в базе.
     */
    fun order(revisions: List<CategoryMonthRevision>): List<CategoryMonthRevision> =
        revisions.sortedBy { it.createdAtUtc }

    fun replay(revisions: List<CategoryMonthRevision>, currency: String): State {
        var amount = Money(0, currency)
        var comment: String? = null
        var deleted = false
        for (r in order(revisions)) {
            when {
                r.op == RevisionOp.DELETE -> deleted = true
                r.op == RevisionOp.UNDELETE -> deleted = false
                r.op == RevisionOp.SET_COMMENT -> Unit
                r.amountBefore == null -> {
                    // Создание. Ячейку одного месяца могли создать на двух компьютерах — тогда
                    // это две прибавки. Сброс суммы — только если ячейка была удалена (импорт
                    // после отмены создаёт ячейку заново).
                    val created = r.delta ?: r.amountAfter
                    amount = if (deleted) created else amount + created
                    deleted = false
                }
                r.op == RevisionOp.SET_AMOUNT -> amount = r.amountAfter
                r.delta != null -> amount += r.delta!!
            }
            if (r.commentAfter != r.commentBefore) comment = r.commentAfter
        }
        return State(amount, comment, deleted)
    }
}
