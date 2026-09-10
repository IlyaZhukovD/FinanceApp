package app.finance.feature.assets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.finance.core.model.AssetKind
import app.finance.core.model.Category
import app.finance.core.model.Period
import app.finance.core.ui.AmountField
import app.finance.core.ui.CategoryDot
import app.finance.core.ui.ConfirmDialog
import app.finance.core.ui.EmptyState
import app.finance.core.ui.MoneyText
import app.finance.core.ui.MutedColor
import app.finance.core.ui.NoticeBar
import app.finance.core.ui.PeriodNavigator
import app.finance.core.ui.formatRu
import app.finance.core.ui.formatSignedRu
import app.finance.core.ui.titleRu

/**
 * Раздел «Активы» (SPEC.md F4).
 *
 * Только разметка и проброс событий в [AssetsController]: ни чтения базы, ни расчётов.
 * Экран не переиспользует `LedgerScreen`, потому что другая единица учёта — снапшот счёта,
 * а не ячейка «категория + месяц» (DECISIONS D-12).
 */
object AssetsSection {
    /** Идентификатор для боковой навигации. */
    const val NAVIGATION_ID = "assets"
}

@Composable
fun AssetsScreen(controller: AssetsController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()

    // Первичная загрузка месяца. Повторный вход экрана в композицию просто перечитывает данные.
    LaunchedEffect(controller) { controller.start() }

    Column(modifier = modifier.fillMaxSize()) {
        state.error?.let { message ->
            NoticeBar(text = message, onDismiss = controller::dismissError, error = true)
        }
        state.notice?.let { message ->
            NoticeBar(text = message, onDismiss = controller::dismissNotice)
        }

        Toolbar(state = state, controller = controller)
        NetWorthCard(header = state.header, period = state.period)

        when {
            state.isLoading -> LoadingView()
            !state.hasAccounts -> EmptyState(
                title = "Счетов пока нет",
                hint = "Добавьте вклад, брокерский счёт или наличные и введите остаток за месяц.",
                modifier = Modifier.fillMaxSize(),
            )

            else -> AccountsList(state = state, controller = controller)
        }
    }

    state.snapshotForm?.let { form -> SnapshotDialog(form = form, controller = controller) }
    state.accountDialog?.let { dialog ->
        AccountDialog(dialog = dialog, categories = state.assetCategories, controller = controller)
    }
    state.confirm?.let { confirm ->
        ConfirmDialog(
            title = confirm.title,
            message = confirm.message,
            confirmLabel = confirm.confirmLabel,
            onConfirm = controller::confirmAction,
            onDismiss = controller::cancelConfirm,
            destructive = true,
        )
    }
}

@Composable
private fun Toolbar(state: AssetsUiState, controller: AssetsController) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PeriodNavigator(
            period = state.period,
            onPrevious = controller::previousMonth,
            onNext = controller::nextMonth,
            onToday = controller::today,
        )
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = controller::openAccountDialog) { Text("Новый счёт") }
    }
}

/** Шапка: Net Worth и разложение изменения на взносы и прирост (F4.1). */
@Composable
private fun NetWorthCard(header: NetWorthHeader, period: Period) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Net Worth · ${period.titleRu()}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MoneyText(
                money = header.netWorth,
                style = MaterialTheme.typography.headlineMedium,
            )

            if (header.hasData) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Metric(
                        label = "Δ к прошлому месяцу",
                        value = header.delta?.let { it.formatSignedRu() } ?: "—",
                        suffix = header.deltaPercent?.let { "$it%" },
                    )
                    Metric(label = "Взносы и снятия", value = header.contributions.formatSignedRu())
                    Metric(label = "Прирост", value = header.gain?.let { it.formatSignedRu() } ?: "—")
                }
                Text(
                    text = "Счетов: ${header.accountCount}, с остатком за месяц: ${header.snapshotCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedColor,
                )
            } else {
                Text(
                    text = "Остатки за месяц не введены. Балансы не переносятся автоматически: " +
                        "введите их или воспользуйтесь дублированием месяца.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedColor,
                )
            }

            if (header.hasMultipleCurrencies) {
                // Курсов конвертации в v1 нет, поэтому другие валюты показываются отдельно.
                Text(
                    text = "Другие валюты: " + header.otherCurrencies.joinToString(", ") { it.formatRu() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, suffix: String? = null) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MutedColor)
        Text(
            text = if (suffix == null) value else "$value · $suffix",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AccountsList(state: AssetsUiState, controller: AssetsController) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.groups.forEach { group ->
            item(key = "group-${group.categoryId}") { GroupHeader(group = group) }
            items(items = group.accounts, key = { it.accountId }) { row ->
                AccountRowView(row = row, onClick = { controller.openSnapshotForm(row.accountId) })
            }
        }
    }
}

