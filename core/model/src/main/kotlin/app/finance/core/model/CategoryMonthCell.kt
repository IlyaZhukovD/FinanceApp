package app.finance.core.model

/**
 * Ячейка «категория + месяц» — единица учёта расходов и доходов (SPEC.md §6, D-02).
 *
 * Отдельных «покупок» как сущностей нет: при повторном вводе суммы в ту же категорию
 * того же месяца сумма аккумулируется в этой же строке. Каждая такая операция
 * фиксируется в [CategoryMonthRevision], поэтому ввод обратим и аудируем.
 *
 * Уникальность обеспечивается частичным индексом БД
 * `uq_category_month_active ON category_month(category_id, period) WHERE deleted_at IS NULL`.
 */
data class CategoryMonthCell(
    val id: String,
    val section: LedgerSection,
    val categoryId: String,
    val period: Period,
    val amount: Money,
    val comment: String? = null,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
    val rowVersion: Int = 1,
    val deletedAtUtc: Long? = null,
) {
    val isDeleted: Boolean get() = deletedAtUtc != null

    val hasComment: Boolean get() = !comment.isNullOrBlank()
}

/** Тип операции над ячейкой. Пишется в журнал ревизий. */
enum class RevisionOp {
    /** Ячейка создана первым вводом. */
    CREATE,

    /** Сумма прибавлена к существующей ячейке (основной сценарий аккумулирования). */
    ADD,

    /** Сумма ячейки заменена явно (ручная правка, сторно). */
    SET_AMOUNT,

    /** Комментарий перезаписан вручную — слияние не применяется. */
    SET_COMMENT,

    DELETE,
    UNDELETE,

    /** Ячейка создана копированием из другого месяца. */
    DUPLICATE,

    /** Ячейка создана или изменена импортом. */
    IMPORT,

    /** Отмена предыдущей операции. */
    UNDO,
}

/** Источник изменения — влияет на правила дедупликации и отчёты. */
enum class RevisionSource {
    MANUAL,
    IMPORT,
    DUPLICATE,
    UNDO,
}

/**
 * Append-only запись журнала изменений ячейки. Не изменяется и не удаляется:
 * на ней держатся Undo, экран истории и будущая синхронизация.
 */
data class CategoryMonthRevision(
    val id: String,
    val cellId: String,
    val op: RevisionOp,
    val amountAfter: Money,
    val amountBefore: Money? = null,
    val delta: Money? = null,
    val commentBefore: String? = null,
    val commentAfter: String? = null,
    val source: RevisionSource,
    val sourcePeriod: Period? = null,
    val idempotencyKey: String? = null,
    val createdAtUtc: Long,
)
