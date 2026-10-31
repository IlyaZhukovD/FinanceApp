package app.finance

import app.finance.core.data.BackupEntry
import app.finance.core.data.BackupResult
import app.finance.core.data.BackupTrigger
import app.finance.core.data.LocalBackupService
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Создание графов и восстановление базы из бэкапа (SPEC.md F7.7).
 *
 * Восстановление требует закрыть базу: граф, из которого его запросили, закрывается,
 * файл подменяется, и открывается новый граф на том же пути — без перезапуска приложения,
 * тем же механизмом, что и переключение баз в настройках.
 *
 * @param onReplaced хост подставляет новый граф в состояние окна.
 */
class GraphHost(
    private val scope: CoroutineScope,
    private val onReplaced: (AppGraph) -> Unit,
) {
    suspend fun create(path: Path): AppGraph = AppGraph.create(
        scope = scope,
        ioDispatcher = Dispatchers.IO,
        databasePath = path,
        restore = ::restore,
    )

    /** @return null — успешно; иначе причина отказа (текущая база не тронута или возвращена). */
    private suspend fun restore(current: AppGraph, entry: BackupEntry): String? {
        val path = current.databasePath
        val staged = try {
            withContext(Dispatchers.IO) {
                // Текущее состояние — отдельным бэкапом: восстановление можно отменить им же.
                val before = current.backupService.backup(BackupTrigger.PRE_RESTORE, force = true)
                if (before is BackupResult.Failed) return@withContext null to "не удалось сохранить текущую базу: ${before.message}"
                current.backupService.prepareRestore(entry, path) to null
            }
        } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        }
        staged.second?.let { return it }

        current.close()
        val swapError = runCatching { withContext(Dispatchers.IO) { LocalBackupService.swapIn(staged.first!!, path) } }
            .exceptionOrNull()
        // Открываем в любом случае: либо восстановленную базу, либо возвращённую из карантина.
        val reopened = create(path)
        if (swapError == null) reopened.afterRestore()
        onReplaced(reopened)
        println("Finance: база ${if (swapError == null) "восстановлена из ${entry.fileName}" else "не подменена"} — $path")
        return swapError?.let { it.message ?: it.javaClass.simpleName }
    }
}
