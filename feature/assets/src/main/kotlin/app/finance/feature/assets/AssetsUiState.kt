package app.finance.feature.assets

import app.finance.core.model.AssetKind
import app.finance.core.model.Category
import app.finance.core.model.Money
import app.finance.core.model.Period

/**
 * Состояние экрана «Активы» (SPEC.md F4).
 *
 * Активы — остатки (stock), а не потоки, поэтому экран не переиспользует ledger-движок
 * (DECISIONS D-12): единица учёта здесь снапшот счёта за месяц, а в шапке — Net Worth.
 * Деньги остаются [Money], форматирует их экран компонентами `core:ui`.
 */
data class AssetsUiState(
    val period: Period,
    val isLoading: Boolean = true,
    val header: NetWorthHeader = NetWorthHeader.EMPTY,
    val groups: List<AccountGroup> = emptyList(),
    /** Категории активов — для диалога создания счёта (F4.2). */
    val assetCategories: List<Category> = emptyList(),
    /** Форма ввода остатка открыта для конкретного счёта (F4.3). */
    val snapshotForm: SnapshotFormState? = null,
    val accountDialog: AccountDialogState? = null,
    val confirm: AssetsConfirm? = null,
    /** Короткое сообщение об успехе операции. */
    val notice: String? = null,
    /** Сообщение об ошибке операции. */
    val error: String? = null,
) {
    val hasAccounts: Boolean get() = groups.any { it.accounts.isNotEmpty() }
}

/**
 * Шапка раздела: Net Worth и разложение изменения на взносы и прирост (F4.1).
 *
 * Балансы кредитов отрицательны и уменьшают Net Worth. Валюты не смешиваются:
 * итоги основной валюты — в [netWorth], остальные — в [otherCurrencies], поскольку
 * курсов конвертации в v1 нет (SPEC.md §5).
 */
data class NetWorthHeader(
    val netWorth: Money,
    /** Итог прошлого месяца по той же валюте; null, если сравнивать не с чем. */
    val previousNetWorth: Money? = null,
    /** null, если в прошлом месяце не было снапшотов основной валюты. */
    val delta: Money?,
    val deltaPercent: Int?,
    val contributions: Money,
    /** Инвестиционный прирост = Δ − взносы (F4.1). */
    val gain: Money?,
    val accountCount: Int,
    /** Число счетов с введённым остатком за месяц. */
    val snapshotCount: Int,
    val otherCurrencies: List<Money> = emptyList(),
) {
    val hasData: Boolean get() = snapshotCount > 0

    val hasMultipleCurrencies: Boolean get() = otherCurrencies.isNotEmpty()

    companion object {
        val EMPTY: NetWorthHeader = NetWorthHeader(
            netWorth = Money.ZERO,
            delta = null,
            deltaPercent = null,
            contributions = Money.ZERO,
            gain = null,
            accountCount = 0,
            snapshotCount = 0,
        )
    }
}

/** Группа счетов по категории активов (F4.1). */
data class AccountGroup(
    val categoryId: String,
    val categoryName: String,
    val categoryColor: String,
    val accounts: List<AccountRow>,
) {
    val total: Money?
        get() {
            val balances = accounts.mapNotNull { it.balance }
            if (balances.isEmpty()) return null
            val currency = balances.first().currency
            // Складываются только совпадающие валюты: группа может быть смешанной.
            return balances.filter { it.currency == currency }
                .fold(Money(0L, currency)) { acc, money -> acc + money }
        }
}

/** Строка счёта в выбранном месяце. */
data class AccountRow(
    val accountId: String,
    val name: String,
    val kindLabel: String,
    val currency: String,
    /** null — остаток за этот месяц не введён. */
    val balance: Money?,
    val previousBalance: Money?,
    val delta: Money?,
    val contribution: Money?,
    val isClosed: Boolean,
    val snapshotId: String?,
) {
    val hasSnapshot: Boolean get() = balance != null
}

/** Форма ввода остатка: повторный ввод за месяц правит существующий снапшот (F4.3). */
data class SnapshotFormState(
    val accountId: String,
    val accountName: String,
    val currency: String,
    val period: Period,
    val balanceText: String = "",
    /** Взносы (+) и снятия (−) за месяц; нужны для разложения Δ (F4.1). */
    val contributionText: String = "",
    val noteText: String = "",
    val error: String? = null,
    val isSaving: Boolean = false,
    /** true, если снапшот за месяц уже есть и ввод его заменит. */
    val isEdit: Boolean = false,
)

/** Диалог создания счёта (F4.2). */
data class AccountDialogState(
    val nameText: String = "",
    val categoryId: String? = null,
    val kind: AssetKind = AssetKind.DEPOSIT,
    val currencyText: String = Money.DEFAULT_CURRENCY,
    /** Годовая ставка в процентах; учитывается только для вклада (F4.4). */
    val rateText: String = "",
    val noteText: String = "",
    val error: String? = null,
    val isSaving: Boolean = false,
)

/**
 * Запрос подтверждения закрытия счёта (F4.5).
 *
 * Отдельный тип, а не общий с ledger: модули разделов не должны зависеть друг от друга.
 */
data class AssetsConfirm(
    val accountId: String,
    val title: String,
    val message: String,
    val confirmLabel: String,
)
