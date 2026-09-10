package app.finance.core.domain

import app.finance.core.model.CategoryMonthRevision
import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import app.finance.core.model.RevisionOp
import app.finance.core.model.RevisionSource

/** Что делать, если в целевом месяце ячейка уже есть (SPEC.md F5.2). */
enum class ConflictPolicy {
    /** Не трогать заполненные категории — безопасно по умолчанию. */
    SKIP,

    /** Прибавить скопированную сумму к существующей. */
    ADD,

    /** Заменить сумму существующей ячейки. Требует явного подтверждения в UI. */
    OVERWRITE,
}

data class DuplicateOptions(
    val conflictPolicy: ConflictPolicy = ConflictPolicy.SKIP,
    /**
     * По умолчанию false: суммы повторяются (аренда, подписки), а комментарии
     * относятся к прошлому месяцу и переносить их смысла нет.
     */
    val copyComments: Boolean = false,
)

data class DuplicateReport(
    val section: LedgerSection,
    val source: Period,
    val target: Period,
    val created: Int = 0,
    val added: Int = 0,
    val overwritten: Int = 0,
    val skippedExisting: Int = 0,
    val skippedArchived: Int = 0,
) {
    /** Сколько ячеек реально изменилось — для решения, нужен ли бэкап и Undo. */
    val changed: Int get() = created + added + overwritten

    /** Человекочитаемая сводка для диалога результата (SPEC.md F5.6). */
    fun summaryRu(): String = buildString {
        append("Скопировано: $created")
        if (added > 0) append(", прибавлено: $added")
        if (overwritten > 0) append(", перезаписано: $overwritten")
        if (skippedExisting > 0) append(", пропущено (уже заполнено): $skippedExisting")
        if (skippedArchived > 0) append(", проигнорировано (архивные): $skippedArchived")
    }
}

/**
 * Копирование месяца в другой месяц — работает одинаково для расходов и доходов
 * (SPEC.md F5). Для активов отдельный use-case в M4: там копируются остатки счетов,
 * а не потоки.
 *
 * Гарантии: одна транзакция на всю операцию, на каждую изменённую строку — ревизия
 * `DUPLICATE` с указанием месяца-источника, молчаливая перезапись запрещена.
 */
class DuplicatePeriod(
    private val categories: CategoryRepository,
    private val ledger: LedgerRepository,
    private val ids: IdGenerator,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {
    suspend operator fun invoke(
        section: LedgerSection,
        source: Period,
        target: Period,
        options: DuplicateOptions = DuplicateOptions(),
    ): DuplicateReport = transactions.inTransaction {
        require(source != target) { "Месяц-источник и месяц-назначение совпадают: $source" }

        val sourceCells = ledger.cellsForPeriod(section, source)
        val targetByCategory = ledger.cellsForPeriod(section, target).associateBy { it.categoryId }
        val now = time.nowUtcMillis()

        var created = 0
        var added = 0
        var overwritten = 0
        var skippedExisting = 0
        var skippedArchived = 0

        for (cell in sourceCells) {
            val category = categories.byId(cell.categoryId)
            // Архивные и удалённые категории не переносим, но показываем в отчёте.
            if (category == null || !category.isSelectable) {
                skippedArchived++
                continue
            }

            val existing = targetByCategory[cell.categoryId]
            val incomingComment = if (options.copyComments) cell.comment else null

            if (existing == null) {
                val newCell = CategoryMonthCell(
                    id = ids.newId(),
                    section = section,
                    categoryId = cell.categoryId,
                    period = target,
                    amount = cell.amount,
                    comment = CommentMerger.merge(null, incomingComment),
                    createdAtUtc = now,
                    updatedAtUtc = now,
                    rowVersion = 1,
                )
                ledger.insertCell(newCell)
                ledger.insertRevision(
                    duplicateRevision(
                        cellId = newCell.id,
                        amountBefore = null,
                        amountAfter = newCell.amount,
                        delta = cell.amount,
                        commentBefore = null,
                        commentAfter = newCell.comment,
                        source = source,
                        now = now,
                    ),
                )
                created++
                continue
            }

            when (options.conflictPolicy) {
                ConflictPolicy.SKIP -> skippedExisting++

                ConflictPolicy.ADD -> {
                    val mergedComment = CommentMerger.merge(existing.comment, incomingComment)
                    val updated = existing.copy(
                        amount = existing.amount + cell.amount,
                        comment = mergedComment,
                        updatedAtUtc = now,
                        rowVersion = existing.rowVersion + 1,
                    )
                    ledger.updateCell(updated)
                    ledger.insertRevision(
                        duplicateRevision(
                            cellId = existing.id,
                            amountBefore = existing.amount,
                            amountAfter = updated.amount,
                            delta = cell.amount,
                            commentBefore = existing.comment,
                            commentAfter = updated.comment,
                            source = source,
                            now = now,
                        ),
                    )
                    added++
                }

                ConflictPolicy.OVERWRITE -> {
                    // Перезаписываем сумму; комментарий без явного запроса не уничтожаем.
                    val newComment = if (options.copyComments) CommentMerger.replace(incomingComment) else existing.comment
                    val updated = existing.copy(
                        amount = cell.amount,
                        comment = newComment,
                        updatedAtUtc = now,
                        rowVersion = existing.rowVersion + 1,
                    )
                    ledger.updateCell(updated)
                    ledger.insertRevision(
                        duplicateRevision(
                            cellId = existing.id,
                            amountBefore = existing.amount,
                            amountAfter = updated.amount,
                            delta = updated.amount - existing.amount,
                            commentBefore = existing.comment,
                            commentAfter = updated.comment,
                            source = source,
                            now = now,
                        ),
                    )
                    overwritten++
                }
            }
        }

        DuplicateReport(
            section = section,
            source = source,
            target = target,
            created = created,
            added = added,
            overwritten = overwritten,
            skippedExisting = skippedExisting,
            skippedArchived = skippedArchived,
        )
    }

    private fun duplicateRevision(
        cellId: String,
        amountBefore: Money?,
        amountAfter: Money,
        delta: Money,
        commentBefore: String?,
        commentAfter: String?,
        source: Period,
        now: Long,
    ): CategoryMonthRevision = CategoryMonthRevision(
        id = ids.newId(),
        cellId = cellId,
        op = RevisionOp.DUPLICATE,
        amountAfter = amountAfter,
        amountBefore = amountBefore,
        delta = delta,
        commentBefore = commentBefore,
        commentAfter = commentAfter,
        source = RevisionSource.DUPLICATE,
        sourcePeriod = source,
        createdAtUtc = now,
    )
}
