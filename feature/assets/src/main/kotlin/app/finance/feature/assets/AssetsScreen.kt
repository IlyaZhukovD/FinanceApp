package app.finance.feature.assets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.finance.core.model.BalanceSource
import app.finance.core.model.Category
import app.finance.core.model.Period
import app.finance.core.ui.AmountField
import app.finance.core.ui.CategoryDot
import app.finance.core.ui.ConfirmDialog
import app.finance.core.ui.EmptyState
import app.finance.core.ui.FinanceCard
import app.finance.core.ui.FinancePalette
import app.finance.core.ui.FinancePicker
import app.finance.core.ui.FinanceSpace
import app.finance.core.ui.HeroNumber
import app.finance.core.ui.MutedColor
import app.finance.core.ui.NoticeBar
import app.finance.core.ui.Numbers
import app.finance.core.ui.PeriodNavigator
import app.finance.core.ui.PrimaryButton
import app.finance.core.ui.QuietButton
import app.finance.core.ui.SecondaryButton
import app.finance.core.ui.SectionHeader
import app.finance.core.ui.TableRow
import app.finance.core.ui.deltaColor
import app.finance.core.ui.formatRu
import app.finance.core.ui.formatSignedRu
import app.finance.core.ui.shortRu
import app.finance.core.ui.titleRu
import app.finance.feature.categories.CategoriesController
import app.finance.feature.categories.CategoryManagerDialog

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
fun AssetsScreen(
    controller: AssetsController,
    categories: CategoriesController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()

    // Первичная загрузка месяца. Повторный вход экрана в композицию просто перечитывает данные.
    LaunchedEffect(controller) { controller.start() }

    // Отступы между блоками задаёт сама колонка: без spacedBy шапка, сводная карточка и
    // первая категория активов стояли вплотную друг к другу.
    Column(
        modifier = modifier.fillMaxSize().padding(vertical = FinanceSpace.Md),
        verticalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
    ) {
        state.error?.let { message ->
            NoticeBar(text = message, onDismiss = controller::dismissError, error = true)
        }
        state.notice?.let { message ->
            NoticeBar(text = message, onDismiss = controller::dismissNotice)
        }

        Toolbar(state = state, controller = controller, categories = categories)
        NetWorthCard(header = state.header, period = state.period)

        // Напоминание обновить стоимость (F4.6, D-21). Системных уведомлений в v1 нет:
        // черновики не входят в Net Worth, поэтому просьба подтвердить их видна сразу.
        state.reminder?.let { reminder ->
            ReminderBar(reminder = reminder, onOpen = controller::openConfirmBalances)
        }

        when {
            state.isLoading -> LoadingView()
            !state.hasAccounts -> EmptyState(
                title = "Счетов пока нет",
                hint = "Добавьте вклад, брокерский счёт или наличные и введите остаток за месяц.",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            else -> AccountsList(state = state, controller = controller)
        }
    }

    state.snapshotForm?.let { form -> SnapshotDialog(form = form, controller = controller) }
    state.confirmBalances?.let { dialog ->
        ConfirmBalancesDialog(dialog = dialog, controller = controller)
    }
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

    // Категории активов (F3.1). После закрытия раздел перечитывается: новая категория
    // появляется в пикере счёта, архивированная из него исчезает (F3.2).
    CategoryManagerDialog(controller = categories, onClosed = controller::start)
}

@Composable
private fun Toolbar(
    state: AssetsUiState,
    controller: AssetsController,
    categories: CategoriesController,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = FinanceSpace.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
    ) {
        SectionHeader(
            title = "Активы",
            subtitle = "Остатки вводятся за месяц и не переносятся автоматически",
            modifier = Modifier.weight(1f),
        )
        PeriodNavigator(
            period = state.period,
            onPrevious = controller::previousMonth,
            onNext = controller::nextMonth,
            onToday = controller::today,
        )
        // Категории активов (F3.1): тот же справочник и тот же диалог, что в расходах
        // и доходах, — копипаста экранов запрещена (F2.2).
        SecondaryButton(text = "Категории", onClick = categories::open)
        PrimaryButton(text = "Новый счёт", onClick = controller::openAccountDialog)
    }
}

