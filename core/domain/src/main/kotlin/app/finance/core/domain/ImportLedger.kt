package app.finance.core.domain

import app.finance.core.model.Category
import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.CategoryMonthRevision
import app.finance.core.model.CategorySection
import app.finance.core.model.ImportBatch
import app.finance.core.model.ImportBatchStatus
import app.finance.core.model.ImportedFile
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import app.finance.core.model.RevisionOp
import app.finance.core.model.RevisionSource

/** Связь «строка источника → ревизия ячейки, в которую она вошла». */
data class ImportRowLink(
    val rowKey: String,
    val batchId: String,
    val revisionId: String,
    val createdAtUtc: Long,
)

/**
 * Хранилище пакетов импорта (docs/IMPORT-NOTION.md §5.1).
 *
 * Ключи строк учитываются только у пакетов в статусе APPLIED: после отмены импорта те же
 * строки можно импортировать заново.
 */
interface ImportBatchRepository {
    suspend fun insertBatch(batch: ImportBatch)

    suspend fun updateStatus(batchId: String, status: ImportBatchStatus, updatedAtUtc: Long)

    suspend fun batchById(id: String): ImportBatch?

    /** Живые пакеты, новые сверху. */
    suspend fun batches(): List<ImportBatch>

    /** Какие из [keys] уже импортированы действующими пакетами. */
    suspend fun importedRowKeys(keys: Collection<String>): Set<String>

    suspend fun insertRowLinks(links: List<ImportRowLink>)

    /** Ревизии пакета без повторов: на ячейку пакет пишет ровно одну ревизию. */
    suspend fun revisionIdsOfBatch(batchId: String): List<String>

    /** Сопоставление «категория источника → id категории», запомненное прошлым импортом. */
    suspend fun savedCategoryMap(): Map<String, String>

    suspend fun saveCategoryMap(map: Map<String, String>)
}

/**
 * Копия базы перед импортом (F10.1: «обязательный снапшот до импорта»).
 *
 * Выполняется вне транзакции: SQLite не умеет `VACUUM INTO` внутри неё.
 * @return путь к созданному файлу — его показывают в отчёте.
 */
fun interface DatabaseSnapshotter {
    suspend fun snapshot(label: String): String
}

/** Куда положить строку: в существующую категорию или в новую, которую создаст импорт. */
sealed interface CategoryTarget {
    data class Existing(val categoryId: String) : CategoryTarget

    /** Новая категория с таким именем; если активная с тем же именем уже есть — берётся она. */
    data class New(val name: String) : CategoryTarget
}

/** Строка источника, готовая к записи: период, сумма, категория уже определены. */
data class ImportRowInput(
    val key: String,
    val section: LedgerSection,
    val target: CategoryTarget,
    val period: Period,
    val amount: Money,
    val comment: String? = null,
)

data class ImportRequest(
    val files: List<ImportedFile>,
    val rows: List<ImportRowInput>,
    /** Сколько строк было в источниках всего — для истории, включая пропущенные. */
    val rowsTotal: Int,
    val source: String = ImportBatch.SOURCE_NOTION_CSV,
)

data class ImportReport(
    /** null — нечего импортировать: все строки уже перенесены ранее. */
    val batch: ImportBatch?,
    val rowsImported: Int,
    val rowsAlreadyImported: Int,
    val cellsCreated: Int,
    val cellsUpdated: Int,
    /** Строки, сумма которых в ячейке дала ноль: ячейка не создаётся (F1.4). */
    val rowsZeroSum: Int,
    val categoriesCreated: List<Category>,
    /** Самый ранний месяц импорта по разделам — куда перейти после импорта. */
    val firstPeriod: Map<LedgerSection, Period>,
    val backupPath: String?,
)

/**
 * Импорт строк в ячейки «категория + месяц» (SPEC.md F10.1, docs/IMPORT-NOTION.md §3.5).
 *
 * - Перед записью делается снапшот базы; без него импорт не начинается.
 * - Всё в одной транзакции: категории, ячейки, ревизии, `sync_outbox`, пакет.
 * - Строки группируются в ячейки `(раздел, категория, месяц)`; на ячейку — ОДНА ревизия
 *   `op = IMPORT`. Существующая ячейка аккумулируется (F1.5), комментарии сливаются.
 * - Уже импортированные строки (по ключу) пропускаются — повторный импорт ничего не удваивает.
 */
