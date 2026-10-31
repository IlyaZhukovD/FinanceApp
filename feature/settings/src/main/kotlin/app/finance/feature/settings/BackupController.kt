package app.finance.feature.settings

import app.finance.core.data.BackupEntry
import app.finance.core.data.BackupResult
import app.finance.core.data.BackupTrigger
import app.finance.core.data.LocalBackupService
import java.awt.Desktop
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

data class BackupUiState(
    val journal: List<BackupEntry> = emptyList(),
    val folder: String = "",
    val isBusy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    /** Снапшот, восстановление из которого ждёт подтверждения. */
    val confirmRestore: BackupEntry? = null,
)

/**
 * Локальные бэкапы (SPEC.md F7, M6; D-30): каталог `backups/` рядом с базой.
 *
 * Автоматически — при закрытии приложения и по таймеру каждые [intervalMillis], пока оно
 * открыто; если данные не менялись, новый файл не создаётся. Ошибка бэкапа не мешает работе.
 *
 * @param restore хост закрывает базу, подменяет файл и открывает её снова; true — успешно.
 */
class BackupController(
    private val service: LocalBackupService,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val restore: suspend (BackupEntry) -> String?,
    private val intervalMillis: Long = INTERVAL_MS,
) {
    private val _state = MutableStateFlow(BackupUiState(folder = service.directory.toString()))
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private var timer: Job? = null

    fun start() {
        if (timer != null) return
        timer = scope.launch {
            reload()
            while (isActive) {
                delay(intervalMillis)
                val result = runCatching { withContext(ioDispatcher) { service.backup(BackupTrigger.TIMER) } }.getOrNull()
                if (result is BackupResult.Created || result is BackupResult.Failed) reload()
            }
        }
    }

    fun stop() {
        timer?.cancel()
        timer = null
    }

    /** Бэкап при закрытии приложения: синхронно на IO, без всплывающих сообщений. */
    suspend fun backupBeforeExit() {
        stop()
        runCatching { withContext(ioDispatcher) { service.backup(BackupTrigger.APP_CLOSE) } }
    }

    fun backupNow() {
        if (_state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null, notice = null) }
            val result = withContext(ioDispatcher) { service.backup(BackupTrigger.MANUAL) }
            _state.update {
                when (result) {
                    is BackupResult.Created -> it.copy(notice = "Бэкап сохранён: ${result.entry.fileName}")
                    BackupResult.Unchanged -> it.copy(notice = "Данные не менялись — последний бэкап актуален")
                    is BackupResult.Failed -> it.copy(error = "Не удалось сделать бэкап: ${result.message}")
                }
            }
            reload()
            _state.update { it.copy(isBusy = false) }
        }
    }

    fun openFolder() {
        runCatching {
            java.nio.file.Files.createDirectories(service.directory)
            Desktop.getDesktop().open(service.directory.toFile())
        }.onFailure { e -> _state.update { it.copy(error = "Не удалось открыть папку: ${e.message}") } }
    }

    fun askRestore(entry: BackupEntry) = _state.update { it.copy(confirmRestore = entry) }

    fun cancelRestore() = _state.update { it.copy(confirmRestore = null) }

    fun confirmRestore() {
        val entry = _state.value.confirmRestore ?: return
        _state.update { it.copy(confirmRestore = null, isBusy = true, error = null) }
        scope.launch {
            val error = runCatching { restore(entry) }.getOrElse { it.message ?: "ошибка" }
            // При успехе хост уже открыл восстановленную базу с новым контроллером; этот
            // показывает ошибку, только если подмена не удалась.
            _state.update { it.copy(isBusy = false, error = error?.let { e -> "Восстановление не выполнено: $e" }) }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private suspend fun reload() {
        val journal = withContext(ioDispatcher) { service.journal() }
        _state.update { it.copy(journal = journal) }
    }

    companion object {
        const val INTERVAL_MS = 15 * 60_000L
    }
}