/**
 * Сводная карточка: суммарные активы и разложение изменения на взносы и прирост (F4.1).
 *
 * В интерфейсе термин «Net Worth» не используется: раздел и карточка называются «Активы»,
 * а `NetWorth*` остаётся в именах кода и состояния как расчётное понятие — сумма активов
 * минус обязательства.
 *
 * Итог — число-герой: оно первым отвечает на вопрос «где я сейчас». Взносы и прирост
 * показаны рядом, потому что без них изменение необъяснимо: непонятно, это результат
 * внесения денег или движения рынка.
 */
@Composable
private fun NetWorthCard(header: NetWorthHeader, period: Period) {
    FinanceCard(
        modifier = Modifier.padding(horizontal = FinanceSpace.Lg),
        title = "Активы",
        subtitle = period.titleRu(),
    ) {
        // Капитал — единственное число-герой в приложении: оно первым отвечает
        // на вопрос «где я сейчас», поэтому крупнее всей остальной шкалы.
        Text(text = header.netWorth.formatRu(), style = HeroNumber)

        if (header.hasData) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Lg),
            ) {
                // Рост капитала — хорошо, просадка — плохо: цвет семантический, а не по знаку.
                Metric(
                    label = "Δ к прошлому месяцу",
                    value = header.delta?.let { it.formatSignedRu() } ?: "—",
                    suffix = header.deltaPercent?.let { "$it %" },
                    color = header.delta?.let { deltaColor(it.minor, favorableWhenPositive = true) }
                        ?: FinancePalette.TextPrimary,
                )
                // Взносы и снятия нейтральны: снятие денег со счёта — не убыток, поэтому
                // красить отрицательное значение в красный здесь было бы ошибкой.
                Metric(label = "Взносы и снятия", value = header.contributions.formatSignedRu())
                Metric(
                    label = "Прирост",
                    value = header.gain?.let { it.formatSignedRu() } ?: "—",
                    color = header.gain?.let { deltaColor(it.minor, favorableWhenPositive = true) }
                        ?: FinancePalette.TextPrimary,
                )
                // Прогноз вкладов входит в капитал, но показывается отдельно (D-21):
                // посчитанная сумма не должна выглядеть введённым остатком.
                if (header.hasProjection) {
                    Metric(
                        label = "Из них прогноз",
                        value = header.projected?.formatRu() ?: "—",
                        color = FinancePalette.TextSecondary,
                    )
                }
            }
            Text(
                text = "Счетов: ${header.accountCount}, со значением за месяц: ${header.snapshotCount}",
                style = MaterialTheme.typography.labelSmall,
                color = FinancePalette.TextSecondary,
            )
        } else {
            Text(
                text = "Значений за месяц нет: введите остаток или подтвердите перенесённую " +
                    "стоимость. Вклад считается по ставке сам, а оценка акций ждёт вашего ввода.",
                style = MaterialTheme.typography.bodySmall,
                color = FinancePalette.TextSecondary,
            )
        }

        if (header.hasMultipleCurrencies) {
            // Курсов конвертации в v1 нет, поэтому другие валюты показываются отдельно.
            Text(
                text = "Другие валюты: " + header.otherCurrencies.joinToString(", ") { it.formatRu() },
                style = MaterialTheme.typography.bodySmall,
                color = FinancePalette.Warning,
            )
        }
    }
}

@Composable
private fun RowScope.Metric(
    label: String,
    value: String,
    suffix: String? = null,
    color: Color = FinancePalette.TextPrimary,
) {
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(FinanceSpace.Xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = FinancePalette.TextSecondary,
        )
        Text(text = if (suffix == null) value else "$value · $suffix", style = Numbers, color = color)
    }
}

