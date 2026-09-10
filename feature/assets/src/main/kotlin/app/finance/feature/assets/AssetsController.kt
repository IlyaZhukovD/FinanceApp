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
import app.finance.core.model.AssetKind
import app.finance.core.model.AssetSnapshot
import app.finance.core.model.Category
import app.finance.core.model.CategorySection
import app.finance.core.model.Money
import app.finance.core.model.Period
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
     * Сохранение остатка. Если снапшот за месяц уже есть, он заменяется — это делает
     * use-case `RecordSnapshot`, опираясь на уникальный индекс `(счёт, период)`.
     */
    fun submitSnapshot() {
        val form = _state.value.snapshotForm ?: return

        val balance = Money.parse(form.balanceText, form.currency)
        if (balance == null) {
            updateFormError("Введите остаток, например 15000 или 15 000,50")
            return
        }
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

    fun setAccountCategory(categoryId: String) = updateDialog { it.copy(categoryId = categoryId, error = null) }

    fun setAccountKind(kind: AssetKind) = updateDialog { it.copy(kind = kind, error = null) }

    fun setAccountCurrency(text: String) = updateDialog { it.copy(currencyText = text, error = null) }

    fun setAccountRate(text: String) = updateDialog { it.copy(rateText = text, error = null) }

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
                            kind = dialog.kind,
                            currency = currency,
                            openedPeriod = openedPeriod,
                            annualRateBp = rateBp,
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
        val snapshots = assets.snapshotsForPeriod(period).associateBy { it.accountId }
        val previous = assets.snapshotsForPeriod(period.minusMonths(1)).associateBy { it.accountId }
        val totals = assetSummary(period)

        // Показываем счёт, если он активен в этом месяце или в нём есть введённый остаток:
        // закрытые счета не исчезают из истории (F4.5).
        val visible = accounts.filter { it.isActiveAt(period) || snapshots.containsKey(it.id) }

        val grouped = assetCategories.mapNotNull { category ->
            val rows = rowsOf(visible.filter { it.categoryId == category.id }, snapshots, previous)
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
        val orphanRows = rowsOf(visible.filter { it.categoryId !in knownIds }, snapshots, previous)
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
        )
    }

    private fun rowsOf(
        accounts: List<AssetAccount>,
        snapshots: Map<String, AssetSnapshot>,
        previous: Map<String, AssetSnapshot>,
    ): List<AccountRow> = accounts
        .map { account -> toRow(account, snapshots[account.id], previous[account.id]) }
        .sortedWith(
            compareByDescending<AccountRow> { it.balance != null }
                .thenByDescending { it.balance?.minor ?: Long.MIN_VALUE }
                .thenBy { it.name },
        )

    private fun toRow(account: AssetAccount, snapshot: AssetSnapshot?, previous: AssetSnapshot?): AccountRow {
        val balance = snapshot?.balance
        val previousBalance = previous?.balance
        return AccountRow(
            accountId = account.id,
            name = account.name,
            kindLabel = account.kind.titleRu(),
            currency = account.currency,
            balance = balance,
            previousBalance = previousBalance,
            delta = differenceOrNull(balance, previousBalance),
            contribution = snapshot?.contribution,
            isClosed = account.closedPeriod != null,
            snapshotId = snapshot?.id,
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
            otherCurrencies = others,
        )
    }

    // ── Служебное ────────────────────────────────────────────────────────────

    private suspend fun <T> io(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

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
    )

    private companion object {
        const val NEUTRAL_COLOR = "#7A8189"
        const val ORPHAN_GROUP_ID = ""
        const val RATE_SCALE = 2
    }
}

/** Плоский список строк текущего месяца — для поиска счёта по id. */
private fun AssetsUiState.rows(): List<AccountRow> = groups.flatMap { it.accounts }
