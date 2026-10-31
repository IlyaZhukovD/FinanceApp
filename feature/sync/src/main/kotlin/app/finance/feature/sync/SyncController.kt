package app.finance.feature.sync

import app.finance.core.network.YandexAuth
import app.finance.core.network.YandexAuthException
import app.finance.core.sync.SyncEngine
import app.finance.core.sync.SyncOutcome
import app.finance.core.sync.SyncProblem
import app.finance.core.sync.SyncTransportException
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Синхронизация через Яндекс.Диск (docs/SYNC-YANDEX.md §7).
 *
 * Когда синхронизировать: при запуске, каждые [autoIntervalMillis] пока приложение открыто,
 * перед закрытием ([syncBeforeExit]) и по кнопке или ⌘S/Ctrl+S ([syncNow]). Синхронизация
 * не блокирует работу: ошибки сети и Диска показываются статусом и не мешают вводу.
 *
 * @param onReceived хост перечитывает разделы: пришли изменения с другого компьютера.
 */
class SyncController(
    private val engine: SyncEngine,
    private val account: YandexAccount,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    folderLabel: String,
    isDevBuild: Boolean,
    private val onReceived: () -> Unit = {},
    private val openBrowser: (String) -> Unit = ::browse,
    private val autoIntervalMillis: Long = AUTO_INTERVAL_MS,
) {
    private val _state = MutableStateFlow(SyncUiState(folderLabel = folderLabel, isDevBuild = isDevBuild))
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private var autoJob: Job? = null
    private var loginJob: Job? = null
    private var codePending: YandexAuth.Pending? = null

    /** Чтение состояния и запуск автосинхронизации; первая попытка — сразу (синхронизация при запуске). */
    fun start() {
        if (autoJob != null) return
        autoJob = scope.launch {
            refresh()
            while (isActive) {
                if (_state.value.isEnabled && _state.value.isSignedIn) runSync(automatic = true)
                var waited = 0L
                // Строка статуса пересчитывает «есть изменения» чаще, чем идёт синхронизация.
                while (waited < autoIntervalMillis && isActive) {
                    delay(STATUS_REFRESH_MS.coerceAtMost(autoIntervalMillis))
                    waited += STATUS_REFRESH_MS
                    refreshPending()
                }
            }
        }
    }

    /** Остановка фоновых задач перед закрытием базы. */
    fun stop() {
        autoJob?.cancel()
        autoJob = null
        loginJob?.cancel()
    }

    fun syncNow() {
        if (_state.value.isBusy) return
        if (!_state.value.isEnabled) {
            _state.update { it.copy(notice = "Синхронизация выключена — включите её в настройках") }
            return
        }
        scope.launch { runSync(automatic = false) }
    }

    /** Синхронизация перед закрытием: не дольше [timeoutMillis], чтобы окно не зависло без сети. */
    suspend fun syncBeforeExit(timeoutMillis: Long = EXIT_TIMEOUT_MS) {
        val current = _state.value
        if (!current.isEnabled || !current.isSignedIn) return
        stop()
        withTimeoutOrNull(timeoutMillis) { withContext(ioDispatcher) { engine.sync() } }
    }

    val isEnabled: Boolean get() = _state.value.isEnabled

    // ── Включение и проблемы ──────────────────────────────────────────────────────

    fun enable() = busy("Подключаемся к Яндекс.Диску…") {
        val outcome = withContext(ioDispatcher) { engine.enable() }
        handle(outcome, automatic = false)
        if (outcome is SyncOutcome.Done) {
            _state.update {
                it.copy(
                    notice = if (outcome.received > 0) {
                        "Синхронизация включена: данные загружены с Диска"
                    } else {
                        "Синхронизация включена: данные этого компьютера выгружены на Диск"
                    },
                )
            }
        }
    }

    fun disable() = busy("Отключаем…") {
        withContext(ioDispatcher) { engine.disable() }
        _state.update { it.copy(isEnabled = false) }
        _state.update { it.copy(problem = null, notice = "Синхронизация выключена. Данные остались на этом компьютере и на Диске") }
    }

    fun recreateRemote() = busy("Создаём хранилище на Диске заново…") {
        val outcome = withContext(ioDispatcher) { engine.recreateRemote() }
        handle(outcome, automatic = false)
        if (outcome is SyncOutcome.Done) {
            _state.update { it.copy(notice = "Хранилище на Диске создано заново. Другие компьютеры подключите к пустой базе") }
        }
    }

    // ── Аккаунт ───────────────────────────────────────────────────────────────────

    fun startEditClientId() = _state.update { it.copy(isEditingClientId = true, clientIdDraft = it.clientId) }

    fun updateClientIdDraft(value: String) = _state.update { it.copy(clientIdDraft = value) }

    fun cancelEditClientId() = _state.update { it.copy(isEditingClientId = false) }

    fun saveClientId() {
        val value = _state.value.clientIdDraft.trim()
        if (value.isEmpty()) {
            _state.update { it.copy(error = "Вставьте Client ID приложения из oauth.yandex.ru") }
            return
        }
        scope.launch {
            withContext(ioDispatcher) { account.saveClientId(value) }
            _state.update { it.copy(clientId = value, isEditingClientId = false) }
        }
    }

    fun signInWithBrowser() {
        if (loginJob?.isActive == true) return
        loginJob = scope.launch {
            _state.update { it.copy(isBusy = true, busyText = "Ждём вход в браузере…", error = null) }
            try {
                val token = withContext(ioDispatcher) { account.signInWithBrowser(openBrowser) }
                signedIn(token.login)
            } catch (e: YandexAuthException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(isBusy = false, busyText = null) }
            }
        }
    }

    fun startCodeLogin() {
        val pending = runCatching { account.startWithCode() }.getOrElse { error ->
            _state.update { it.copy(error = error.message) }
            return
        }
        codePending = pending
        _state.update { it.copy(codeLogin = CodeLoginState(url = pending.authorizeUrl), error = null) }
        runCatching { openBrowser(pending.authorizeUrl) }
    }

    fun updateCode(value: String) = _state.update { s -> s.copy(codeLogin = s.codeLogin?.copy(code = value)) }

    fun cancelCodeLogin() {
        codePending = null
        _state.update { it.copy(codeLogin = null) }
    }

    fun finishCodeLogin() {
        val pending = codePending ?: return
        val code = _state.value.codeLogin?.code.orEmpty()
        if (code.isBlank()) return
        scope.launch {
            _state.update { it.copy(isBusy = true, busyText = "Проверяем код…", error = null) }
            try {
                val token = withContext(ioDispatcher) { account.finishWithCode(pending, code) }
                codePending = null
                _state.update { it.copy(codeLogin = null) }
                signedIn(token.login)
            } catch (e: YandexAuthException) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(isBusy = false, busyText = null) }
            }
        }
    }

    fun signOut() {
        scope.launch {
            withContext(ioDispatcher) { account.signOut() }
            _state.update { it.copy(isSignedIn = false, login = null, notice = "Вы вышли из Яндекса на этом компьютере") }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ── Внутреннее ────────────────────────────────────────────────────────────────

    private suspend fun signedIn(login: String?) {
        _state.update { it.copy(isSignedIn = true, login = login, notice = "Вход выполнен${login?.let { l -> ": $l" }.orEmpty()}") }
        // Повторный вход после «Войдите снова» — сразу и синхронизируем.
        if (_state.value.isEnabled) {
            _state.update { it.copy(problem = null) }
            runSync(automatic = false)
        }
    }

    private suspend fun refresh() {
        val snapshot = withContext(ioDispatcher) {
            Loaded(
                clientId = account.clientId(),
                token = account.token(),
                enabled = engine.isEnabled,
                last = engine.lastSyncAtUtc,
                pending = engine.pendingCount(),
            )
        }
        _state.update {
            it.copy(
                isLoaded = true,
                clientId = snapshot.clientId,
                isEditingClientId = snapshot.clientId.isEmpty(),
                isSignedIn = snapshot.token != null,
                login = snapshot.token?.login,
                isEnabled = snapshot.enabled,
                lastSyncAtUtc = snapshot.last,
                pending = snapshot.pending,
            )
        }
    }

    private suspend fun refreshPending() {
        if (!_state.value.isEnabled) return
        val pending = runCatching { withContext(ioDispatcher) { engine.pendingCount() } }.getOrNull() ?: return
        _state.update { it.copy(pending = pending) }
    }

    private suspend fun runSync(automatic: Boolean) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, busyText = "Синхронизация…") }
        try {
            val outcome = withContext(ioDispatcher) { engine.sync() }
            handle(outcome, automatic)
        } finally {
            _state.update { it.copy(isBusy = false, busyText = null) }
        }
    }

    private suspend fun handle(outcome: SyncOutcome, automatic: Boolean) {
        val (enabled, last, pending) = withContext(ioDispatcher) {
            Triple(engine.isEnabled, engine.lastSyncAtUtc, engine.pendingCount())
        }
        _state.update { it.copy(isEnabled = enabled, lastSyncAtUtc = last, pending = pending) }
        when (outcome) {
            is SyncOutcome.Done -> {
                _state.update { it.copy(problem = null) }
                if (outcome.received > 0) onReceived()
                if (!automatic && outcome.received + outcome.sent == 0) {
                    _state.update { it.copy(notice = "Синхронизировано: изменений нет") }
                }
            }
            SyncOutcome.NotEnabled -> Unit
            is SyncOutcome.Failed -> {
                val problem = outcome.problem
                // Сеть пропала — штатная ситуация офлайна: показываем статус, без всплывающей ошибки.
                val quiet = automatic && problem is SyncProblem.Transport &&
                    problem.kind in setOf(SyncTransportException.Kind.NETWORK, SyncTransportException.Kind.RATE_LIMIT)
                _state.update {
                    it.copy(
                        problem = problem,
                        isSignedIn = if (problem is SyncProblem.Transport && problem.kind == SyncTransportException.Kind.AUTH) false else it.isSignedIn,
                        error = if (quiet || problem !is SyncProblem.Transport) it.error else problem.message,
                    )
                }
            }
        }
    }

    private fun busy(text: String, block: suspend () -> Unit) {
        if (_state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(isBusy = true, busyText = text, error = null) }
            try {
                block()
            } finally {
                _state.update { it.copy(isBusy = false, busyText = null) }
            }
        }
    }

    private data class Loaded(
        val clientId: String,
        val token: app.finance.core.network.YandexToken?,
        val enabled: Boolean,
        val last: Long?,
        val pending: Int,
    )

    companion object {
        const val AUTO_INTERVAL_MS = 10 * 60_000L
        private const val STATUS_REFRESH_MS = 30_000L
        private const val EXIT_TIMEOUT_MS = 20_000L

        fun browse(url: String) {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url))
            } else {
                error("Не удалось открыть браузер. Откройте адрес вручную: $url")
            }
        }
    }
}
