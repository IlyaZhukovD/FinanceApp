package app.finance.core.model

/**
 * Счёт активов: вклад, брокерский счёт, ИИС/паи, наличные, кредит.
 *
 * Природу счёта задаёт его категория (D-22): отдельного «типа» у счёта нет, правило
 * баланса и право на отрицательный остаток читаются из [Category].
 *
 * Активы — это остатки (stock), а не потоки: сумма балансов за два месяца
 * НЕ является расходом, поэтому активы живут отдельно от `category_month`
 * и никогда не складываются с потоками в одной серии графиков (SPEC.md F6.8).
 */
data class AssetAccount(
    val id: String,
    val categoryId: String,
    val name: String,
    val currency: String = Money.DEFAULT_CURRENCY,
    val openedPeriod: Period,
    val closedPeriod: Period? = null,
    /** Годовая ставка в базисных пунктах: 850 == 8.50%. Только для вкладов. */
    val annualRateBp: Int? = null,
    /**
     * Срок вклада в месяцах от [openedPeriod]; null — бессрочно (F4.6, D-21).
     *
     * Срок задаёт последний месяц начисления процента: вклад, открытый в январе на
     * 12 месяцев, начисляет процент с февраля по январь следующего года включительно.
     * Дальше ставка не начисляется, а интерфейс предлагает закрыть счёт.
     */
    val termMonths: Int? = null,
    /**
     * День месяца, в который банк начисляет и капитализирует процент (1..31); null — день
     * не указан, и прогноз считается упрощённо: `ставка / 12` за месяц (D-31).
     *
     * С указанным днём процент за месяц считается по фактическим дням между двумя датами
     * начисления: `остаток × ставка × дни / дней в году` (ACT/365, в високосный год — 366).
     * В месяцах короче этого дня начисление переносится на последний день месяца.
     */
    val interestDay: Int? = null,
    /**
     * Когда проценты прибавляются к вкладу (D-32): каждый месяц ([DepositCapitalization.MONTHLY])
     * или один раз в конце срока ([DepositCapitalization.AT_MATURITY]) — тогда это простые
     * проценты на сумму вклада, и до конца срока они в остаток не входят.
     */
    val capitalization: DepositCapitalization = DepositCapitalization.MONTHLY,
    /**
     * Напоминать обновить остаток в конце месяца (D-34): пока за месяц не введён остаток,
     * экран активов показывает счёт в плашке «Обновите остаток». На расчёт значения
     * месяца флаг не влияет — его по-прежнему задаёт правило категории.
     */
    val monthlyReminder: Boolean = false,
    val note: String? = null,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
    val rowVersion: Int = 1,
    val deletedAtUtc: Long? = null,
) {
    init {
        require(name.isNotBlank()) { "Название счёта не может быть пустым" }
        require(annualRateBp == null || annualRateBp >= 0) {
            "Ставка не может быть отрицательной: $annualRateBp"
        }
        require(termMonths == null || termMonths > 0) {
            "Срок вклада задаётся числом месяцев больше нуля: $termMonths"
        }
        require(capitalization == DepositCapitalization.MONTHLY || termMonths != null) {
            "Выплата процентов в конце срока требует срока вклада"
        }
        require(interestDay == null || interestDay in 1..MAX_INTEREST_DAY) {
            "День начисления процентов — число от 1 до $MAX_INTEREST_DAY: $interestDay"
        }
        if (closedPeriod != null) {
            require(closedPeriod >= openedPeriod) {
                "Период закрытия $closedPeriod раньше периода открытия $openedPeriod"
            }
        }
    }

    val isOpen: Boolean get() = closedPeriod == null && deletedAtUtc == null

    /**
     * Последний месяц, за который вкладу начисляется процент; null — вклад бессрочный.
     *
     * Открытый в январе вклад на 12 месяцев даёт [openedPeriod] + 12 = январь следующего
     * года: процент начислен за 12 месяцев (февраль — январь). Следующий месяц после
     * [maturityPeriod] — «срок истёк».
     */
    val maturityPeriod: Period? get() = termMonths?.let { openedPeriod.plusMonths(it) }

    /**
     * Счёт активен в указанном периоде: уже открыт и ещё не закрыт.
     *
     * [closedPeriod] читается как «закрыт С этого месяца» (SPEC.md F4.5, D-19), поэтому
     * сам месяц закрытия в активные не входит: остаток закрытого счёта больше не
     * принадлежит пользователю на конец месяца и не участвует ни в Net Worth,
     * ни в дублировании месяца (F5.3), ни во вводе нового остатка (F4.3).
     */
    fun isActiveAt(period: Period): Boolean =
        deletedAtUtc == null && period >= openedPeriod && (closedPeriod == null || period < closedPeriod)

    companion object {
        const val MAX_INTEREST_DAY = 31
    }
}

/**
 * Когда банк прибавляет проценты к вкладу (SPEC.md F4.6, D-32).
 *
 * Хранится в `asset_account.capitalization` именем константы.
 */
enum class DepositCapitalization {
    /** Каждый месяц: следующий месяц считается от суммы с процентами (сложный процент). */
    MONTHLY,

    /**
     * В конце срока: процент простой, начисляется на сумму вклада и до окончания срока
     * в остаток не входит — банк показывает его отдельно, «под замком».
     */
    AT_MATURITY,
    ;

    companion object {
        /** Неизвестное значение (например, от более новой версии) читается как [MONTHLY]. */
        fun fromDb(value: String?): DepositCapitalization =
            entries.firstOrNull { it.name == value } ?: MONTHLY
    }
}

/**
 * Снапшоты только тех счетов, которые активны в месяце своего снапшота (F4.5, D-19).
 *
 * Закрытый счёт с месяца закрытия не участвует в Net Worth и сериях капитала, хотя
 * история его снапшотов сохраняется и доступна в истории счёта (F4.4). Снапшот
 * неизвестного счёта остаётся в выборке: молча терять данные нельзя.
 */
fun List<AssetSnapshot>.ofActiveAccounts(accounts: List<AssetAccount>): List<AssetSnapshot> {
    val byId = accounts.associateBy { it.id }
    return filter { snapshot -> byId[snapshot.accountId]?.isActiveAt(snapshot.period) ?: true }
}

/**
 * Снапшот остатка счёта на месяц. Один на пару (счёт, период) — UNIQUE в БД,
 * поэтому повторный ввод за тот же месяц означает редактирование.
 *
 * [contribution] — взносы (+) и снятия (−) за месяц. Нужен, чтобы разложить
 * изменение баланса на « довнёс сам» и «рыночный прирост» (SPEC.md F4.1):
 * `прирост = balance[M] − balance[M−1] − contribution[M]`.
 */
data class AssetSnapshot(
    val id: String,
    val accountId: String,
    val period: Period,
    val balance: Money,
    val contribution: Money? = null,
    val note: String? = null,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
    val rowVersion: Int = 1,
    val deletedAtUtc: Long? = null,
) {
    init {
        if (contribution != null) {
            require(balance.currency == contribution.currency) {
                "Валюта баланса и взноса должны совпадать"
            }
        }
    }
}
