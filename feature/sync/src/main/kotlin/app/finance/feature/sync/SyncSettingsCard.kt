package app.finance.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import app.finance.core.network.YandexAuth
import app.finance.core.sync.SyncProblem
import app.finance.core.sync.SyncTransportException
import app.finance.core.ui.FinanceCard
import app.finance.core.ui.FinancePalette
import app.finance.core.ui.FinanceSpace
import app.finance.core.ui.FinanceTextField
import app.finance.core.ui.MutedColor
import app.finance.core.ui.NoticeBar
import app.finance.core.ui.PrimaryButton
import app.finance.core.ui.QuietButton
import app.finance.core.ui.SecondaryButton
import kotlinx.coroutines.delay

/**
 * Карточка «Синхронизация через Яндекс.Диск» в настройках (docs/SYNC-YANDEX.md §7).
 *
 * Порядок шагов: Client ID приложения → вход в Яндекс → включение. Пока шаг не пройден,
 * следующий не показывается: так экран сам объясняет, что делать дальше.
 */
@Composable
fun SyncSettingsCard(controller: SyncController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }

    FinanceCard(
        modifier = modifier,
        title = "Синхронизация через Яндекс.Диск",
        subtitle = "Одни и те же данные на нескольких компьютерах. Файлы лежат в папке «${state.folderLabel}»",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
            state.error?.let { NoticeBar(text = it, onDismiss = controller::dismissError, error = true) }
            state.notice?.let { NoticeBar(text = it, onDismiss = controller::dismissNotice) }
            if (state.isDevBuild) {
                Hint("Сборка для разработки: своя папка на Диске (dev), установленная версия её не видит.")
            }
            state.busyText?.let { Hint(it) }

            if (!state.isLoaded) return@Column

            // Шаг 1. Приложение на oauth.yandex.ru.
            if (state.isEditingClientId) {
                ClientIdEditor(state, controller)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
                Hint("Client ID: ${state.clientId}", modifier = Modifier.weight(1f))
                QuietButton(text = "Изменить", onClick = controller::startEditClientId, enabled = !state.isBusy)
            }

            // Шаг 2. Вход.
            if (!state.isSignedIn) {
                SignIn(state, controller)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
                Text(
                    text = "Аккаунт Яндекса: ${state.login ?: "вход выполнен"}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                QuietButton(text = "Выйти", onClick = controller::signOut, enabled = !state.isBusy)
            }

            // Шаг 3. Синхронизация.
            state.problem?.let { problem -> ProblemBlock(problem, state, controller) }
            if (!state.isEnabled) {
                Hint(
                    "Первый компьютер выгружает свои данные на Диск. Следующие подключайте с пустой базой — " +
                        "данные загрузятся с Диска.",
                )
                PrimaryButton(text = "Включить синхронизацию", onClick = controller::enable, enabled = !state.isBusy)
            } else {
                val (status, _) = syncStatusLine(state, now)
                Text(text = status, style = MaterialTheme.typography.bodyMedium)
                Hint("Автоматически: при запуске, каждые 10 минут и при закрытии приложения.")
                Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
                    PrimaryButton(text = "Синхронизировать сейчас", onClick = controller::syncNow, enabled = !state.isBusy)
                    SecondaryButton(text = "Отключить", onClick = controller::disable, enabled = !state.isBusy)
                }
            }
        }
    }
}

@Composable
private fun ClientIdEditor(state: SyncUiState, controller: SyncController) {
    Hint(
        "Один раз зарегистрируйте приложение на oauth.yandex.ru: платформа «Веб-сервисы», Redirect URI — " +
            "${YandexAuth.LOOPBACK_REDIRECT} и ${YandexAuth.VERIFICATION_REDIRECT}, доступ «Яндекс.Диск REST API → " +
            "Доступ к папке приложения на Диске». Подробно — docs/SYNC-YANDEX.md.",
    )
    FinanceTextField(
        value = state.clientIdDraft,
        onValueChange = controller::updateClientIdDraft,
        label = "Client ID",
        placeholder = "например 1a2b3c…",
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
        PrimaryButton(text = "Сохранить", onClick = controller::saveClientId, enabled = state.clientIdDraft.isNotBlank())
        if (state.clientId.isNotEmpty()) QuietButton(text = "Отмена", onClick = controller::cancelEditClientId)
    }
}

@Composable
private fun SignIn(state: SyncUiState, controller: SyncController) {
    val code = state.codeLogin
    if (code == null) {
        Hint("Войдите в Яндекс: откроется браузер, после подтверждения вернитесь в Finance.")
        Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
            PrimaryButton(text = "Войти через браузер", onClick = controller::signInWithBrowser, enabled = !state.isBusy)
            SecondaryButton(text = "Войти с кодом", onClick = controller::startCodeLogin, enabled = !state.isBusy)
        }
        return
    }
    Hint("В браузере открылась страница Яндекса. Подтвердите доступ и вставьте показанный код. Если страница не открылась:")
    SelectionContainer { Text(text = code.url, style = MaterialTheme.typography.labelSmall, color = MutedColor) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
        FinanceTextField(
            value = code.code,
            onValueChange = controller::updateCode,
            label = "Код",
            modifier = Modifier.width(CODE_WIDTH),
        )
        PrimaryButton(text = "Подтвердить", onClick = controller::finishCodeLogin, enabled = code.code.isNotBlank() && !state.isBusy)
        QuietButton(text = "Отмена", onClick = controller::cancelCodeLogin)
    }
}

@Composable
private fun ProblemBlock(problem: SyncProblem, state: SyncUiState, controller: SyncController) {
    Text(text = problemText(problem), style = MaterialTheme.typography.bodyMedium, color = FinancePalette.Error)
    Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
        when (problem) {
            SyncProblem.RemoteMissing, is SyncProblem.RemoteCorrupt -> {
                SecondaryButton(text = "Создать заново с этого компьютера", onClick = controller::recreateRemote, enabled = !state.isBusy)
            }
            is SyncProblem.Transport -> if (problem.kind != SyncTransportException.Kind.AUTH) {
                SecondaryButton(text = "Повторить", onClick = controller::syncNow, enabled = !state.isBusy)
            }
            is SyncProblem.NeedsUpdate -> SecondaryButton(text = "Проверить снова", onClick = controller::syncNow, enabled = !state.isBusy)
            SyncProblem.RemoteReplaced, SyncProblem.LocalNotEmpty -> Unit
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MutedColor, modifier = modifier.padding(vertical = 2.dp))
}

private val CODE_WIDTH = 200.dp
private const val TICK_MS = 30_000L
