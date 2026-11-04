package app.finance.feature.assets

import app.finance.core.domain.AccountInput
import app.finance.core.domain.AssetRepository
import app.finance.core.domain.AssetSummary
import app.finance.core.domain.AssetTotals
import app.finance.core.domain.CategoryRepository
import app.finance.core.domain.CloseAccount
import app.finance.core.domain.CreateAccount
import app.finance.core.domain.RecordSnapshot
import app.finance.core.domain.SnapshotInput
import app.finance.core.domain.SnapshotResult
import app.finance.core.model.AssetAccount
import app.finance.core.model.AssetBalances
import app.finance.core.model.AssetSnapshot
import app.finance.core.model.AccountBalance
import app.finance.core.model.BalanceSource
import app.finance.core.model.Category
import app.finance.core.model.CategorySection
import app.finance.core.model.Money
import app.finance.core.model.Period
import app.finance.core.ui.badgeRu
import app.finance.core.ui.deltaPercent
import app.finance.core.ui.formatRu
import app.finance.core.ui.shortRu
import app.finance.core.ui.titleRu
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Контроллер раздела «Активы» (SPEC.md F4).
 *
 * Отдельный от `LedgerController` не из-за копипасты, а потому что другая единица учёта:
 * снапшот счёта за месяц с `UNIQUE(account, period)`, где повторный ввод означает
 * редактирование, а не аккумулирование (D-02 против F4.3).
 *
 * Как и в ledger, контроллер принимает [scope] и [ioDispatcher] извне: это делает его
 * тестируемым, а в приложении они приходят из `AppGraph`.
 */
