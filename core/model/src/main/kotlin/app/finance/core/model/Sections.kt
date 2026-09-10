package app.finance.core.model

/**
 * Раздел учёта потоков: расходы и доходы хранятся в одной таблице `category_month`
 * и различаются этим полем (SPEC.md §6). Активы — отдельный мир остатков, поэтому
 * здесь их нет.
 */
enum class LedgerSection {
    EXPENSE,
    INCOME,
}

/**
 * Секция категории. Категории скоупнуты по секции: у расходов, доходов и активов
 * свои независимые наборы (SPEC.md §1, требование продукта).
 */
enum class CategorySection {
    EXPENSE,
    INCOME,
    ASSET,
    ;

    /** true для секций, которые участвуют в потоковом учёте (расходы и доходы). */
    val isLedger: Boolean get() = this != ASSET

    fun toLedgerSection(): LedgerSection = when (this) {
        EXPENSE -> LedgerSection.EXPENSE
        INCOME -> LedgerSection.INCOME
        ASSET -> error("Секция ASSET не является потоковой: у активов свои сущности")
    }

    companion object {
        fun of(section: LedgerSection): CategorySection = when (section) {
            LedgerSection.EXPENSE -> EXPENSE
            LedgerSection.INCOME -> INCOME
        }
    }
}

/** Тип счёта активов. CREDIT допускает отрицательный баланс (SPEC.md F4.1). */
enum class AssetKind {
    DEPOSIT,
    BROKERAGE,
    FUND,
    IIS,
    CASH,
    CREDIT,
    REAL_ESTATE,
    OTHER,
    ;

    /** Для таких счетов баланс может быть отрицательным. */
    val allowsNegativeBalance: Boolean get() = this == CREDIT
}
