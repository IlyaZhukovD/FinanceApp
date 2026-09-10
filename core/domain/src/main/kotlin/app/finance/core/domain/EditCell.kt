package app.finance.core.domain

import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.CategoryMonthRevision
import app.finance.core.model.Money
import app.finance.core.model.RevisionOp
import app.finance.core.model.RevisionSource

/**
 * Изменение комментария. `Keep` и «очистить» — разные действия, поэтому null
 * в [Set] означает именно очистку, а не «не трогать».
 */
sealed interface CommentChange {
    data object Keep : CommentChange

    /** null или пустая строка — очистить комментарий. */
    data class Set(val text: String?) : CommentChange
}

/** Результат правки: итоговая ячейка и все записанные ревизии. */
data class EditResult(
    val cell: CategoryMonthCell,
    val revisions: List<CategoryMonthRevision>,
)

/** Результат одиночной операции над ячейкой. */
data class CellChange(
    val cell: CategoryMonthCell,
    val revision: CategoryMonthRevision,
)

/**
 * Явная правка ячейки из её панели (SPEC.md F1.3).
 *
 * В отличие от [RecordAmount] здесь нет слияния: пользователь правит конкретное
 * значение. Если изменены и сумма, и комментарий, пишутся **две** ревизии —
 * журнал должен отражать каждое изменение отдельной записью.
 */
class EditCell(
    private val ledger: LedgerRepository,
    private val ids: IdGenerator,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {
    suspend operator fun invoke(
        cellId: String,
        amount: Money? = null,
        comment: CommentChange = CommentChange.Keep,
    ): EditResult = transactions.inTransaction {
        val cell = requireNotNull(ledger.cellById(cellId)) { "Ячейка $cellId не найдена" }
        require(cell.deletedAtUtc == null) { "Ячейка удалена: сначала восстановите её" }
        if (amount != null) require(!amount.isZero()) { "Сумма не может быть нулевой" }

        val now = time.nowUtcMillis()
        val revisions = mutableListOf<CategoryMonthRevision>()
        var current = cell

        if (amount != null && amount != current.amount) {
            val before = current.amount
            current = current.copy(amount = amount, updatedAtUtc = now, rowVersion = current.rowVersion + 1)
            val revision = CategoryMonthRevision(
                id = ids.newId(),
                cellId = cellId,
                op = RevisionOp.SET_AMOUNT,
                amountAfter = amount,
                amountBefore = before,
                delta = amount - before,
                commentBefore = current.comment,
                commentAfter = current.comment,
                source = RevisionSource.MANUAL,
                createdAtUtc = now,
            )
            revisions += revision
            ledger.updateCell(current)
            // Журнал append-only и обязателен: на нём держатся история ячейки и Undo
            // (SPEC.md §7). Без записи правка осталась бы только в возвращаемом результате.
            ledger.insertRevision(revision)
        }

        if (comment is CommentChange.Set) {
            val updated = CommentMerger.replace(comment.text)
            if (updated != current.comment) {
                val before = current.comment
                current = current.copy(comment = updated, updatedAtUtc = now, rowVersion = current.rowVersion + 1)
                val revision = CategoryMonthRevision(
                    id = ids.newId(),
                    cellId = cellId,
                    op = RevisionOp.SET_COMMENT,
                    amountAfter = current.amount,
                    amountBefore = current.amount,
                    commentBefore = before,
                    commentAfter = updated,
                    source = RevisionSource.MANUAL,
                    createdAtUtc = now,
                )
                revisions += revision
                ledger.updateCell(current)
                ledger.insertRevision(revision)
            }
        }

        EditResult(current, revisions)
    }
}

/**
 * Удаление ячейки — soft delete (SPEC.md §5, §7): ставится `deletedAtUtc`, строка
 * остаётся в БД как tombstone. Именно поэтому уникальность в БД обеспечена частичным
 * индексом `WHERE deleted_at IS NULL`: удалённую ячейку можно завести заново.
 */
class DeleteCell(
    private val ledger: LedgerRepository,
    private val ids: IdGenerator,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {
    suspend operator fun invoke(cellId: String): CellChange = transactions.inTransaction {
        val cell = requireNotNull(ledger.cellById(cellId)) { "Ячейка $cellId не найдена" }
        require(cell.deletedAtUtc == null) { "Ячейка уже удалена" }

        val now = time.nowUtcMillis()
        val updated = cell.copy(
            deletedAtUtc = now,
            updatedAtUtc = now,
            rowVersion = cell.rowVersion + 1,
        )
        val revision = CategoryMonthRevision(
            id = ids.newId(),
            cellId = cellId,
            op = RevisionOp.DELETE,
            amountAfter = cell.amount,
            amountBefore = cell.amount,
            commentBefore = cell.comment,
            commentAfter = cell.comment,
            source = RevisionSource.MANUAL,
            createdAtUtc = now,
        )
        ledger.updateCell(updated)
        ledger.insertRevision(revision)
        CellChange(updated, revision)
    }
}

/** Отмена удаления: снимает tombstone и пишет ревизию UNDELETE. */
class RestoreCell(
    private val ledger: LedgerRepository,
    private val ids: IdGenerator,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {
    suspend operator fun invoke(cellId: String): CellChange = transactions.inTransaction {
        val cell = requireNotNull(ledger.cellById(cellId)) { "Ячейка $cellId не найдена" }
        require(cell.deletedAtUtc != null) { "Ячейка не удалена" }

        val now = time.nowUtcMillis()
        val updated = cell.copy(
            deletedAtUtc = null,
            updatedAtUtc = now,
            rowVersion = cell.rowVersion + 1,
        )
        val revision = CategoryMonthRevision(
            id = ids.newId(),
            cellId = cellId,
            op = RevisionOp.UNDELETE,
            amountAfter = cell.amount,
            amountBefore = cell.amount,
            commentBefore = cell.comment,
            commentAfter = cell.comment,
            source = RevisionSource.UNDO,
            createdAtUtc = now,
        )
        ledger.updateCell(updated)
        ledger.insertRevision(revision)
        CellChange(updated, revision)
    }
}
