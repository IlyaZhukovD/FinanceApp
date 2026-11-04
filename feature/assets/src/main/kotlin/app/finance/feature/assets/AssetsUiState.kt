package app.finance.feature.assets

import app.finance.core.model.BalanceSource
import app.finance.core.model.Category
import app.finance.core.model.Money
import app.finance.core.model.Period

/**
 * Состояние экрана «Активы» (SPEC.md F4).
 *
 * Активы — остатки (stock), а не потоки, поэтому экран не переиспользует ledger-движок
 * (DECISIONS D-12): единица учёта здесь значение счёта за месяц, а в шапке — Net Worth.
 * Значение месяца не всегда введено пользователем: вклад считается расписанием, а
 * стоимость актива с ручной оценкой переносится черновиком (F4.6, D-21), поэтому строка
 * хранит источник и пометку.
 *
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
    /** Напоминание обновить стоимость активов с ручной оценкой (F4.6, D-21). */
    val reminder: AssetReminder? = null,
    /** Массовое подтверждение стоимости: все счета месяца одним окном. */
    val confirmBalances: ConfirmBalancesState? = null,
    /** Короткое сообщение об успехе операции. */
    val notice: String? = null,
    /** Сообщение об ошибке операции. */
    val error: String? = null,
) {
    val hasAccounts: Boolean get() = groups.any { it.accounts.isNotEmpty() }
}

/**
 * Режим ввода значения при подтверждении перенесённой стоимости (F4.6, D-21).
 *
 * Дельта — это рыночное изменение стоимости: взносы и снятия остаются отдельным полем
 * снапшота, иначе прирост месяца потерял бы смысл (`Δ − взносы`).
 */
enum class BalanceInputMode(val titleRu: String) {
    /** Рыночное изменение: перенесённая сумма плюс введённая дельта. */
    DELTA("Изменение"),

    /** Полное значение: введённая сумма заменяет перенесённую. */
    ABSOLUTE("Сумма целиком"),
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
    /** null, если в прошлом месяце не было учтённых значений основной валюты. */
    val delta: Money?,
    val deltaPercent: Int?,
    val contributions: Money,
    /** Инвестиционный прирост = Δ − взносы (F4.1). */
    val gain: Money?,
    val accountCount: Int,
    /** Число счетов со значением за месяц: введённым, прогнозным или перенесённым. */
    val snapshotCount: Int,
    /**
     * Часть [netWorth], посчитанная расписанием вкладов, а не введённая пользователем
     * (D-21). Шапка показывает её с пометкой «прогноз», чтобы расчёт не выглядел фактом.
     */
    val projected: Money? = null,
    val otherCurrencies: List<Money> = emptyList(),
) {
    val hasData: Boolean get() = snapshotCount > 0

    val hasMultipleCurrencies: Boolean get() = otherCurrencies.isNotEmpty()

    val hasProjection: Boolean get() = projected?.isPositive() == true

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
    /**
     * Итог группы по учтённым значениям: черновики и пустые строки не входят, иначе
     * сумма группы разошлась бы с Net Worth в шапке (D-21).
     */
    val total: Money?
        get() {
            val balances = accounts.filter { it.isCounted }.mapNotNull { it.balance }
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
    val currency: String,
    /**
     * Значение месяца: факт, прогноз вклада или перенос; null — данных нет либо счёт
     * закрыт и в месяце не учитывается (F4.5, D-19).
     */
    val balance: Money?,
    /** Откуда взялось [balance] — определяет пометку и участие в итогах (D-21). */
    val source: BalanceSource,
    /** Короткая пометка рядом с суммой: «прогноз», «черновик», «срок истёк»… */
    val badge: String?,
    /** Процент, начисленный расписанием за этот месяц (только для прогноза вклада). */
    val accruedInterest: Money?,
    /** Месяц последнего введённого остатка — база прогноза или переноса. */
    val basedOnPeriod: Period?,
    val previousBalance: Money?,
    val delta: Money?,
    val contribution: Money?,
    val isClosed: Boolean,
    /**
     * Счёт активен в выбранном месяце. С месяца закрытия — false: значение не входит
     * в Net Worth и не редактируется (SPEC.md F4.5, D-19).
     */
    val isActiveInPeriod: Boolean,
    /** Срок вклада истёк: ставка больше не начисляется, предлагаем закрыть счёт (D-21). */
    val isMatured: Boolean,
    val snapshotId: String?,
) {
    /** Остаток за месяц ввёл пользователь — такую строку можно править как снапшот. */
    val hasSnapshot: Boolean get() = source == BalanceSource.RECORDED

    /** Значение входит в итоги месяца. */
    val isCounted: Boolean get() = balance != null && source.isCounted

    /** Стоимость ждёт подтверждения пользователем (F4.6). */
    val isDraft: Boolean get() = source.isDraft
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
    /**
     * Перенесённое значение черновика; null, если остаток за месяц введён или данных нет.
     * С ним форма предлагает ввести дельту или сумму целиком (F4.6, D-21).
     */
    val carriedBalance: Money? = null,
    val mode: BalanceInputMode = BalanceInputMode.DELTA,
) {
    /** Форма открыта для подтверждения перенесённой стоимости. */
    val isDraftConfirm: Boolean get() = carriedBalance != null && !isEdit
}

/** Диалог создания счёта (F4.2). Природу счёта задаёт категория — типа счёта нет (D-22). */
data class AccountDialogState(
    val nameText: String = "",
    val categoryId: String? = null,
    val currencyText: String = Money.DEFAULT_CURRENCY,
    /** Годовая ставка в процентах; нужна категориям с известным процентом (F4.4, D-20). */
    val rateText: String = "",
    /** Срок вклада в месяцах; пусто — бессрочно (F4.6, D-21). */
    val termText: String = "",
    /** День начисления процентов (1..31); пусто — упрощённый расчёт `ставка / 12` (D-31). */
    val interestDayText: String = "",
    /** Сумма, положенная на счёт при открытии: создаёт первый остаток месяца (D-21). */
    val initialBalanceText: String = "",
    val noteText: String = "",
    val error: String? = null,
    val isSaving: Boolean = false,
)

/**
 * Напоминание обновить стоимость активов (F4.6, D-21).
 *
 * Системных уведомлений в v1 нет: напоминание живёт на экране активов и появляется,
 * когда в выбранном месяце есть не подтверждённые пользователем значения.
 */
data class AssetReminder(
    val period: Period,
    val title: String,
    val detail: String,
    val accounts: List<ReminderAccount>,
) {
    val accountCount: Int get() = accounts.size
}

/** Счёт, чья стоимость за месяц перенесена и ждёт подтверждения. */
data class ReminderAccount(
    val accountId: String,
    val name: String,
    val currency: String,
    /** Перенесённое значение — отправная точка для дельты. */
    val carriedBalance: Money,
    /** Месяц, из которого значение перенесено. */
    val basedOnPeriod: Period,
)

/** Массовое подтверждение стоимости счетов за месяц (F4.6, D-21). */
data class ConfirmBalancesState(
    val period: Period,
    val items: List<ConfirmBalanceItem>,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val count: Int get() = items.size
}

/** Строка массового подтверждения: счёт, перенесённое значение и ввод пользователя. */
data class ConfirmBalanceItem(
    val accountId: String,
    val accountName: String,
    val currency: String,
    val carriedBalance: Money,
    val mode: BalanceInputMode = BalanceInputMode.DELTA,
    /** Пустой ввод означает «подтвердить перенесённое значение как есть». */
    val amountText: String = "",
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
