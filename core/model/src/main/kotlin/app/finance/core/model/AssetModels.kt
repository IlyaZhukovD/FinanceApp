package app.finance.core.model

/**
 * Счёт активов: вклад, брокерский счёт, ИИС/паи, наличные, кредит.
 *
 * Активы — это остатки (stock), а не потоки: сумма балансов за два месяца
 * НЕ является расходом, поэтому активы живут отдельно от `category_month`
 * и никогда не складываются с потоками в одной серии графиков (SPEC.md F6.8).
 */
data class AssetAccount(
    val id: String,
    val categoryId: String,
    val name: String,
    val kind: AssetKind,
    val currency: String = Money.DEFAULT_CURRENCY,
    val openedPeriod: Period,
    val closedPeriod: Period? = null,
    /** Годовая ставка в базисных пунктах: 850 == 8.50%. Только для вкладов. */
    val annualRateBp: Int? = null,
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
        if (closedPeriod != null) {
            require(closedPeriod >= openedPeriod) {
                "Период закрытия $closedPeriod раньше периода открытия $openedPeriod"
            }
        }
    }

    val isOpen: Boolean get() = closedPeriod == null && deletedAtUtc == null

    /** Счёт активен в указанном периоде: открыт и ещё не закрыт. */
    fun isActiveAt(period: Period): Boolean =
        deletedAtUtc == null && period >= openedPeriod && (closedPeriod == null || period <= closedPeriod)
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
