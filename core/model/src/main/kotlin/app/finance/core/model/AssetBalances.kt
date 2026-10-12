package app.finance.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Откуда взялось значение счёта за месяц (SPEC.md F4.6, D-21).
 *
 * Тип нужен, чтобы интерфейс честно показывал происхождение суммы: прогноз вклада —
 * не факт, а перенесённая стоимость актива с ручной оценкой — черновик, который
 * не входит в итоги, пока пользователь его не подтвердил.
 */
enum class BalanceSource {
    /** Остаток ввёл пользователь: фактическое значение месяца. */
    RECORDED,

    /** Посчитано расписанием вклада по ставке. В базу не пишется (D-21). */
    PROJECTED,

    /** Перенесено с прошлого месяца: актив без роста, значение учитывается сразу. */
    CARRIED,

    /** Перенесено как черновик: ждёт подтверждения и в итоги НЕ входит. */
    CARRIED_DRAFT,

    /** Значения нет: за месяц ничего не введено и переносить нечего. */
    MISSING,
    ;

    /** Значение участвует в Net Worth, Δ и сериях капитала. */
    val isCounted: Boolean get() = this == RECORDED || this == PROJECTED || this == CARRIED

    /** Черновик, который пользователь должен подтвердить или исправить. */
    val isDraft: Boolean get() = this == CARRIED_DRAFT
}

/**
 * Значение счёта за месяц вместе с источником.
 *
 * [basedOnPeriod] — месяц, из которого значение получено: для прогноза и переноса это
 * месяц последнего введённого остатка, для факта — сам [period]. Интерфейс показывает его
 * в подсказке («по данным за 09.2026»), чтобы расчётная сумма не выглядела введённой.
 */
data class AccountBalance(
    val accountId: String,
    val period: Period,
    /** null при [BalanceSource.MISSING]. */
    val balance: Money?,
    val source: BalanceSource,
    val basedOnPeriod: Period? = null,
    /** Процент, начисленный расписанием за этот месяц (только для [BalanceSource.PROJECTED]). */
    val accruedInterest: Money? = null,
    /** Срок вклада истёк: ставка больше не начисляется, счёт предлагаем закрыть (D-21). */
    val isMatured: Boolean = false,
) {
    /** Значение входит в итоги месяца. */
    val isCounted: Boolean get() = balance != null && source.isCounted
}

/**
 * Итоги месяца по одной валюте, посчитанные по правилам перехода месяца.
 *
 * Общий вход для итогов раздела «Активы» (`AssetSummary`) и серии капитала
 * (`NetWorthSeries`): оба должны показывать одно и то же число (D-19, D-21).
 */
data class AssetMonthTotal(
    val period: Period,
    val currency: String,
    /** Сумма учтённых значений: факт, прогноз вкладов и перенос активов без роста. */
    val netWorth: Money,
    /** Часть [netWorth], посчитанная расписанием вкладов, а не введённая пользователем. */
    val projected: Money,
    /** Взносы и снятия месяца — только из введённых снапшотов. */
    val contributions: Money,
    /** Число учтённых значений месяца. */
    val valueCount: Int,
)

/**
 * Правила перехода месяца для активов (SPEC.md F4.6, D-20, D-21).
 *
 * Единственный источник истины: экран активов, итоги раздела и график капитала обязаны
 * вызывать его, иначе числа разойдутся (урок D-19).
 *
 * Приоритет значения за месяц:
 * 1. введённый пользователем снапшот — факт ([BalanceSource.RECORDED]);
 * 2. категория с известной ставкой — расписание вклада: прогноз считается на лету и
 *    в базу не пишется ([BalanceSource.PROJECTED]);
 * 3. категория с ручной оценкой — перенос прошлого остатка черновиком, который не
 *    учитывается до подтверждения ([BalanceSource.CARRIED_DRAFT]);
 * 4. категория без роста — перенос прошлого остатка, учитывается сразу
 *    ([BalanceSource.CARRIED]);
 * 5. иначе значения нет ([BalanceSource.MISSING]).
 *
 * Счёт, закрытый на этот месяц, значения не имеет (F4.5, D-19) — даже если снапшот
 * за месяц существует: это история, а не актив месяца.
 */
object AssetBalances {

    /**
     * Значения всех счетов за месяц.
     *
     * @param snapshots снапшоты счетов ДО [period] включительно: нужны и факты месяца,
     *   и последний известный остаток для прогноза и переноса.
     */
    fun resolve(
        period: Period,
        accounts: List<AssetAccount>,
        categories: List<Category>,
        snapshots: List<AssetSnapshot>,
    ): List<AccountBalance> {
        val policies = categories.associate { it.id to it.balancePolicy }
        val byAccount = snapshots
            .filter { it.deletedAtUtc == null }
            .groupBy { it.accountId }

        return accounts.map { account ->
            valueOf(account, period, policies[account.categoryId], byAccount[account.id].orEmpty())
        }
    }

