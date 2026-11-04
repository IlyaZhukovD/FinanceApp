package app.finance.core.data

import app.finance.core.data.db.Asset_account as AccountRow
import app.finance.core.data.db.Asset_snapshot as SnapshotRow
import app.finance.core.data.db.Category as CategoryRow
import app.finance.core.data.db.Category_month as CellRow
import app.finance.core.data.db.Category_month_revision as RevisionRow
import app.finance.core.model.DepositCapitalization
import app.finance.core.model.AssetAccount
import app.finance.core.model.AssetBalancePolicy
import app.finance.core.model.AssetSnapshot
import app.finance.core.model.Category
import app.finance.core.model.CategoryMonthCell
import app.finance.core.model.CategoryMonthRevision
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import app.finance.core.model.RevisionOp
import app.finance.core.model.RevisionSource

/**
 * Преобразование строк БД в доменные модели.
 *
 * Здесь же закрыты расхождения SQLite и будущего PostgreSQL (SPEC.md §3, D-01):
 * SQLite отдаёт INTEGER как Long, а домен использует Int для счётчиков и Period;
 * enum хранятся как TEXT-имена.
 */
internal fun CategoryRow.toDomain(): Category = Category(
    id = id,
    section = CategorySection.valueOf(section),
    name = name,
    color = color,
    icon = icon,
    parentId = parent_id,
    sortOrder = sort_order.toInt(),
    balancePolicy = AssetBalancePolicy.valueOf(balance_policy),
    allowsNegativeBalance = allows_negative_balance,
    isArchived = is_archived,
    createdAtUtc = created_at_utc,
    updatedAtUtc = updated_at_utc,
    rowVersion = row_version.toInt(),
    deletedAtUtc = deleted_at_utc,
)

internal fun CellRow.toDomain(): CategoryMonthCell = CategoryMonthCell(
    id = id,
    section = LedgerSection.valueOf(section),
    categoryId = category_id,
    period = Period.ofKey(period.toInt()),
    amount = Money(amount_minor, currency),
    comment = comment,
    createdAtUtc = created_at_utc,
    updatedAtUtc = updated_at_utc,
    rowVersion = row_version.toInt(),
    deletedAtUtc = deleted_at_utc,
)

internal fun RevisionRow.toDomain(): CategoryMonthRevision = CategoryMonthRevision(
    id = id,
    cellId = cell_id,
    op = RevisionOp.valueOf(op),
    amountAfter = Money(amount_after_minor, currency),
    amountBefore = amount_before_minor?.let { Money(it, currency) },
    delta = delta_minor?.let { Money(it, currency) },
    commentBefore = comment_before,
    commentAfter = comment_after,
    source = RevisionSource.valueOf(source),
    sourcePeriod = source_period?.toInt()?.let(Period::ofKey),
    idempotencyKey = idempotency_key,
    createdAtUtc = created_at_utc,
)

/** Счёт активов. */
internal fun AccountRow.toDomain(): AssetAccount = AssetAccount(
    id = id,
    categoryId = category_id,
    name = name,
    currency = currency,
    openedPeriod = Period.ofKey(opened_period.toInt()),
    closedPeriod = closed_period?.toInt()?.let(Period::ofKey),
    annualRateBp = annual_rate_bp?.toInt(),
    termMonths = term_months?.toInt(),
    interestDay = interest_day?.toInt(),
    capitalization = DepositCapitalization.fromDb(capitalization),
    monthlyReminder = monthly_reminder,
    note = note,
    createdAtUtc = created_at_utc,
    updatedAtUtc = updated_at_utc,
    rowVersion = row_version.toInt(),
    deletedAtUtc = deleted_at_utc,
)

/**
 * Снапшот остатка. В таблице `asset_snapshot` нет колонки валюты: она равна валюте
 * счёта, поэтому подставляется вызывающим кодом (см. KDoc `SqlAssetRepository`).
 */
internal fun SnapshotRow.toDomain(currency: String): AssetSnapshot = AssetSnapshot(
    id = id,
    accountId = account_id,
    period = Period.ofKey(period.toInt()),
    balance = Money(balance_minor, currency),
    contribution = contribution_minor?.let { Money(it, currency) },
    note = note,
    createdAtUtc = created_at_utc,
    updatedAtUtc = updated_at_utc,
    rowVersion = row_version.toInt(),
    deletedAtUtc = deleted_at_utc,
)

/** Ключ периода в БД хранится как INTEGER, поэтому Long. */
internal fun Period.toDbKey(): Long = key.toLong()
