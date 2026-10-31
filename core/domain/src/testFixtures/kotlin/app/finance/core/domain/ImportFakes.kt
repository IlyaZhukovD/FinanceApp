package app.finance.core.domain

import app.finance.core.model.ImportBatch
import app.finance.core.model.ImportBatchStatus

/** Повторяет контракт `SqlImportBatchRepository`: ключи строк учитываются только у APPLIED. */
class FakeImportBatchRepository : ImportBatchRepository {
    val batchesById = linkedMapOf<String, ImportBatch>()
    val links = mutableListOf<ImportRowLink>()
    var categoryMap: Map<String, String> = emptyMap()

    override suspend fun insertBatch(batch: ImportBatch) {
        check(batch.id !in batchesById) { "Пакет ${batch.id} уже есть" }
        batchesById[batch.id] = batch
    }

    override suspend fun updateStatus(batchId: String, status: ImportBatchStatus, updatedAtUtc: Long) {
        val batch = requireNotNull(batchesById[batchId])
        batchesById[batchId] = batch.copy(status = status, updatedAtUtc = updatedAtUtc, rowVersion = batch.rowVersion + 1)
    }

    override suspend fun batchById(id: String): ImportBatch? = batchesById[id]

    override suspend fun batches(): List<ImportBatch> = batchesById.values.sortedByDescending { it.createdAtUtc }

    override suspend fun importedRowKeys(keys: Collection<String>): Set<String> {
        val applied = batchesById.values.filter { it.status == ImportBatchStatus.APPLIED }.map { it.id }.toSet()
        val wanted = keys.toSet()
        return links.filter { it.batchId in applied && it.rowKey in wanted }.map { it.rowKey }.toSet()
    }

    override suspend fun insertRowLinks(links: List<ImportRowLink>) {
        links.forEach { check(it.batchId in batchesById) { "Пакет ${it.batchId} не записан" } }
        this.links += links
    }

    override suspend fun revisionIdsOfBatch(batchId: String): List<String> =
        links.filter { it.batchId == batchId }.map { it.revisionId }.distinct()

    override suspend fun savedCategoryMap(): Map<String, String> = categoryMap

    override suspend fun saveCategoryMap(map: Map<String, String>) {
        categoryMap = map
    }
}

/** Снапшотер без файлов: считает вызовы. */
class FakeSnapshotter : DatabaseSnapshotter {
    var calls = 0
        private set

    override suspend fun snapshot(label: String): String {
        calls++
        return "backups/snapshot-$calls-$label.db"
    }
}
