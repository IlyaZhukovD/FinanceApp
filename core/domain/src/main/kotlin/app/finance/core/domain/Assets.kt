package app.finance.core.domain

import app.finance.core.model.AssetAccount
import app.finance.core.model.AssetBalances
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
 * Отрицательный баланс допустим, только если право на минус даёт категория счёта
 * (`Category.allowsNegativeBalance`, D-20): иначе минус означал бы либо ошибку ввода,
 * либо долг, который учитывается отдельным счётом в категории с таким признаком
 * (F4.1 — Net Worth включает отрицательные балансы). Собственного «типа счёта» больше
 * нет (D-22), поэтому исключений по типу не осталось.
 */
class RecordSnapshot(
    private val assets: AssetRepository,
    private val categories: CategoryRepository,
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
                "Счёт «${account.name}» не активен в ${input.period}: открыт с " +
                    "${account.openedPeriod}$closed"
            }
            require(input.balance.currency == account.currency) {
                "Баланс указан в ${input.balance.currency}, а счёт «${account.name}» " +
                    "ведётся в ${account.currency}"
            }
            if (input.balance.isNegative()) {
                // Право на минус задаёт категория счёта (D-20, D-22): у самого счёта
                // признака нет, а кредит — это категория с разрешённым минусом.
                val categoryAllows = categories.byId(account.categoryId)?.allowsNegativeBalance == true
                require(categoryAllows) {
                    "Отрицательный баланс допустим только в категории с правом на минус, " +
                        "а «${account.name}» к такой не относится"
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
    val currency: String = Money.DEFAULT_CURRENCY,
    val openedPeriod: Period,
    /**
     * Годовая ставка в базисных пунктах: 850 == 8.50%. Задаётся только для счетов
     * категории с известным процентом (`AssetBalancePolicy.FIXED_RATE`, F4.4, D-20).
     */
    val annualRateBp: Int? = null,
    /**
     * Срок вклада в месяцах от [openedPeriod]; null — бессрочно (F4.6, D-21).
     * Задаётся только вместе со ставкой.
     */
    val termMonths: Int? = null,
    /**
     * Сумма, положенная на счёт при открытии: создаёт первый снапшот за [openedPeriod]
     * в той же транзакции (D-21). Для вклада обязательна — без базы процент считать не с чего.
     */
    val initialBalance: Money? = null,
    val note: String? = null,
)

/**
 * Создание счёта.
 *
 * Категория обязана относиться к секции [CategorySection.ASSET]: наборы категорий
 * расходов, доходов и активов независимы (SPEC.md §1).
 *
 * Вклад создаётся одной операцией «счёт + сумма открытия» (D-21): ставка, срок и
 * внесённая сумма задаются вместе, и расписание прогноза (F4.6) получает базу сразу.
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

            val isDeposit = category.balancePolicy.isProjected
            if (input.annualRateBp != null) {
                require(input.annualRateBp >= 0) {
                    "Ставка не может быть отрицательной: ${input.annualRateBp}"
                }
                // Прогноз по ставке определён только для категорий с известной доходностью
                // (F4.4, D-20): у акций, паев и валюты процент заранее неизвестен, поэтому
                // их стоимость подтверждается вручную, а не считается расписанием.
                require(isDeposit) {
                    "Годовая ставка задаётся только для категорий с известным процентом, " +
                        "а «${category.name}» оценивается иначе"
                }
            }
            if (input.termMonths != null) {
                require(isDeposit) {
                    "Срок задаётся только для категорий с известным процентом, " +
                        "а «${category.name}» оценивается иначе"
                }
            }
            // Ставка и сумма открытия вклада ожидаются интерфейсом, но домен их не требует:
            // счёт может появиться из другого пути, а без ставки или без базы прогноз просто
            // не считается — значение месяца остаётся черновиком и ждёт ввода (D-21).
            input.initialBalance?.let { balance ->
                require(balance.currency == input.currency) {
                    "Сумма указана в ${balance.currency}, а счёт «$name» ведётся в ${input.currency}"
                }
                if (balance.isNegative()) {
                    // То же правило, что при вводе остатка (F4.3, D-20): минус разрешает
                    // только категория — «типа счёта» больше нет (D-22).
                    require(category.allowsNegativeBalance) {
                        "Отрицательная сумма при открытии допустима только в категории " +
                            "с правом на минус"
                    }
                }
            }

            val now = time.nowUtcMillis()
            val account = AssetAccount(
                id = ids.newId(),
                categoryId = category.id,
                name = name,
                currency = input.currency,
                openedPeriod = input.openedPeriod,
                annualRateBp = input.annualRateBp,
                termMonths = input.termMonths,
                note = input.note?.trim()?.takeIf { it.isNotEmpty() },
                createdAtUtc = now,
                updatedAtUtc = now,
                rowVersion = 1,
            )
            assets.insertAccount(account)

            input.initialBalance?.let { balance ->
                // Снапшот открытия пишем напрямую: счёт только что создан, проверять
                // активность и прежний ввод нечего, а транзакция уже открыта.
                assets.insertSnapshot(
                    AssetSnapshot(
                        id = ids.newId(),
                        accountId = account.id,
                        period = account.openedPeriod,
                        balance = balance,
                        createdAtUtc = now,
                        updatedAtUtc = now,
                        rowVersion = 1,
                    ),
                )
            }
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
    /** Сумма учтённых значений месяца, включая отрицательные балансы кредитов (F4.1). */
    val netWorth: Money,
    /**
     * Часть [netWorth], посчитанная расписанием вкладов, а не введённая пользователем
     * (D-21). Шапка показывает её отдельно, чтобы прогноз не выглядел фактом.
     */
    val projected: Money,
    /** null, если в прошлом месяце не было ни одного учтённого значения этой валюты. */
    val previousNetWorth: Money?,
    val delta: Money?,
    /** Сумма взносов и снятий месяца; снятия уменьшают её. */
    val contributions: Money,
    /**
     * Инвестиционный прирост: `Δ − взносы` (F4.1).
     *
     * В SPEC формула записана по счёту — `balance[M] − balance[M−1] − contribution[M]`;
     * в сумме по всем счетам месяца она даёт ровно `Δ − взносы`, поэтому считаем
     * агрегированно и не теряем счета, у которых нет значения за прошлый месяц.
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
 * Значения месяца берутся у единого механизма [AssetBalances] (D-20, D-21): факт,
 * прогноз вклада по ставке, перенос остатка актива без роста и черновик ручной оценки.
 * В итоги входят только учтённые значения — черновики не считаются, пока пользователь
 * не подтвердил стоимость.
 *
 * Счёт, закрытый на месяц значения, в итогах не участвует (F4.5, D-19): его последний
 * сохранённый остаток — история, а не актив месяца. Прошлый месяц считается по тем же
 * правилам, иначе Δ и «прирост» сравнивали бы активы с закрытым счётом.
 *
 * Тот же [AssetBalances] использует серия капитала в аналитике: числа обязаны совпадать.
 *
 * Только чтение, поэтому транзакция не нужна.
 */
class AssetSummary(
    private val assets: AssetRepository,
    private val categories: CategoryRepository,
) {

    suspend operator fun invoke(period: Period): AssetTotals {
        val accounts = assets.accounts(includeClosed = true)
        val assetCategories = categories.bySection(CategorySection.ASSET, includeArchived = true)
        // Одной выборкой закрываем оба месяца: значение считается от последнего введённого
        // остатка, поэтому нужны снапшоты и до выбранного месяца.
        val snapshots = assets.snapshotsUpTo(period)
        val current = AssetBalances.monthTotals(period, accounts, assetCategories, snapshots)
        if (current.isEmpty()) return AssetTotals(period = period, totals = emptyList())

        val previous = AssetBalances.monthTotals(period.minusMonths(1), accounts, assetCategories, snapshots)

        val totals = current.map { month ->
            val before = previous.firstOrNull { it.currency == month.currency }
            val delta = before?.let { month.netWorth - it.netWorth }
            CurrencyTotal(
                currency = month.currency,
                netWorth = month.netWorth,
                projected = month.projected,
                previousNetWorth = before?.netWorth,
                delta = delta,
                contributions = month.contributions,
                gain = delta?.let { it - month.contributions },
                accountCount = month.valueCount,
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
