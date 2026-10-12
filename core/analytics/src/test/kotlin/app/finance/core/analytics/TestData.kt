package app.finance.core.analytics

import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period

/** Общая фабрика ячеек для тестов аналитики. */
internal fun cell(
    categoryId: String,
    period: Period,
    minor: Long,
    section: LedgerSection = LedgerSection.EXPENSE,
    currency: String = Money.DEFAULT_CURRENCY,
    deleted: Boolean = false,
): CategoryMonthCell = CategoryMonthCell(
    id = "$categoryId-${period.key}",
    section = section,
    categoryId = categoryId,
    period = period,
    amount = Money(minor, currency),
    createdAtUtc = 0L,
    updatedAtUtc = 0L,
    deletedAtUtc = if (deleted) 1L else null,
)

internal fun rub(minor: Long): Money = Money(minor, Money.DEFAULT_CURRENCY)
