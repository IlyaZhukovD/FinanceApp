package app.finance.feature.sync

import app.finance.core.sync.SyncProblem
import app.finance.core.sync.SyncTransportException
import app.finance.core.ui.StatusTone
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncStatusTest {
    private val now = 1_800_000_000_000L
    private val on = SyncUiState(isLoaded = true, isEnabled = true, isSignedIn = true)

    @Test
    fun `строка статуса`() {
        assertEquals("Данные хранятся локально на этом компьютере" to StatusTone.OK, syncStatusLine(SyncUiState(), now))
        assertEquals("Синхронизировано только что" to StatusTone.OK, syncStatusLine(on.copy(lastSyncAtUtc = now - 10_000), now))
        assertEquals(
            "Есть изменения · синхр. 12 мин назад" to StatusTone.WARNING,
            syncStatusLine(on.copy(lastSyncAtUtc = now - 12 * 60_000, pending = 3), now),
        )
        assertEquals(StatusTone.ERROR, syncStatusLine(on.copy(problem = SyncProblem.RemoteMissing), now).second)
        assertEquals(
            "Нет связи с Яндекс.Диском",
            syncStatusLine(on.copy(problem = SyncProblem.Transport(SyncTransportException.Kind.NETWORK, "x")), now).first,
        )
        assertEquals("Синхронизация…", syncStatusLine(on.copy(isBusy = true, pending = 2), now).first)
    }

    @Test
    fun `относительное время`() {
        assertEquals("3 ч назад", relativeTime(now - 3 * 3_600_000 - 5, now))
        assertEquals("15.01 08:00", relativeTime(1_768_464_000_000L, 1_768_464_000_000L + 2 * 86_400_000, ZoneOffset.UTC))
    }
}
