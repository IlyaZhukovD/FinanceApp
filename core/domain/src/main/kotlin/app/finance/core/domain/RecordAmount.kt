package app.finance.core.domain

import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.CategoryMonthRevision
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import app.finance.core.model.RevisionOp
import app.finance.core.model.RevisionSource

/** Ввод суммы за месяц: основной пользовательский сценарий (SPEC.md F1.4). */
data class AmountInput(
    val section: LedgerSection,
    val categoryId: String,
    val period: Period,
    val amount: Money,
    val comment: String? = null,
    val source: RevisionSource = RevisionSource.MANUAL,
    /** Ключ идемпотентности для импорта; при ручном вводе не используется. */
    val idempotencyKey: String? = null,
)

sealed interface RecordAmountResult {
    val cell: CategoryMonthCell
    val revision: CategoryMonthRevision

    /** Ячейки раньше не было — создана новая. */
    data class Created(
        override val cell: CategoryMonthCell,
        override val revision: CategoryMonthRevision,
    ) : RecordAmountResult

    /** Ячейка уже была — сумма аккумулирована в неё же (SPEC.md F1.5). */
    data class Accumulated(
        override val cell: CategoryMonthCell,
        override val revision: CategoryMonthRevision,
    ) : RecordAmountResult

    /** Такой ключ идемпотентности уже применён — данные не менялись. */
    data class AlreadyApplied(
        override val cell: CategoryMonthCell,
        override val revision: CategoryMonthRevision,
    ) : RecordAmountResult
}

/**
 * Запись суммы за месяц с аккумулированием.
 *
 * Ключевое поведение продукта: если категория уже есть в этом месяце, сумма
 * прибавляется к существующей ячейке, а не создаёт вторую строку. Каждая операция
 * оставляет запись в журнале ревизий, поэтому ошибочный ввод обратим.
 *
 * Отрицательная сумма допустима и означает сторно/корректировку: отдельной сущности
 * «возврат товара» в продукте нет (D-02).
 */
class RecordAmount(
    private val categories: CategoryRepository,
    private val ledger: LedgerRepository,
    private val ids: IdGenerator,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {
    suspend operator fun invoke(input: AmountInput): RecordAmountResult =
        transactions.inTransaction {
            val category = requireNotNull(categories.byId(input.categoryId)) {
                "Категория ${input.categoryId} не найдена"
            }
            require(category.isSelectable) {
                "Категория «${category.name}» в архиве: восстановите её или выберите другую"
            }
            require(category.section == CategorySection.of(input.section)) {
                "Категория «${category.name}» относится к секции ${category.section}, " +
                    "а ввод выполняется в секции ${input.section}"
            }
            require(!input.amount.isZero()) { "Сумма не может быть нулевой" }

            // Повторный импорт того же файла не должен удваивать сумму.
            val idempotencyKey = input.idempotencyKey
            if (idempotencyKey != null) {
                val applied = ledger.revisionByIdempotencyKey(idempotencyKey)
                if (applied != null) {
                    val cell = ledger.cellById(applied.cellId)
                    if (cell != null) return@inTransaction RecordAmountResult.AlreadyApplied(cell, applied)
                }
            }

            val now = time.nowUtcMillis()
            val existing = ledger.findCell(input.categoryId, input.period)

            if (existing == null) {
                val cell = CategoryMonthCell(
                    id = ids.newId(),
                    section = input.section,
                    categoryId = input.categoryId,
                    period = input.period,
                    amount = input.amount,
                    comment = CommentMerger.merge(null, input.comment),
                    createdAtUtc = now,
                    updatedAtUtc = now,
                    rowVersion = 1,
                )
                val revision = CategoryMonthRevision(
                    id = ids.newId(),
                    cellId = cell.id,
                    op = RevisionOp.CREATE,
                    amountAfter = cell.amount,
                    amountBefore = null,
                    delta = input.amount,
                    commentBefore = null,
                    commentAfter = cell.comment,
                    source = input.source,
                    idempotencyKey = idempotencyKey,
                    createdAtUtc = now,
                )
                ledger.insertCell(cell)
                ledger.insertRevision(revision)
                RecordAmountResult.Created(cell, revision)
            } else {
                // Сложение проверяет совпадение валют и переполнение внутри Money.
                val accumulated = existing.amount + input.amount
                val mergedComment = CommentMerger.merge(existing.comment, input.comment)
                val cell = existing.copy(
                    amount = accumulated,
                    comment = mergedComment,
                    updatedAtUtc = now,
                    rowVersion = existing.rowVersion + 1,
                )
                val revision = CategoryMonthRevision(
                    id = ids.newId(),
                    cellId = existing.id,
                    op = RevisionOp.ADD,
                    amountAfter = accumulated,
                    amountBefore = existing.amount,
                    delta = input.amount,
                    commentBefore = existing.comment,
                    commentAfter = mergedComment,
                    source = input.source,
                    idempotencyKey = idempotencyKey,
                    createdAtUtc = now,
                )
                ledger.updateCell(cell)
                ledger.insertRevision(revision)
                RecordAmountResult.Accumulated(cell, revision)
            }
        }
}
