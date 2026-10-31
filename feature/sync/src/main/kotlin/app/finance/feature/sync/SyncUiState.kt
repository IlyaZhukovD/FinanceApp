package app.finance.feature.sync

import app.finance.core.sync.SyncProblem
import app.finance.core.sync.SyncTransportException
import app.finance.core.ui.StatusTone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Состояние синхронизации для карточки в настройках и строки статуса в боковой панели. */
data class SyncUiState(
    val isLoaded: Boolean = false,
    val clientId: String = "",
    val clientIdDraft: String = "",
    val isEditingClientId: Boolean = false,
    val login: String? = null,
    val isSignedIn: Boolean = false,
    val isEnabled: Boolean = false,
    val isBusy: Boolean = false,
    val busyText: String? = null,
    val lastSyncAtUtc: Long? = null,
    val pending: Int = 0,
    val problem: SyncProblem? = null,
    val error: String? = null,
    val notice: String? = null,
    val codeLogin: CodeLoginState? = null,
    /** Папка на Диске: `Приложения/Finance` или `Приложения/Finance/dev` у сборки для разработки. */
    val folderLabel: String = "Приложения/Finance",
    val isDevBuild: Boolean = false,
)

/** Вход кодом: страница Яндекса открыта, ждём код от пользователя. */
data class CodeLoginState(val url: String, val code: String = "")

/** Текст и цвет строки статуса в боковой панели (SPEC.md F7.9). */
fun syncStatusLine(state: SyncUiState, nowUtc: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<String, StatusTone> = when {
    !state.isEnabled -> "Данные хранятся локально на этом компьютере" to StatusTone.OK
    state.isBusy -> "Синхронизация…" to StatusTone.OK
    state.problem != null -> problemShort(state.problem) to StatusTone.ERROR
    !state.isSignedIn -> "Войдите в Яндекс для синхронизации" to StatusTone.WARNING
    state.pending > 0 -> {
        val last = state.lastSyncAtUtc?.let { " · синхр. ${relativeTime(it, nowUtc, zone)}" }.orEmpty()
        "Есть изменения$last" to StatusTone.WARNING
    }
    state.lastSyncAtUtc != null -> "Синхронизировано ${relativeTime(state.lastSyncAtUtc, nowUtc, zone)}" to StatusTone.OK
    else -> "Синхронизация включена" to StatusTone.OK
}

/** «только что», «5 мин назад», «3 ч назад», иначе дата и время. */
fun relativeTime(atUtc: Long, nowUtc: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val minutes = (nowUtc - atUtc).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        minutes < 24 * 60 -> "${minutes / 60} ч назад"
        else -> DATE_TIME.withZone(zone).format(Instant.ofEpochMilli(atUtc))
    }
}

private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

private fun problemShort(problem: SyncProblem): String = when (problem) {
    is SyncProblem.NeedsUpdate -> "Обновите Finance для синхронизации"
    is SyncProblem.Transport -> when (problem.kind) {
        SyncTransportException.Kind.NETWORK -> "Нет связи с Яндекс.Диском"
        SyncTransportException.Kind.AUTH -> "Войдите в Яндекс снова"
        else -> "Ошибка синхронизации"
    }
    else -> "Синхронизация остановлена"
}

/** Подробное объяснение проблемы для карточки в настройках. */
fun problemText(problem: SyncProblem): String = when (problem) {
    SyncProblem.RemoteMissing ->
        "Папка синхронизации на Яндекс.Диске пропала. Можно создать её заново с данными этого компьютера — " +
            "остальным компьютерам тогда придётся подключиться заново."
    SyncProblem.RemoteReplaced ->
        "Хранилище на Диске создано заново с другого компьютера, и эта база с ним больше не связана. " +
            "Отключите синхронизацию здесь и подключите пустую базу (Настройки → База данных → «Добавить базу…»)."
    is SyncProblem.RemoteCorrupt ->
        "Файлы синхронизации на Диске повреждены (${problem.details}). Можно пересоздать хранилище с данными " +
            "этого компьютера — остальным придётся подключиться заново."
    is SyncProblem.NeedsUpdate ->
        "На компьютере «${problem.deviceName}» стоит более новая версия Finance. Обновите приложение здесь — " +
            "до этого синхронизация приостановлена, данные не потеряются."
    SyncProblem.LocalNotEmpty ->
        "На Диске уже есть данные с другого компьютера, а в этой базе тоже есть записи. Подключать можно только " +
            "пустую базу: добавьте новую в «База данных» и включите синхронизацию в ней."
    is SyncProblem.Transport -> problem.message
}