    /** Итоги месяца по каждой валюте; валюта без учтённых значений в результат не входит. */
    fun monthTotals(
        period: Period,
        accounts: List<AssetAccount>,
        categories: List<Category>,
        snapshots: List<AssetSnapshot>,
    ): List<AssetMonthTotal> {
        val values = resolve(period, accounts, categories, snapshots)
        val counted = values.filter { it.isCounted }
        return counted
            .groupBy { requireNotNull(it.balance).currency }
            .map { (currency, list) ->
                val zero = Money(0L, currency)
                // Взносы берём только из фактических снапшотов: у расчётных значений
                // взноса нет, иначе прирост месяца оказался бы занижен.
                val contributions = snapshots
                    .filter { it.deletedAtUtc == null && it.period == period }
                    .filter { snapshot -> list.any { it.accountId == snapshot.accountId } }
                    .fold(zero) { acc, snapshot -> snapshot.contribution?.let { acc + it } ?: acc }

                AssetMonthTotal(
                    period = period,
                    currency = currency,
                    netWorth = list.fold(zero) { acc, value -> acc + requireNotNull(value.balance) },
                    projected = list
                        .filter { it.source == BalanceSource.PROJECTED }
                        .fold(zero) { acc, value -> acc + requireNotNull(value.balance) },
                    contributions = contributions,
                    valueCount = list.size,
                )
            }
    }

    /**
     * Начисление процента за месяц по годовой ставке с капитализацией (D-21).
     *
     * Ставка годовая, начисление ежемесячное: `X → X + X * (rate / 12)`, следующий месяц
     * считается от новой суммы, то есть процент капитализируется. Округление до копеек
     * на каждом месяце — как в банковской выписке.
     */
    fun monthlyInterest(balance: Money, annualRateBp: Int): Money {
        if (annualRateBp == 0) return Money(0L, balance.currency)
        val monthlyRate = BigDecimal.valueOf(annualRateBp.toLong())
            .movePointLeft(BASIS_POINTS_SCALE)
            .divide(MONTHS_PER_YEAR, RATE_SCALE, RoundingMode.HALF_UP)
        val interest = BigDecimal.valueOf(balance.minor)
            .multiply(monthlyRate)
            .setScale(0, RoundingMode.HALF_UP)
        return Money(interest.longValueExact(), balance.currency)
    }

    private fun valueOf(
        account: AssetAccount,
        period: Period,
        policy: AssetBalancePolicy?,
        snapshots: List<AssetSnapshot>,
    ): AccountBalance {
        val missing = AccountBalance(
            accountId = account.id,
            period = period,
            balance = null,
            source = BalanceSource.MISSING,
        )
        if (!account.isActiveAt(period)) return missing

        val recorded = snapshots.firstOrNull { it.period == period }
        if (recorded != null) {
            return AccountBalance(
                accountId = account.id,
                period = period,
                balance = recorded.balance,
                source = BalanceSource.RECORDED,
                basedOnPeriod = period,
            )
        }

        // Последний введённый остаток до этого месяца — база прогноза и переноса.
        val base = snapshots.filter { it.period < period }.maxByOrNull { it.period } ?: return missing
        val effectivePolicy = policy ?: AssetBalancePolicy.DEFAULT

        return when {
            effectivePolicy.isProjected -> project(account, period, base)
            else -> AccountBalance(
                accountId = account.id,
                period = period,
                balance = base.balance,
                source = if (effectivePolicy.needsConfirmation) {
                    BalanceSource.CARRIED_DRAFT
                } else {
                    BalanceSource.CARRIED
                },
                basedOnPeriod = base.period,
            )
        }
    }

    /**
     * Прогноз вклада: капитализация от последнего введённого остатка до [period].
     *
     * Срок не указан — вклад считается бессрочным. После последнего месяца срока ставка
     * больше не начисляется, но деньги остаются активом: сумма переносится как есть, а
     * [AccountBalance.isMatured] подсказывает интерфейсу предложить закрыть счёт (D-21).
     */
    private fun project(
        account: AssetAccount,
        period: Period,
        base: AssetSnapshot,
    ): AccountBalance {
        val rateBp = account.annualRateBp
        if (rateBp == null) {
            // Ставки нет — начислять нечего, переносим сумму как обычный актив. Черновиком
            // делать нельзя: вклад обещан «без ввода» (F4.6), а напоминание «обновите
            // стоимость» на вкладе означало бы «добавьте ставку» и только путало бы.
            return AccountBalance(
                accountId = account.id,
                period = period,
                balance = base.balance,
                source = BalanceSource.CARRIED,
                basedOnPeriod = base.period,
            )
        }

        val maturity = account.maturityPeriod
        val isMatured = maturity != null && period > maturity
        val lastAccrual = if (isMatured) requireNotNull(maturity) else period

        var balance = base.balance
        var interest = Money(0L, balance.currency)
        repeat(base.period.monthsUntil(lastAccrual)) {
            val accrued = monthlyInterest(balance, rateBp)
            balance += accrued
            interest = accrued
        }
        return AccountBalance(
            accountId = account.id,
            period = period,
            balance = balance,
            // После окончания срока это уже не прогноз, а перенесённая сумма вклада.
            source = if (isMatured) BalanceSource.CARRIED else BalanceSource.PROJECTED,
            basedOnPeriod = base.period,
            accruedInterest = if (isMatured) null else interest,
            isMatured = isMatured,
        )
    }

    private const val BASIS_POINTS_SCALE = 4
    private const val RATE_SCALE = 10
    private val MONTHS_PER_YEAR: BigDecimal = BigDecimal.valueOf(12)
}
