package app.finance.core.domain

import app.finance.core.model.AssetAccount
import app.finance.core.model.AssetKind
import app.finance.core.model.AssetSnapshot
import app.finance.core.model.CategorySection
import app.finance.core.model.Money
import app.finance.core.model.Period

/**
 * Активы — остатки (stock), а не потоки: балансы нельзя складывать с расходами и доходами
 * (SPEC.md §1, F6.8). Единица учёта здесь другая — снапшот счёта за месяц с
 * `UNIQUE(account, period)`, поэтому движок `category_month` не переиспользуется (D-12).
 */

// ── Снапшот остатка за месяц (F4.3) ──────────────────────────────────────────

/** Ввод остатка счёта за месяц. Баланс обязателен, взнос и заметка — нет. */
data class SnapshotInput(
    val accountId: String,
    val period: Period,
    val balance: Money,
    /** Взносы (+) и снятия (−) за месяц: нужны для разложения Δ на взносы и прирост (F4.1). */
    val contribution: Money? = null,
    val note: String? = null,
)

sealed interface SnapshotResult {
    val snapshot: AssetSnapshot

    /** Снапшота за этот месяц не было — создан новый. */
    data class Created(override val snapshot: AssetSnapshot) : SnapshotResult

    /** Снапшот уже был — повторный ввод за тот же месяц означает редактирование (F4.3). */
    data class Updated(
        override val snapshot: AssetSnapshot,
        val balanceBefore: Money,
    ) : SnapshotResult
}

/**
 * Запись остатка счёта за месяц.
 *
 * Ключевое поведение продукта: пара `(счёт, месяц)` уникальна, поэтому второй ввод
 * за тот же месяц не создаёт вторую строку, а правит существующую (F4.3). Это
 * противоположно расходам, где повторный ввод аккумулируется (D-02).
 *
 * Отрицательный баланс допустим только для счетов, у которых
 * [AssetKind.allowsNegativeBalance] (кредит): иначе минус означал бы либо ошибку ввода,
 * либо долг, который продуктом учитывается как отдельный счёт (F4.1 — Net Worth
 * включает отрицательные балансы кредитов).
 */