class AssetsController(
    private val assets: AssetRepository,
    private val categories: CategoryRepository,
    private val recordSnapshot: RecordSnapshot,
    private val createAccount: CreateAccount,
    private val closeAccount: CloseAccount,
    private val assetSummary: AssetSummary,
    private val initialPeriod: Period,
    private val clock: Clock,
    private val zone: ZoneId,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val _state = MutableStateFlow(AssetsUiState(period = initialPeriod))

    val state: StateFlow<AssetsUiState> = _state.asStateFlow()

    /** Первичная загрузка. Вызывается из `LaunchedEffect` при входе экрана в композицию. */
    fun start() = reloadMonth()

    // ── Период (F1.7) ────────────────────────────────────────────────────────

    fun previousMonth() = moveTo(_state.value.period.minusMonths(1))

    fun nextMonth() = moveTo(_state.value.period.plusMonths(1))

    fun today() = moveTo(Period.now(clock, zone))

    fun moveTo(period: Period) {
        if (period == _state.value.period) return
        // Формы относятся к прежнему месяцу — закрываем их вместе со сменой периода.
        _state.update {
            it.copy(
                period = period,
                snapshotForm = null,
                accountDialog = null,
                confirm = null,
                confirmBalances = null,
                notice = null,
                error = null,
            )
        }
        reloadMonth()
    }

    // ── Ввод остатка (F4.3) ──────────────────────────────────────────────────

    fun openSnapshotForm(accountId: String) {
        val period = _state.value.period
        val row = _state.value.rows().firstOrNull { it.accountId == accountId } ?: return
        if (!row.isActiveInPeriod) {
            // Счёт закрыт в этом месяце (F4.5, D-19): его остаток не участвует в Net Worth,
            // поэтому вводить нечего — показываем причину вместо формы.
            _state.update {
                it.copy(
                    snapshotForm = null,
                    notice = "«${row.name}» закрыт: остаток за ${period.shortRu()} не учитывается",
                    error = null,
                )
            }
            return
        }
        scope.launch {
            runCatching { io { assets.findSnapshot(accountId, period) } }
                .onFailure { error -> _state.update { failed(it, error) } }
                .onSuccess { snapshot ->
                    _state.update { state ->
                        state.copy(
                            snapshotForm = SnapshotFormState(
                                accountId = accountId,
                                accountName = row.name,
                                currency = row.currency,
                                period = period,
                                balanceText = snapshot?.balance?.toPlainString().orEmpty(),
                                contributionText = snapshot?.contribution?.toPlainString().orEmpty(),
                                noteText = snapshot?.note.orEmpty(),
                                isEdit = snapshot != null,
                                // Черновик переноса (F4.6, D-21): поле ввода означает
                                // изменение стоимости, а отправная точка показана подписью.
                                carriedBalance = row.balance?.takeIf { snapshot == null && row.isDraft },
                            ),
                            error = null,
                        )
                    }
                }
        }
    }

    fun closeSnapshotForm() {
        _state.update { it.copy(snapshotForm = null) }
    }

    fun setBalanceText(text: String) {
        updateForm { it.copy(balanceText = text, error = null) }
    }

    fun setContributionText(text: String) {
        updateForm { it.copy(contributionText = text, error = null) }
    }

    fun setNoteText(text: String) {
        updateForm { it.copy(noteText = text) }
    }

    /**
     * Переключение режима ввода при подтверждении перенесённой стоимости (F4.6, D-21):
     * дельта — рыночное изменение, сумма — значение целиком. Введённое число очищается,
     * потому что в двух режимах оно означает разное.
     */
    fun setSnapshotMode(mode: BalanceInputMode) {
        updateForm { it.copy(mode = mode, balanceText = "", error = null) }
    }

    /**
     * Сохранение остатка. Если снапшот за месяц уже есть, он заменяется — это делает
     * use-case `RecordSnapshot`, опираясь на уникальный индекс `(счёт, период)`.
     */
    fun submitSnapshot() {
        val form = _state.value.snapshotForm ?: return

        val balance = balanceOf(form) ?: return
        val contributionText = form.contributionText.trim()
        val contribution = if (contributionText.isEmpty()) {
            null
        } else {
            Money.parse(contributionText, form.currency)
        }
        if (contributionText.isNotEmpty() && contribution == null) {
            updateFormError("Взнос вводится числом: 5000 для пополнения и -2000 для снятия")
            return
        }
        val note = form.noteText.trim().ifEmpty { null }
        val accountName = form.accountName

        scope.launch {
            _state.update { it.copy(snapshotForm = it.snapshotForm?.copy(isSaving = true, error = null)) }
            runCatching {
                io {
                    recordSnapshot(
                        SnapshotInput(
                            accountId = form.accountId,
                            period = form.period,
                            balance = balance,
                            contribution = contribution,
                            note = note,
                        ),
                    )
                }
            }
                .onFailure { error -> _state.update { failed(it, error) } }
                .onSuccess { result ->
                    _state.update { it.copy(snapshotForm = null) }
                    reloadMonth(notice = snapshotNotice(result, accountName))
                }
        }
    }

    // ── Подтверждение перенесённой стоимости (F4.6, D-21) ────────────────────

    /**
     * Массовое подтверждение: все черновики месяца одним окном.
     *
     * Черновик — не факт: он не входит в Net Worth, пока пользователь не ввёл дельту
     * или сумму. Пустой ввод в строке означает «подтвердить перенесённое значение».
     */
    fun openConfirmBalances() {
        val reminder = _state.value.reminder ?: return
        _state.update {
            it.copy(
                confirmBalances = ConfirmBalancesState(
                    period = reminder.period,
                    items = reminder.accounts.map { account ->
                        ConfirmBalanceItem(
                            accountId = account.accountId,
                            accountName = account.name,
                            currency = account.currency,
                            carriedBalance = account.carriedBalance,
                        )
                    },
                ),
                error = null,
            )
        }
    }

    fun closeConfirmBalances() {
        _state.update { it.copy(confirmBalances = null) }
    }

    /**
     * Режим ввода в строке массового подтверждения. Введённое число очищается:
     * в двух режимах оно означает разное (F4.6, D-21).
     */
    fun setConfirmMode(accountId: String, mode: BalanceInputMode) =
        updateConfirm(accountId) { it.copy(mode = mode, amountText = "") }

    fun setConfirmAmount(accountId: String, text: String) =
        updateConfirm(accountId) { it.copy(amountText = text) }

    /**
     * Запись подтверждённых значений: на каждый счёт создаётся или правится снапшот месяца.
     *
     * Взнос не заполняется намеренно: дельта здесь — рыночное изменение стоимости, а
     * взносы и снятия остаются отдельным полем (D-21). Иначе прирост месяца потерял бы
     * смысл, потому что считается как `Δ − взносы`.
     */
    fun submitConfirmBalances() {
        val dialog = _state.value.confirmBalances ?: return
        val period = dialog.period

        // Разбор ввода до записи: ошибка в одном счёте не должна портить остальные.
        val requests = ArrayList<SnapshotInput>(dialog.items.size)
        for (item in dialog.items) {
            val balance = confirmedBalance(item) ?: return
            requests += SnapshotInput(accountId = item.accountId, period = period, balance = balance)
        }

        scope.launch {
            _state.update {
                it.copy(confirmBalances = it.confirmBalances?.copy(isSaving = true, error = null))
            }
            runCatching { io { requests.forEach { request -> recordSnapshot(request) } } }
                .onFailure { error -> _state.update { failed(it, error) } }
                .onSuccess {
                    _state.update { it.copy(confirmBalances = null) }
                    reloadMonth(notice = "Стоимость подтверждена: ${requests.size} ${accountsWord(requests.size)}")
                }
        }
    }

    /**
     * Значение строки массового подтверждения; null — разбор не прошёл, ошибка показана.
     *
     * Пустой ввод подтверждает перенесённую сумму как есть: пользователь согласен,
     * что стоимость за месяц не изменилась.
     */
    private fun confirmedBalance(item: ConfirmBalanceItem): Money? {
        val text = item.amountText.trim()
        if (text.isEmpty()) return item.carriedBalance

        val amount = Money.parse(text, item.currency)
        if (amount == null) {
            _state.update { state ->
                state.copy(
                    confirmBalances = state.confirmBalances?.copy(
                        error = "«${item.accountName}»: введите число, например 1500 или -250",
                    ),
                )
            }
            return null
        }
        return if (item.mode == BalanceInputMode.DELTA) item.carriedBalance + amount else amount
    }

    private fun updateConfirm(accountId: String, transform: (ConfirmBalanceItem) -> ConfirmBalanceItem) {
        _state.update { state ->
            state.copy(
                confirmBalances = state.confirmBalances?.let { dialog ->
                    dialog.copy(
                        error = null,
                        items = dialog.items.map { item ->
                            if (item.accountId == accountId) transform(item) else item
                        },
                    )
                },
            )
        }
    }

    // ── Счета (F4.2, F4.5) ───────────────────────────────────────────────────

    fun openAccountDialog() {
        _state.update { state ->
            state.copy(
                accountDialog = AccountDialogState(
                    categoryId = state.assetCategories.firstOrNull { it.isSelectable }?.id,
                ),
                error = null,
            )
        }
    }

    fun closeAccountDialog() {
        _state.update { it.copy(accountDialog = null) }
    }

    fun setAccountName(text: String) = updateDialog { it.copy(nameText = text, error = null) }

    /**
     * Смена категории счёта.
     *
     * Ставка и срок очищаются, если новая категория не подразумевает известного процента:
     * правило баланса живёт в категории (D-20), и сохранение со ставкой у «Акций» упало бы
     * на доменной проверке. Лучше убрать поля сразу, чем показывать ошибку постфактум.
     */
    fun setAccountCategory(categoryId: String) = updateDialog { dialog ->
        val projected = _state.value.assetCategories.firstOrNull { it.id == categoryId }
            ?.balancePolicy?.isProjected == true
        dialog.copy(
            categoryId = categoryId,
            rateText = if (projected) dialog.rateText else "",
            termText = if (projected) dialog.termText else "",
            interestDayText = if (projected) dialog.interestDayText else "",
            error = null,
        )
    }

    fun setAccountCurrency(text: String) = updateDialog { it.copy(currencyText = text, error = null) }

    fun setAccountRate(text: String) = updateDialog { it.copy(rateText = text, error = null) }

    /** Срок вклада в месяцах; пусто — бессрочно (F4.6, D-21). */
    fun setAccountTerm(text: String) = updateDialog { it.copy(termText = text, error = null) }

    /** День начисления процентов вклада; пусто — упрощённый расчёт (F4.6, D-31). */
    fun setAccountInterestDay(text: String) = updateDialog { it.copy(interestDayText = text, error = null) }

    /** Сумма, положенная на счёт при открытии: создаёт первый остаток месяца (D-21). */
    fun setAccountInitialBalance(text: String) =
        updateDialog { it.copy(initialBalanceText = text, error = null) }

    fun setAccountNote(text: String) = updateDialog { it.copy(noteText = text) }

    fun submitAccount() {
        val dialog = _state.value.accountDialog ?: return
        val categoryId = dialog.categoryId
        if (categoryId == null) {
            updateDialogError("Выберите категорию счёта")
            return
        }
        val name = dialog.nameText.trim()
        if (name.isEmpty()) {
            updateDialogError("Введите название счёта")
            return
        }
        val currency = dialog.currencyText.trim().uppercase()
        if (currency.isEmpty()) {
            updateDialogError("Введите код валюты, например RUB")
            return
        }
        val rateText = dialog.rateText.trim()
        val rateBp = if (rateText.isEmpty()) null else parseRateBp(rateText)
        if (rateText.isNotEmpty() && rateBp == null) {
            updateDialogError("Ставка вводится числом процентов, например 8,5")
            return
        }
        val termText = dialog.termText.trim()
        val termMonths = if (termText.isEmpty()) null else termText.toIntOrNull()
        if (termText.isNotEmpty() && (termMonths == null || termMonths <= 0)) {
            updateDialogError("Срок вводится числом месяцев, например 12")
            return
        }
        val dayText = dialog.interestDayText.trim()
        val interestDay = if (dayText.isEmpty()) null else dayText.toIntOrNull()
        if (dayText.isNotEmpty() && (interestDay == null || interestDay !in 1..AssetAccount.MAX_INTEREST_DAY)) {
            updateDialogError("День начисления — число от 1 до 31, например 6")
            return
        }
        val initialText = dialog.initialBalanceText.trim()
        val initialBalance = if (initialText.isEmpty()) null else Money.parse(initialText, currency)
        if (initialText.isNotEmpty() && initialBalance == null) {
            updateDialogError("Сумма вводится числом, например 500000 или 500 000,50")
            return
        }
        // Вклад без ставки и суммы не имеет смысла: прогнозировать нечего, а база
        // расписания останется пустой. Домен их не требует, поэтому проверяем здесь (D-21).
        val isDeposit = _state.value.assetCategories.firstOrNull { it.id == categoryId }
            ?.balancePolicy?.isProjected == true
        if (isDeposit) {
            if (rateBp == null) {
                updateDialogError("Для вклада укажите годовую ставку, например 16")
                return
            }
            if (initialBalance == null) {
                updateDialogError("Для вклада укажите сумму, которую положили на счёт")
                return
            }
        }
        val note = dialog.noteText.trim().ifEmpty { null }
        val openedPeriod = _state.value.period

        scope.launch {
            _state.update { it.copy(accountDialog = it.accountDialog?.copy(isSaving = true, error = null)) }
            runCatching {
                io {
                    createAccount(
                        AccountInput(
                            categoryId = categoryId,
                            name = name,
                            currency = currency,
                            openedPeriod = openedPeriod,
                            annualRateBp = rateBp,
                            termMonths = termMonths,
                            interestDay = interestDay,
                            initialBalance = initialBalance,
                            note = note,
                        ),
                    )
                }
            }
                .onFailure { error -> _state.update { failed(it, error) } }
                .onSuccess { account ->
                    _state.update { it.copy(accountDialog = null) }
                    reloadMonth(notice = "Счёт «${account.name}» добавлен")
                }
        }
    }

    /** Закрытие счёта: история сохраняется, счёт выбывает из будущих месяцев (F4.5). */
    fun requestCloseAccount(accountId: String) {
        val row = _state.value.rows().firstOrNull { it.accountId == accountId } ?: return
        _state.update {
            it.copy(
                confirm = AssetsConfirm(
                    accountId = accountId,
                    title = "Закрыть счёт?",
                    message = "«${row.name}» перестанет участвовать в следующих месяцах и в " +
                        "дублировании периода. История остатков сохранится.",
                    confirmLabel = "Закрыть счёт",
                ),
                error = null,
            )
        }
    }

    fun cancelConfirm() {
        _state.update { it.copy(confirm = null) }
    }

    fun confirmAction() {
        val confirm = _state.value.confirm ?: return
        val period = _state.value.period
        _state.update { it.copy(confirm = null) }
        scope.launch {
            runCatching { io { closeAccount(confirm.accountId, period) } }
                .onFailure { error -> _state.update { failed(it, error) } }
                .onSuccess { account ->
                    reloadMonth(notice = "Счёт «${account.name}» закрыт периодом ${period.shortRu()}")
                }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ── Загрузка месяца ──────────────────────────────────────────────────────

    private fun reloadMonth(notice: String? = null) {
        val period = _state.value.period
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            runCatching { io { readMonth(period) } }
                .onFailure { error -> _state.update { failed(it, error) } }
                .onSuccess { data ->
                    _state.update { state ->
                        state.copy(
                            isLoading = false,
                            header = data.header,
                            groups = data.groups,
                            assetCategories = data.assetCategories,
                            reminder = data.reminder,
                            notice = notice,
                            error = null,
                        )
                    }
                }
        }
    }

    private suspend fun readMonth(period: Period): MonthData {
        val accounts = assets.accounts(includeClosed = true)
        val assetCategories = categories.bySection(CategorySection.ASSET, includeArchived = true)
        // Значения месяца считает единый механизм (F4.6, D-21): введённый остаток, прогноз
        // вклада по ставке, перенос остатка и черновик ручной оценки. Снапшоты читаются до
        // выбранного месяца включительно — прогноз и перенос опираются на последний факт.
        val snapshots = assets.snapshotsUpTo(period)
        val values = AssetBalances.resolve(period, accounts, assetCategories, snapshots)
            .associateBy { it.accountId }
        val previous = AssetBalances.resolve(period.minusMonths(1), accounts, assetCategories, snapshots)
            .associateBy { it.accountId }
        val recorded = snapshots.filter { it.period == period }.associateBy { it.accountId }
        val totals = assetSummary(period)

        // Показываем счёт, если он активен в этом месяце или в нём есть введённый остаток:
        // закрытые счета не исчезают из истории (F4.5). Значение закрытого счёта механизм
        // не возвращает, поэтому его строка остаётся без суммы (D-19).
        val visible = accounts.filter { it.isActiveAt(period) || recorded.containsKey(it.id) }

        val grouped = assetCategories.mapNotNull { category ->
            val rows = rowsOf(
                accounts = visible.filter { it.categoryId == category.id },
                values = values,
                previous = previous,
                recorded = recorded,
                period = period,
            )
            if (rows.isEmpty()) {
                null
            } else {
                AccountGroup(
                    categoryId = category.id,
                    categoryName = category.name,
                    categoryColor = category.color,
                    accounts = rows,
                )
            }
        }

        // Счета категории, которой нет в списке (например, удалённой), не должны теряться.
        val knownIds = assetCategories.map { it.id }.toSet()
        val orphanRows = rowsOf(
            accounts = visible.filter { it.categoryId !in knownIds },
            values = values,
            previous = previous,
            recorded = recorded,
            period = period,
        )
        val groups = if (orphanRows.isEmpty()) {
            grouped
        } else {
            grouped + AccountGroup(
                categoryId = ORPHAN_GROUP_ID,
                categoryName = "Без категории",
                categoryColor = NEUTRAL_COLOR,
                accounts = orphanRows,
            )
        }

        return MonthData(
            header = headerOf(totals, visible.size),
            groups = groups,
            assetCategories = assetCategories,
            reminder = reminderOf(period, accounts, values),
        )
    }

    private fun rowsOf(
        accounts: List<AssetAccount>,
        values: Map<String, AccountBalance>,
        previous: Map<String, AccountBalance>,
        recorded: Map<String, AssetSnapshot>,
        period: Period,
    ): List<AccountRow> = accounts
        .map { account ->
            toRow(account, values[account.id], previous[account.id], recorded[account.id], period)
        }
        .sortedWith(
            // Сначала строки со значением, затем по убыванию суммы: порядок читается как
            // «крупные счета сверху», пустые строки уходят вниз.
            compareByDescending<AccountRow> { it.isCounted }
                .thenByDescending { it.balance?.minor ?: Long.MIN_VALUE }
                .thenBy { it.name },
        )

    private fun toRow(
        account: AssetAccount,
        value: AccountBalance?,
        previous: AccountBalance?,
        recorded: AssetSnapshot?,
        period: Period,
    ): AccountRow {
        val source = value?.source ?: BalanceSource.MISSING
        return AccountRow(
            accountId = account.id,
            name = account.name,
            currency = account.currency,
            balance = value?.balance,
            source = source,
            badge = badgeOf(value),
            accruedInterest = value?.accruedInterest,
            basedOnPeriod = value?.basedOnPeriod,
            previousBalance = previous?.balance,
            delta = differenceOrNull(value?.balance, previous?.balance),
            contribution = recorded?.contribution,
            isClosed = account.closedPeriod != null,
            isActiveInPeriod = account.isActiveAt(period),
            isMatured = value?.isMatured == true,
            snapshotId = recorded?.id,
        )
    }

    /**
     * Пометка рядом с суммой: источник значения или окончание срока вклада (F4.6, D-21).
     *
     * «Срок истёк» важнее источника: после окончания срока сумма переносится как обычный
     * остаток, но вклад пора закрывать или продлевать.
     */
    private fun badgeOf(value: AccountBalance?): String? {
        if (value == null) return null
        if (value.isMatured) return "срок истёк"
        return value.source.badgeRu()
    }

    /**
     * Напоминание обновить стоимость активов (F4.6, D-21).
     *
     * Системных уведомлений в v1 нет, поэтому напоминание живёт на экране: оно появляется,
     * когда в выбранном месяце есть перенесённые черновики. Пока пользователь не ввёл
     * дельту или сумму, черновики не входят в Net Worth — и баннер объясняет именно это.
     */
    private fun reminderOf(
        period: Period,
        accounts: List<AssetAccount>,
        values: Map<String, AccountBalance>,
    ): AssetReminder? {
        val drafts = values.values.filter { it.source.isDraft }
        if (drafts.isEmpty()) return null

        val byId = accounts.associateBy { it.id }
        val items = drafts.mapNotNull { value ->
            val account = byId[value.accountId] ?: return@mapNotNull null
            val balance = value.balance ?: return@mapNotNull null
            val basedOn = value.basedOnPeriod ?: return@mapNotNull null
            ReminderAccount(
                accountId = account.id,
                name = account.name,
                currency = account.currency,
                carriedBalance = balance,
                basedOnPeriod = basedOn,
            )
        }
        if (items.isEmpty()) return null

        val count = items.size
        return AssetReminder(
            period = period,
            title = "Обновите стоимость: $count ${accountsWord(count)} за ${period.titleRu()}",
            detail = "Значения перенесены с прошлых месяцев и не учитываются в Net Worth, " +
                "пока вы их не подтвердите",
            accounts = items,
        )
    }

    private fun headerOf(totals: AssetTotals, accountCount: Int): NetWorthHeader {
        val main = totals.of()
        val others = totals.totals
            .filter { it.currency != Money.DEFAULT_CURRENCY }
            .map { it.netWorth }

        if (main == null) {
            return NetWorthHeader.EMPTY.copy(accountCount = accountCount, otherCurrencies = others)
        }

        val previous = main.previousNetWorth
        // Процент имеет смысл только от положительной базы: на отрицательном Net Worth
        // знак дельты вводит в заблуждение.
        val percent = if (previous != null && previous.isPositive()) {
            main.delta?.let { deltaPercent(main.netWorth, previous) }
        } else {
            null
        }

        return NetWorthHeader(
            netWorth = main.netWorth,
            previousNetWorth = previous,
            delta = main.delta,
            deltaPercent = percent,
            contributions = main.contributions,
            gain = main.gain,
            accountCount = accountCount,
            snapshotCount = main.accountCount,
            // Прогноз вкладов входит в Net Worth, но показывается отдельно (D-21):
            // пользователь должен видеть, какая часть суммы посчитана, а не введена.
            projected = main.projected,
            otherCurrencies = others,
        )
    }

    // ── Служебное ────────────────────────────────────────────────────────────

    private suspend fun <T> io(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    /**
     * Значение, которое будет записано в снапшот (F4.3, F4.6).
     *
     * Для черновика переноса поле ввода трактуется по режиму: дельта прибавляется
     * к перенесённой стоимости, сумма — заменяет её. Пустой ввод подтверждает перенос
     * как есть. В остальных случаях поле — это остаток месяца целиком.
     * Возвращает null, если ввод не разобрать: сообщение уже показано в форме.
     */
    private fun balanceOf(form: SnapshotFormState): Money? {
        val carried = form.carriedBalance
        val text = form.balanceText.trim()

        if (carried == null) {
            val parsed = Money.parse(text, form.currency)
            if (parsed == null) {
                updateFormError("Введите остаток, например 15000 или 15 000,50")
            }
            return parsed
        }
        if (text.isEmpty()) return carried

        val amount = Money.parse(text, form.currency)
        if (amount == null) {
            updateFormError(
                if (form.mode == BalanceInputMode.DELTA) {
                    "Изменение стоимости вводится числом: 500 для роста и -250 для снижения"
                } else {
                    "Сумма вводится числом, например 15000 или 15 000,50"
                },
            )
            return null
        }
        return if (form.mode == BalanceInputMode.DELTA) carried + amount else amount
    }

    private fun updateForm(transform: (SnapshotFormState) -> SnapshotFormState) {
        _state.update { state -> state.copy(snapshotForm = state.snapshotForm?.let(transform)) }
    }

    private fun updateFormError(message: String) = updateForm { it.copy(error = message) }

    private fun updateDialog(transform: (AccountDialogState) -> AccountDialogState) {
        _state.update { state -> state.copy(accountDialog = state.accountDialog?.let(transform)) }
    }

    private fun updateDialogError(message: String) = updateDialog { it.copy(error = message) }

    private fun failed(state: AssetsUiState, error: Throwable): AssetsUiState = state.copy(
        isLoading = false,
        // Сообщения доменных use-case'ов уже на русском и понятны пользователю.
        error = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
        snapshotForm = state.snapshotForm?.copy(isSaving = false),
        accountDialog = state.accountDialog?.copy(isSaving = false),
        confirmBalances = state.confirmBalances?.copy(isSaving = false),
    )

    private fun snapshotNotice(result: SnapshotResult, accountName: String): String = when (result) {
        is SnapshotResult.Created -> "Остаток ${result.snapshot.balance.formatRu()} сохранён · $accountName"
        is SnapshotResult.Updated ->
            "Остаток обновлён: ${result.balanceBefore.formatRu()} → " +
                "${result.snapshot.balance.formatRu()} · $accountName"
    }

    private fun differenceOrNull(current: Money?, previous: Money?): Money? {
        if (current == null || previous == null || current.currency != previous.currency) return null
        return runCatching { current - previous }.getOrNull()
    }

    /** Проценты в базисные пункты: «8,5» -> 850. Не число — null, сообщение покажет форма. */
    private fun parseRateBp(text: String): Int? {
        val normalized = text.replace(',', '.').replace(" ", "").removeSuffix("%")
        if (normalized.isEmpty()) return null
        return runCatching {
            BigDecimal(normalized).movePointRight(RATE_SCALE)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact()
        }.getOrNull()
    }

    private data class MonthData(
        val header: NetWorthHeader,
        val groups: List<AccountGroup>,
        val assetCategories: List<Category>,
        val reminder: AssetReminder?,
    )

    /** Форма слова «счёт» по-русски: 1 счёт, 2 счёта, 5 счетов, 11 счетов. */
    private fun accountsWord(count: Int): String {
        if (count % HUNDRED in ELEVEN..FOURTEEN) return "счетов"
        return when (count % TEN) {
            1 -> "счёт"
            2, 3, 4 -> "счёта"
            else -> "счетов"
        }
    }

    private companion object {
        const val NEUTRAL_COLOR = "#7A8189"
        const val ORPHAN_GROUP_ID = ""
        const val RATE_SCALE = 2
        const val HUNDRED = 100
        const val TEN = 10
        const val ELEVEN = 11
        const val FOURTEEN = 14
    }
}

/** Плоский список строк текущего месяца — для поиска счёта по id. */
private fun AssetsUiState.rows(): List<AccountRow> = groups.flatMap { it.accounts }