@Composable
private fun ColumnScope.AccountsList(state: AssetsUiState, controller: AssetsController) {
    LazyColumn(
        // `weight`, а не `fillMaxSize`: список обязан занимать ровно остаток высоты колонки,
        // иначе он вылезает за нижний край окна и последние счета не видны.
        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = FinanceSpace.Lg),
        contentPadding = PaddingValues(bottom = FinanceSpace.Md),
        verticalArrangement = Arrangement.spacedBy(FinanceSpace.Md),
    ) {
        items(items = state.groups, key = { it.categoryId }) { group ->
            GroupCard(group = group, onAccountClick = controller::openSnapshotForm)
        }
    }
}

/**
 * Группа счетов одной категории: карточка с итогом и строки счетов.
 *
 * Строка кликабельна и открывает ввод остатка — это основное действие раздела, поэтому
 * оно доступно без дополнительного меню. Отсутствие остатка помечено предупреждающим
 * цветом: пропущенный месяц рвёт динамику капитала (D-15).
 */
@Composable
private fun GroupCard(group: AccountGroup, onAccountClick: (String) -> Unit) {
    FinanceCard(
        title = group.categoryName,
        actions = {
            CategoryDot(hexColor = group.categoryColor)
            group.total?.let { total ->
                Text(text = total.formatRu(), style = Numbers)
            }
        },
    ) {
        group.accounts.forEachIndexed { index, row ->
            AccountRowView(
                row = row,
                alternate = index % 2 == 1,
                showDivider = index != group.accounts.lastIndex,
                onClick = { onAccountClick(row.accountId) },
            )
        }
    }
}

