package app.finance.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.finance.core.data.BackupEntry
import app.finance.core.ui.FinanceCard
import app.finance.core.ui.FinancePalette
import app.finance.core.ui.FinanceSpace
import app.finance.core.ui.MutedColor
import app.finance.core.ui.NoticeBar
import app.finance.core.ui.PrimaryButton
import app.finance.core.ui.QuietButton
import app.finance.core.ui.SecondaryButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Карточка «Резервные копии» (SPEC.md F7.8): журнал, бэкап вручную, восстановление. */
@Composable
fun BackupCard(controller: BackupController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    LaunchedEffect(controller) { controller.start() }

    FinanceCard(
        modifier = modifier,
        title = "Резервные копии",
        subtitle = "Копии базы на этом компьютере: при закрытии приложения и каждые 15 минут, если данные менялись",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FinanceSpace.Xs)) {
            state.error?.let { NoticeBar(text = it, onDismiss = controller::dismissError, error = true) }
            state.notice?.let { NoticeBar(text = it, onDismiss = controller::dismissNotice) }
            Text(text = "Папка: ${state.folder}", style = MaterialTheme.typography.bodySmall, color = MutedColor)
            Row(horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm), modifier = Modifier.padding(vertical = FinanceSpace.Xs)) {
                PrimaryButton(text = "Сделать бэкап", onClick = controller::backupNow, enabled = !state.isBusy)
                SecondaryButton(text = "Открыть папку", onClick = controller::openFolder)
            }
            if (state.journal.isEmpty()) {
                Text(text = "Бэкапов пока нет", style = MaterialTheme.typography.bodySmall, color = MutedColor)
            }
            state.journal.take(JOURNAL_ROWS).forEach { entry ->
                JournalRow(entry = entry, enabled = !state.isBusy, onRestore = { controller.askRestore(entry) })
            }
        }
    }

    state.confirmRestore?.let { entry ->
        AlertDialog(
            onDismissRequest = controller::cancelRestore,
            title = { Text("Восстановить базу?") },
            text = {
                Text(
                    "База будет заменена копией от ${formatTime(entry.createdAtUtc)}. Перед заменой текущее состояние " +
                        "сохраняется отдельным бэкапом — восстановление можно отменить, восстановив его. " +
                        "Если включена синхронизация, изменения, уже выложенные на Яндекс.Диск, вернутся при следующей синхронизации.",
                )
            },
            confirmButton = { PrimaryButton(text = "Восстановить", onClick = controller::confirmRestore) },
            dismissButton = { QuietButton(text = "Отмена", onClick = controller::cancelRestore) },
        )
    }
}

@Composable
private fun JournalRow(entry: BackupEntry, enabled: Boolean, onRestore: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FinanceSpace.Sm)) {
        Text(text = formatTime(entry.createdAtUtc), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(TIME_WIDTH))
        Text(text = triggerLabel(entry.trigger), style = MaterialTheme.typography.bodySmall, color = MutedColor, modifier = Modifier.width(TRIGGER_WIDTH))
        if (entry.isOk) {
            Text(
                text = "${entry.sizeBytes / 1024} КБ · ${entry.sha256.take(8)}",
                style = MaterialTheme.typography.bodySmall,
                color = MutedColor,
                modifier = Modifier.weight(1f),
            )
            QuietButton(text = "Восстановить", onClick = onRestore, enabled = enabled)
        } else {
            Text(
                text = "Ошибка: ${entry.error.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = FinancePalette.Error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun triggerLabel(trigger: String) = when (trigger) {
    "APP_CLOSE" -> "при закрытии"
    "TIMER" -> "по таймеру"
    "MANUAL" -> "вручную"
    "PRE_RESTORE" -> "перед восстановлением"
    else -> trigger
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

private fun formatTime(at: Long): String = TIME_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))

private const val JOURNAL_ROWS = 15
private val TIME_WIDTH = 130.dp
private val TRIGGER_WIDTH = 170.dp