@Composable
private fun GroupHeader(group: AccountGroup) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CategoryDot(hexColor = group.categoryColor)
        Text(text = group.categoryName, style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.weight(1f))
        group.total?.let { total ->
            MoneyText(money = total, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AccountRowView(row: AccountRow, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = row.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = buildString {
                        append(row.kindLabel)
                        if (row.isClosed) append(" · закрыт")
                        if (!row.hasSnapshot) append(" · остаток не введён")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedColor,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                if (row.balance != null) {
                    MoneyText(money = row.balance)
                } else {
                    Text(text = "—", style = MaterialTheme.typography.bodyLarge, color = MutedColor)
                }
                row.delta?.let { delta ->
                    Text(
                        text = delta.formatSignedRu(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (delta.isNegative()) MaterialTheme.colorScheme.error else MutedColor,
                    )
                }
            }
        }
    }
}

/** Ввод остатка за месяц: повторный ввод правит существующий снапшот (F4.3). */
@Composable
private fun SnapshotDialog(form: SnapshotFormState, controller: AssetsController) {
    AlertDialog(
        onDismissRequest = controller::closeSnapshotForm,
        title = { Text(text = "Остаток · ${form.period.titleRu()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = form.accountName, style = MaterialTheme.typography.titleSmall)
                AmountField(
                    value = form.balanceText,
                    onValueChange = controller::setBalanceText,
                    label = "Остаток, ${form.currency}",
                    isError = form.error != null,
                    supportingText = form.error,
                )
                AmountField(
                    value = form.contributionText,
                    onValueChange = controller::setContributionText,
                    label = "Взносы и снятия (необязательно)",
                )
                OutlinedTextField(
                    value = form.noteText,
                    onValueChange = controller::setNoteText,
                    label = { Text("Заметка") },
                    singleLine = true,
                )
                if (form.isEdit) {
                    Text(
                        text = "Снапшот за этот месяц уже есть: сохранение заменит прежнее значение.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedColor,
                    )
                }
                TextButton(onClick = { controller.requestCloseAccount(form.accountId) }) {
                    Text(text = "Закрыть счёт", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = controller::submitSnapshot, enabled = !form.isSaving) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = controller::closeSnapshotForm) { Text("Отмена") }
        },
    )
}

/** Создание счёта (F4.2). */
@Composable
private fun AccountDialog(
    dialog: AccountDialogState,
    categories: List<Category>,
    controller: AssetsController,
) {
    AlertDialog(
        onDismissRequest = controller::closeAccountDialog,
        title = { Text("Новый счёт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = dialog.nameText,
                    onValueChange = controller::setAccountName,
                    label = { Text("Название") },
                    singleLine = true,
                    isError = dialog.error != null,
                    supportingText = dialog.error?.let { { Text(it) } },
                )
                Picker(
                    label = "Категория",
                    current = categories.firstOrNull { it.id == dialog.categoryId }?.name ?: "—",
                    options = categories.map { it.id to it.name },
                    onPick = controller::setAccountCategory,
                )
                Picker(
                    label = "Тип счёта",
                    current = dialog.kind.titleRu(),
                    options = AssetKind.entries.map { it.name to it.titleRu() },
                    onPick = { name ->
                        AssetKind.entries.firstOrNull { it.name == name }?.let(controller::setAccountKind)
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = dialog.currencyText,
                        onValueChange = controller::setAccountCurrency,
                        label = { Text("Валюта") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = dialog.rateText,
                        onValueChange = controller::setAccountRate,
                        label = { Text("Ставка %") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = dialog.noteText,
                    onValueChange = controller::setAccountNote,
                    label = { Text("Заметка") },
                    singleLine = true,
                )
                Text(
                    text = "Счёт открывается текущим месяцем. Ставка учитывается только для вклада.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedColor,
                )
            }
        },
        confirmButton = {
            Button(onClick = controller::submitAccount, enabled = !dialog.isSaving) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = controller::closeAccountDialog) { Text("Отмена") }
        },
    )
}

/** Компактный выпадающий список: в v1 достаточно, поиск не требуется (F4.2). */
@Composable
private fun Picker(
    label: String,
    current: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MutedColor)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(text = current)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, title) ->
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = {
                            onPick(id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Читаем остатки…", style = MaterialTheme.typography.bodyMedium, color = MutedColor)
        }
    }
}