@Composable
private fun AccountRowView(row: AccountRow, alternate: Boolean, showDivider: Boolean, onClick: () -> Unit) {
    TableRow(alternate = alternate, showDivider = showDivider, onClick = onClick) {
        Column(modifier = Modifier.weight(1f).padding(horizontal = FinanceSpace.Xs)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Подпись объясняет происхождение суммы (F4.6, D-21): прогноз вклада, перенос
            // остатка или черновик, который ещё не подтверждён. «Тип счёта» больше не
            // подписывается: природу счёта задаёт категория, а её имя уже стоит в
            // заголовке группы (D-22).
            val status = when {
                !row.isActiveInPeriod -> "закрыт, не учитывается"
                row.isMatured -> "срок истёк, закройте или продлите"
                row.isDraft -> "черновик: стоимость ждёт подтверждения"
                row.badge != null -> buildString {
                    append(row.badge)
                    row.basedOnPeriod?.let { append(" с ${it.shortRu()}") }
                }

                row.source == BalanceSource.MISSING -> "значение не введено"
                row.isClosed -> "закрыт"
                else -> null
            }
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        row.isActiveInPeriod && row.source == BalanceSource.MISSING -> FinancePalette.Warning
                        row.isDraft -> FinancePalette.Warning
                        else -> FinancePalette.TextSecondary
                    },
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).padding(horizontal = FinanceSpace.Xs),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = row.balance?.formatRu() ?: "—",
                style = Numbers,
                color = when {
                    row.balance == null -> FinancePalette.TextDisabled
                    // Черновик не входит в итоги: приглушённая сумма не выглядит подтверждённой.
                    row.isDraft -> FinancePalette.TextSecondary
                    else -> FinancePalette.TextPrimary
                },
            )
            // Начисленный процент объясняет, откуда взялась прогнозная сумма вклада.
            row.accruedInterest?.takeIf { it.isPositive() }?.let { interest ->
                Text(
                    text = "проценты ${interest.formatRu()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = FinancePalette.TextSecondary,
                )
            }
            // Проценты «под замком»: набежали, но выплатятся в конце срока (D-32).
            row.pendingInterest?.takeIf { it.isPositive() }?.let { interest ->
                Text(
                    text = "накоплено ${interest.formatRu()}, выплата в конце срока",
                    style = MaterialTheme.typography.labelSmall,
                    color = FinancePalette.TextSecondary,
                )
            }
            row.delta?.let { delta ->
                Text(
                    text = delta.formatSignedRu(),
                    style = MaterialTheme.typography.labelSmall,
                    color = deltaColor(delta.minor, favorableWhenPositive = true),
                )
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
                if (form.isDraftConfirm) {
                    // Черновик переноса (F4.6, D-21): пользователь выбирает, ввести
                    // рыночную дельту или сумму целиком. Взносы остаются отдельным полем.
                    Text(
                        text = "Перенесено: ${form.carriedBalance?.formatRu()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedColor,
                    )
                    FinancePicker(
                        label = "Как ввести значение",
                        current = form.mode.titleRu,
                        options = BalanceInputMode.entries.map { it.name to it.titleRu },
                        onPick = { name ->
                            BalanceInputMode.entries.firstOrNull { it.name == name }
                                ?.let(controller::setSnapshotMode)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AmountField(
                    value = form.balanceText,
                    onValueChange = controller::setBalanceText,
                    label = when {
                        !form.isDraftConfirm -> "Остаток, ${form.currency}"
                        form.mode == BalanceInputMode.DELTA -> "Изменение стоимости, ${form.currency}"
                        else -> "Сумма целиком, ${form.currency}"
                    },
                    isError = form.error != null,
                    supportingText = form.error
                        ?: if (form.isDraftConfirm) "Пусто — подтвердить перенесённое значение" else null,
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
                // Разрушительное действие окрашено в error и отделено от основных кнопок:
                // «Закрыть счёт» не должно стоять рядом с «Сохранить» с одинаковым весом.
                Text(
                    text = "Закрыть счёт",
                    style = MaterialTheme.typography.labelLarge,
                    color = FinancePalette.Error,
                    modifier = Modifier.clickable { controller.requestCloseAccount(form.accountId) },
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                text = if (form.isSaving) "Сохраняем…" else "Сохранить",
                onClick = controller::submitSnapshot,
                enabled = !form.isSaving,
            )
        },
        dismissButton = {
            QuietButton(text = "Отмена", onClick = controller::closeSnapshotForm)
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
    // Правило баланса хранится в категории (D-20): ставка, срок и сумма вклада показываются
    // только там, где процент известен заранее.
    val isDeposit = categories.firstOrNull { it.id == dialog.categoryId }
        ?.balancePolicy?.isProjected == true

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
                FinancePicker(
                    label = "Категория",
                    current = categories.firstOrNull { it.id == dialog.categoryId }?.name ?: "—",
                    options = categories.map { it.id to it.name },
                    onPick = controller::setAccountCategory,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = dialog.currencyText,
                        onValueChange = controller::setAccountCurrency,
                        label = { Text("Валюта") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (isDeposit) {
                        OutlinedTextField(
                            value = dialog.rateText,
                            onValueChange = controller::setAccountRate,
                            label = { Text("Ставка % годовых") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = dialog.initialBalanceText,
                        onValueChange = controller::setAccountInitialBalance,
                        label = { Text(if (isDeposit) "Сумма вклада" else "Сумма (необязательно)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (isDeposit) {
                        OutlinedTextField(
                            value = dialog.termText,
                            onValueChange = controller::setAccountTerm,
                            label = { Text("Срок, мес.") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                if (isDeposit) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = dialog.interestDayText,
                            onValueChange = controller::setAccountInterestDay,
                            label = { Text("День начисления %") },
                            placeholder = { Text("например 6") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "Процент считается по дням, как в банке. Пусто — ставка / 12.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedColor,
                            modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            controller.setAccountInterestAtMaturity(!dialog.interestAtMaturity)
                        },
                    ) {
                        Checkbox(
                            checked = dialog.interestAtMaturity,
                            onCheckedChange = controller::setAccountInterestAtMaturity,
                        )
                        Column {
                            Text("Проценты в конце срока", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "Без капитализации: до конца срока проценты показываются " +
                                    "отдельно и в сумму не входят.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedColor,
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        controller.setAccountMonthlyReminder(!dialog.monthlyReminder)
                    },
                ) {
                    Checkbox(
                        checked = dialog.monthlyReminder,
                        onCheckedChange = controller::setAccountMonthlyReminder,
                    )
                    Column {
                        Text("Напоминать обновить остаток", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "В последнюю неделю месяца, пока остаток за месяц не введён, " +
                                "счёт появится в плашке «Обновите остаток».",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedColor,
                        )
                    }
                }
                OutlinedTextField(
                    value = dialog.noteText,
                    onValueChange = controller::setAccountNote,
                    label = { Text("Заметка") },
                    singleLine = true,
                )
                Text(
                    text = if (isDeposit) {
                        "Вклад открывается текущим месяцем. Укажите ставку, срок и сумму — " +
                            "следующие месяцы посчитаются с процентами и будут помечены как прогноз."
                    } else {
                        "Счёт открывается текущим месяцем. Ставка и срок задаются только " +
                            "категориям с известным процентом."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedColor,
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                text = if (dialog.isSaving) "Создаём…" else "Создать",
                onClick = controller::submitAccount,
                enabled = !dialog.isSaving,
            )
        },
        dismissButton = {
            QuietButton(text = "Отмена", onClick = controller::closeAccountDialog)
        },
    )
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

/**
 * Напоминание обновить стоимость активов (F4.6, D-21).
 *
 * Системных уведомлений в v1 нет: напоминание живёт на экране активов и предлагает одно
 * действие — ввести дельту или сумму по всем счетам, чья стоимость перенесена черновиком.
 */
@Composable
private fun ReminderBar(reminder: AssetReminder, onOpen: () -> Unit) {
    FinanceCard(
        modifier = Modifier.padding(horizontal = FinanceSpace.Lg),
        title = reminder.title,
        subtitle = reminder.detail,
        actions = { PrimaryButton(text = "Обновить", onClick = onOpen) },
    ) {
        // Счета перечислены сразу: пользователь видит, о чём речь, до открытия окна.
        Text(
            text = reminder.accounts.joinToString(" · ") { "${it.name}: ${it.carriedBalance.formatRu()}" },
            style = MaterialTheme.typography.labelSmall,
            color = FinancePalette.TextSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Массовое подтверждение стоимости (F4.6, D-21).
 *
 * Одно окно на все черновики месяца: для каждого счёта выбирается режим ввода — рыночная
 * дельта или сумма целиком. Пустое поле подтверждает перенесённое значение как есть.
 */
@Composable
private fun ConfirmBalancesDialog(dialog: ConfirmBalancesState, controller: AssetsController) {
    AlertDialog(
        onDismissRequest = controller::closeConfirmBalances,
        title = { Text("Стоимость · ${dialog.period.titleRu()}") },
        text = {
            Column(
                // Счетов может быть больше, чем влезает в окно: список прокручивается.
                modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Дельта — рыночное изменение стоимости: взносы и снятия вводятся " +
                        "отдельно в карточке счёта. Пустое поле подтверждает перенесённую сумму.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedColor,
                )
                dialog.error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = FinancePalette.Error,
                    )
                }
                dialog.items.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(FinanceSpace.Xs)) {
                        Text(
                            text = "${item.accountName} · перенесено ${item.carriedBalance.formatRu()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FinancePicker(
                                label = "Ввод",
                                current = item.mode.titleRu,
                                options = BalanceInputMode.entries.map { it.name to it.titleRu },
                                onPick = { name ->
                                    BalanceInputMode.entries.firstOrNull { it.name == name }
                                        ?.let { controller.setConfirmMode(item.accountId, it) }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            AmountField(
                                value = item.amountText,
                                onValueChange = { text -> controller.setConfirmAmount(item.accountId, text) },
                                label = if (item.mode == BalanceInputMode.DELTA) {
                                    "Δ, ${item.currency}"
                                } else {
                                    "Сумма, ${item.currency}"
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                text = if (dialog.isSaving) "Сохраняем…" else "Подтвердить",
                onClick = controller::submitConfirmBalances,
                enabled = !dialog.isSaving,
            )
        },
        dismissButton = {
            QuietButton(text = "Позже", onClick = controller::closeConfirmBalances)
        },
    )
}