class ImportLedgerBatch(
    private val categories: CategoryRepository,
    private val ledger: LedgerRepository,
    private val batches: ImportBatchRepository,
    private val snapshotter: DatabaseSnapshotter,
    private val ids: IdGenerator,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {
    suspend operator fun invoke(request: ImportRequest): ImportReport {
        val uniqueRows = request.rows.distinctBy { it.key }
        // Быстрая проверка до снапшота: если всё уже импортировано, копию базы не плодим.
        val knownBefore = batches.importedRowKeys(uniqueRows.map { it.key })
        if (uniqueRows.none { it.key !in knownBefore }) {
            return emptyReport(alreadyImported = uniqueRows.size)
        }

        val backupPath = snapshotter.snapshot("pre-import")

        return transactions.inTransaction {
            val known = batches.importedRowKeys(uniqueRows.map { it.key })
            val fresh = uniqueRows.filter { it.key !in known }
            if (fresh.isEmpty()) return@inTransaction emptyReport(uniqueRows.size).copy(backupPath = backupPath)

            val now = time.nowUtcMillis()
            val batchId = ids.newId()
            val resolver = CategoryResolver(categories, ids, now)

            val groups = linkedMapOf<CellKey, MutableList<ImportRowInput>>()
            for (row in fresh) {
                val categoryId = resolver.resolve(row.section, row.target)
                groups.getOrPut(CellKey(row.section, categoryId, row.period)) { mutableListOf() } += row
            }

            var created = 0
            var updated = 0
            var zeroRows = 0
            val links = mutableListOf<ImportRowLink>()
            for ((key, rows) in groups) {
                val currency = rows.first().amount.currency
                val sum = rows.fold(Money(0, currency)) { acc, row -> acc + row.amount }
                if (sum.isZero()) {
                    zeroRows += rows.size
                    continue
                }
                val existing = ledger.findCell(key.categoryId, key.period)
                val comment = mergeComments(existing?.comment, rows.mapNotNull { it.comment })
                val revisionId = ids.newId()
                if (existing == null) {
                    val cell = CategoryMonthCell(
                        id = ids.newId(),
                        section = key.section,
                        categoryId = key.categoryId,
                        period = key.period,
                        amount = sum,
                        comment = comment,
                        createdAtUtc = now,
                        updatedAtUtc = now,
                    )
                    ledger.insertCell(cell)
                    ledger.insertRevision(importRevision(revisionId, batchId, cell, before = null, beforeComment = null, delta = sum, now))
                    created++
                } else {
                    val cell = existing.copy(
                        amount = existing.amount + sum,
                        comment = comment,
                        updatedAtUtc = now,
                        rowVersion = existing.rowVersion + 1,
                    )
                    ledger.updateCell(cell)
                    ledger.insertRevision(
                        importRevision(revisionId, batchId, cell, before = existing.amount, beforeComment = existing.comment, delta = sum, now),
                    )
                    updated++
                }
                rows.forEach { links += ImportRowLink(it.key, batchId, revisionId, now) }
            }

            val batch = ImportBatch(
                id = batchId,
                source = request.source,
                files = request.files,
                rowsTotal = request.rowsTotal,
                rowsImported = links.size,
                cellsTouched = created + updated,
                createdCategoryIds = resolver.created.map { it.id },
                status = ImportBatchStatus.APPLIED,
                createdAtUtc = now,
                updatedAtUtc = now,
            )
            batches.insertBatch(batch)
            batches.insertRowLinks(links)

            val firstPeriod = groups.keys.groupBy { it.section }.mapValues { (_, keys) -> keys.minOf { it.period } }
            ImportReport(
                batch = batch,
                rowsImported = links.size,
                rowsAlreadyImported = uniqueRows.size - fresh.size,
                cellsCreated = created,
                cellsUpdated = updated,
                rowsZeroSum = zeroRows,
                categoriesCreated = resolver.created,
                firstPeriod = firstPeriod,
                backupPath = backupPath,
            )
        }
    }

    private fun emptyReport(alreadyImported: Int) = ImportReport(
        batch = null,
        rowsImported = 0,
        rowsAlreadyImported = alreadyImported,
        cellsCreated = 0,
        cellsUpdated = 0,
        rowsZeroSum = 0,
        categoriesCreated = emptyList(),
        firstPeriod = emptyMap(),
        backupPath = null,
    )

    private fun importRevision(
        id: String,
        batchId: String,
        cell: CategoryMonthCell,
        before: Money?,
        beforeComment: String?,
        delta: Money,
        now: Long,
    ) = CategoryMonthRevision(
        id = id,
        cellId = cell.id,
        op = RevisionOp.IMPORT,
        amountAfter = cell.amount,
        amountBefore = before,
        delta = delta,
        commentBefore = beforeComment,
        commentAfter = cell.comment,
        source = RevisionSource.IMPORT,
        idempotencyKey = "import:$batchId:${cell.id}",
        createdAtUtc = now,
    )

    /**
     * Слияние по правилу F1.5, но построчно: одинаковые строки не дублируются, даже если
     * в ячейке уже несколько строк комментария.
     */
    private fun mergeComments(existing: String?, incoming: List<String>): String? {
        val lines = existing?.split(CommentMerger.LINE_SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.toMutableList() ?: mutableListOf()
        for (text in incoming) {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty() && trimmed !in lines) lines += trimmed
        }
        return lines.joinToString(CommentMerger.LINE_SEPARATOR).ifEmpty { null }
    }
}

/** Ячейка, в которую складываются строки импорта: раздел, категория, месяц. */
private data class CellKey(val section: LedgerSection, val categoryId: String, val period: Period)

/** Определяет id категории для строки и создаёт недостающие категории — по одной на имя. */
private class CategoryResolver(
    private val categories: CategoryRepository,
    private val ids: IdGenerator,
    private val now: Long,
) {
    val created = mutableListOf<Category>()
    private val cache = mutableMapOf<Pair<CategorySection, String>, String>()

    suspend fun resolve(section: LedgerSection, target: CategoryTarget): String {
        val categorySection = CategorySection.of(section)
        return when (target) {
            is CategoryTarget.Existing -> {
                val category = requireNotNull(categories.byId(target.categoryId)) {
                    "Категория ${target.categoryId} не найдена"
                }
                require(category.section == categorySection) {
                    "Категория «${category.name}» относится к другому разделу"
                }
                require(category.isSelectable) {
                    "Категория «${category.name}» в архиве: восстановите её или выберите другую"
                }
                category.id
            }

            is CategoryTarget.New -> {
                val name = target.name.trim()
                require(name.isNotEmpty()) { "Название категории не может быть пустым" }
                cache.getOrPut(categorySection to name.lowercase()) {
                    val active = categories.bySection(categorySection, includeArchived = false)
                    active.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id ?: run {
                        val category = Category(
                            id = ids.newId(),
                            section = categorySection,
                            name = name,
                            color = Category.defaultColor(active.size),
                            sortOrder = (active.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                            createdAtUtc = now,
                            updatedAtUtc = now,
                        )
                        categories.create(category)
                        created += category
                        category.id
                    }
                }
            }
        }
    }
}

data class UndoImportReport(
    val cellsReverted: Int,
    val cellsRemoved: Int,
    /** Ячейки, которые после импорта удалили вручную: их не трогаем. */
    val cellsSkipped: Int,
    val categoriesArchived: Int,
)

/**
 * Отмена пакета импорта целиком (docs/IMPORT-NOTION.md §4).
 *
 * Ничего не удаляется физически: на каждую ячейку пакета пишется обратная ревизия
 * `op = UNDO` (сумма уменьшается на вклад импорта), а ячейка, которую создал импорт и
 * которая после отмены обнулилась, получает tombstone. Правки, сделанные после импорта
 * вручную, сохраняются — вычитается только то, что добавил импорт.
 */
class UndoImportBatch(
    private val categories: CategoryRepository,
    private val ledger: LedgerRepository,
    private val batches: ImportBatchRepository,
    private val ids: IdGenerator,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {
    suspend operator fun invoke(batchId: String): UndoImportReport = transactions.inTransaction {
        val batch = requireNotNull(batches.batchById(batchId)) { "Импорт $batchId не найден" }
        require(batch.status == ImportBatchStatus.APPLIED) { "Этот импорт уже отменён" }
        val now = time.nowUtcMillis()

        var reverted = 0
        var removed = 0
        var skipped = 0
        for (revisionId in batches.revisionIdsOfBatch(batchId)) {
            val revision = ledger.revisionById(revisionId) ?: continue
            val delta = revision.delta ?: continue
            val cell = ledger.cellById(revision.cellId)
            if (cell == null || cell.isDeleted) {
                skipped++
                continue
            }
            val amount = cell.amount - delta
            val comment = if (cell.comment == revision.commentAfter) revision.commentBefore else cell.comment
            val updated = cell.copy(
                amount = if (amount.isZero()) cell.amount else amount,
                comment = comment,
                deletedAtUtc = if (amount.isZero()) now else null,
                updatedAtUtc = now,
                rowVersion = cell.rowVersion + 1,
            )
            ledger.updateCell(updated)
            ledger.insertRevision(
                CategoryMonthRevision(
                    id = ids.newId(),
                    cellId = cell.id,
                    op = if (amount.isZero()) RevisionOp.DELETE else RevisionOp.UNDO,
                    amountAfter = updated.amount,
                    amountBefore = cell.amount,
                    delta = if (amount.isZero()) null else -delta,
                    commentBefore = cell.comment,
                    commentAfter = updated.comment,
                    source = RevisionSource.UNDO,
                    createdAtUtc = now,
                ),
            )
            if (amount.isZero()) removed++ else reverted++
        }

        // Категории, созданные импортом и оставшиеся без сумм, уходят в архив (F3.2).
        var archived = 0
        for (categoryId in batch.createdCategoryIds) {
            val category = categories.byId(categoryId) ?: continue
            if (category.isArchived) continue
            val section = category.section.toLedgerSection()
            val hasCells = ledger.cellsInRange(section, FULL_RANGE_FROM, FULL_RANGE_TO).any { it.categoryId == categoryId }
            if (!hasCells) {
                categories.setArchived(categoryId, archived = true, updatedAtUtc = now)
                archived++
            }
        }

        batches.updateStatus(batchId, ImportBatchStatus.UNDONE, now)
        UndoImportReport(reverted, removed, skipped, archived)
    }

    private companion object {
        val FULL_RANGE_FROM = Period(Period.MIN_YEAR, 1)
        val FULL_RANGE_TO = Period(Period.MAX_YEAR, 12)
    }
}