class RecordSnapshot(
    private val assets: AssetRepository,
    private val ids: IdGenerator,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {

    suspend operator fun invoke(input: SnapshotInput): SnapshotResult =
        transactions.inTransaction {
            val account = requireNotNull(assets.accountById(input.accountId)) {
                "Счёт ${input.accountId} не найден"
            }
            require(account.deletedAtUtc == null) {
                "Счёт «${account.name}» удалён"
            }
            require(account.isActiveAt(input.period)) {
                val closed = account.closedPeriod?.let { ", закрыт с $it" } ?: ""
                "Счёт «${account.name}» не активен в $input.period: открыт с " +
                    "${account.openedPeriod}$closed"
            }
            require(input.balance.currency == account.currency) {
                "Баланс указан в ${input.balance.currency}, а счёт «${account.name}» " +
                    "ведётся в ${account.currency}"
            }
            if (input.balance.isNegative()) {
                require(account.kind.allowsNegativeBalance) {
                    "Отрицательный баланс допустим только для кредита, а " +
                        "«${account.name}» — это ${account.kind}"
                }
            }
            input.contribution?.let { contribution ->
                require(contribution.currency == account.currency) {
                    "Взнос указан в ${contribution.currency}, а счёт «${account.name}» " +
                        "ведётся в ${account.currency}"
                }
            }

            val now = time.nowUtcMillis()
            val existing = assets.findSnapshot(input.accountId, input.period)

            if (existing == null) {
                val snapshot = AssetSnapshot(
                    id = ids.newId(),
                    accountId = account.id,
                    period = input.period,
                    balance = input.balance,
                    contribution = input.contribution,
                    note = input.note?.trim()?.takeIf { it.isNotEmpty() },
                    createdAtUtc = now,
                    updatedAtUtc = now,
                    rowVersion = 1,
                )
                assets.insertSnapshot(snapshot)
                SnapshotResult.Created(snapshot)
            } else {
                val snapshot = existing.copy(
                    balance = input.balance,
                    contribution = input.contribution,
                    note = input.note?.trim()?.takeIf { it.isNotEmpty() },
                    updatedAtUtc = now,
                    rowVersion = existing.rowVersion + 1,
                )
                assets.updateSnapshot(snapshot)
                SnapshotResult.Updated(snapshot = snapshot, balanceBefore = existing.balance)
            }
        }
}

// ── Счета (F4.2, F4.5) ───────────────────────────────────────────────────────

/** Параметры нового счёта. Период закрытия задаётся отдельной операцией (F4.5). */
data class AccountInput(
    val categoryId: String,
    val name: String,
    val kind: AssetKind,
    val currency: String = Money.DEFAULT_CURRENCY,
    val openedPeriod: Period,
    /** Годовая ставка в базисных пунктах: 850 == 8.50%. Только для вкладов (F4.4). */
    val annualRateBp: Int? = null,
    val note: String? = null,
)

/**
 * Создание счёта.
 *
 * Категория обязана относиться к секции [CategorySection.ASSET]: наборы категорий
 * расходов, доходов и активов независимы (SPEC.md §1).
 */
class CreateAccount(
    private val categories: CategoryRepository,
    private val assets: AssetRepository,
    private val ids: IdGenerator,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {

    suspend operator fun invoke(input: AccountInput): AssetAccount =
        transactions.inTransaction {
            val category = requireNotNull(categories.byId(input.categoryId)) {
                "Категория ${input.categoryId} не найдена"
            }
            require(category.section == CategorySection.ASSET) {
                "Категория «${category.name}» относится к секции ${category.section}, " +
                    "а счёт создаётся в секции ${CategorySection.ASSET}"
            }
            require(category.isSelectable) {
                "Категория «${category.name}» в архиве: восстановите её или выберите другую"
            }
            val name = input.name.trim()
            require(name.isNotEmpty()) { "Название счёта не может быть пустым" }
            require(input.currency.isNotBlank()) { "Код валюты не может быть пустым" }
            if (input.annualRateBp != null) {
                require(input.annualRateBp >= 0) {
                    "Ставка не может быть отрицательной: ${input.annualRateBp}"
                }
                // Прогноз по ставке определён только для вкладов (F4.4).
                require(input.kind == AssetKind.DEPOSIT) {
                    "Годовая ставка задаётся только для вклада, а выбран тип ${input.kind}"
                }
            }

            val now = time.nowUtcMillis()
            val account = AssetAccount(
                id = ids.newId(),
                categoryId = category.id,
                name = name,
                kind = input.kind,
                currency = input.currency,
                openedPeriod = input.openedPeriod,
                annualRateBp = input.annualRateBp,
                note = input.note?.trim()?.takeIf { it.isNotEmpty() },
                createdAtUtc = now,
                updatedAtUtc = now,
                rowVersion = 1,
            )
            assets.insertAccount(account)
            account
        }
}

/**
 * Закрытие счёта (F4.5): ставит `closed_period`, история снапшотов сохраняется,
 * а счёт перестаёт участвовать в дублировании месяца (D-06).
 */
class CloseAccount(
    private val assets: AssetRepository,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) {

    suspend operator fun invoke(accountId: String, closedPeriod: Period): AssetAccount =
        transactions.inTransaction {
            val account = requireNotNull(assets.accountById(accountId)) {
                "Счёт $accountId не найден"
            }
            require(account.deletedAtUtc == null) { "Счёт «${account.name}» удалён" }
            require(account.closedPeriod == null) {
                "Счёт «${account.name}» уже закрыт периодом ${account.closedPeriod}"
            }
            require(closedPeriod >= account.openedPeriod) {
                "Период закрытия $closedPeriod раньше периода открытия ${account.openedPeriod}"
            }

            assets.closeAccount(
                accountId = account.id,
                closedPeriod = closedPeriod,
                updatedAtUtc = time.nowUtcMillis(),
            )
            // Перечитываем: closed_period и row_version меняет запрос хранилища.
            requireNotNull(assets.accountById(account.id)) {
                "Счёт ${account.id} исчез после закрытия"
            }
        }
}

// ── Итоги раздела (F4.1) ─────────────────────────────────────────────────────

/**
 * Итоги по одной валюте. Валюты не смешиваются: сложение разных валют запрещено
 * на уровне [Money], а курсов конвертации в v1 нет (SPEC.md §5).
 */
data class CurrencyTotal(
    val currency: String,
    /** Сумма балансов месяца, включая отрицательные балансы кредитов (F4.1). */
    val netWorth: Money,
    /** null, если в прошлом месяце не было ни одного снапшота этой валюты. */
    val previousNetWorth: Money?,
    val delta: Money?,
    /** Сумма взносов и снятий месяца; снятия уменьшают её. */
    val contributions: Money,
    /**
     * Инвестиционный прирост: `Δ − взносы` (F4.1).
     *
     * В SPEC формула записана по счёту — `balance[M] − balance[M−1] − contribution[M]`;
     * в сумме по всем счетам месяца она даёт ровно `Δ − взносы`, поэтому считаем
     * агрегированно и не теряем счета, у которых нет снапшота за прошлый месяц.
     */
    val gain: Money?,
    val accountCount: Int,
)

/** Итоги раздела «Активы» за месяц: по одному [CurrencyTotal] на каждую валюту. */
data class AssetTotals(
    val period: Period,
    val totals: List<CurrencyTotal>,
) {
    val isEmpty: Boolean get() = totals.isEmpty()

    val hasMultipleCurrencies: Boolean get() = totals.size > 1

    /** Итоги основной валюты — их шапка экрана показывает крупно. */
    fun of(currency: String = Money.DEFAULT_CURRENCY): CurrencyTotal? =
        totals.firstOrNull { it.currency == currency }
}

/**
 * Расчёт Net Worth и разложения изменения на взносы и прирост (F4.1).
 *
 * Балансы не переносятся из месяца в месяц: в итогах участвуют только снапшоты
 * выбранного периода. Пропущенный месяц означает «остаток не введён», а не ноль;
 * для переноса существует «Дублировать прошлый месяц» (F5.3).
 *
 * Только чтение, поэтому транзакция не нужна.
 */
class AssetSummary(private val assets: AssetRepository) {

    suspend operator fun invoke(period: Period): AssetTotals {
        val current = assets.snapshotsForPeriod(period)
        if (current.isEmpty()) return AssetTotals(period = period, totals = emptyList())

        val previous = assets.snapshotsForPeriod(period.minusMonths(1))

        val totals = current
            .groupBy { it.balance.currency }
            .map { (currency, snapshots) ->
                val zero = Money(0L, currency)
                val netWorth = snapshots.fold(zero) { acc, snapshot -> acc + snapshot.balance }
                val contributions = snapshots.fold(zero) { acc, snapshot ->
                    snapshot.contribution?.let { acc + it } ?: acc
                }
                val previousSnapshots = previous.filter { it.balance.currency == currency }
                val previousNetWorth = previousSnapshots
                    .fold(zero) { acc, snapshot -> acc + snapshot.balance }
                    .takeIf { previousSnapshots.isNotEmpty() }
                val delta = previousNetWorth?.let { netWorth - it }

                CurrencyTotal(
                    currency = currency,
                    netWorth = netWorth,
                    previousNetWorth = previousNetWorth,
                    delta = delta,
                    contributions = contributions,
                    gain = delta?.let { it - contributions },
                    accountCount = snapshots.size,
                )
            }

        // Основная валюта — первой: порядок определяет, что шапка покажет крупно.
        val ordered = totals.sortedWith(
            compareByDescending<CurrencyTotal> { it.currency == Money.DEFAULT_CURRENCY }
                .thenBy { it.currency },
        )
        return AssetTotals(period = period, totals = ordered)
    }
}
