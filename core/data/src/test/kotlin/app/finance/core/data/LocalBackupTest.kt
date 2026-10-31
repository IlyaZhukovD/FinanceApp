package app.finance.core.data

import app.finance.core.domain.RecordAmount
import app.finance.core.domain.AmountInput
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalBackupTest {

    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `ротация 7 дней, 4 недели, 6 месяцев, 3 года и минимум 3`() {
        // Каждый день по два бэкапа за 2 года.
        val start = LocalDateTime.of(2024, 11, 1, 9, 0)
        val all = (0 until 730).flatMap { day ->
            listOf(9, 18).map { h -> start.plusDays(day.toLong()).withHour(h) }
        }.map { "b-$it" to it.toInstant(ZoneOffset.UTC).toEpochMilli() }
        val keep = BackupRetention.keep(all, ZoneOffset.UTC)
        val newest = all.maxBy { it.second }
        assertTrue(newest.first in keep)
        // 7 дней + до 4 недель + до 6 месяцев + до 3 лет, многие совпадают.
        assertTrue(keep.size in 7..20, "оставлено ${keep.size}")
        // Самый старый год представлен одним бэкапом — последним в том году.
        assertTrue("b-2024-12-31T18:00" in keep)
        assertTrue(all.none { it.first == "b-2024-12-31T09:00" && it.first in keep })

        assertEquals(setOf("a", "b"), BackupRetention.keep(listOf("a" to at(2026, 1, 1), "b" to at(2026, 1, 1, 13)), ZoneOffset.UTC))
    }

    @Test
    fun `бэкап, пропуск без изменений, восстановление`() = runTest {
        val dir = createTempDirectory("finance-backup")
        val dbPath = dir.resolve("finance.db")
        val time = FixedTime(at(2026, 10, 31))
        val session = DatabaseFactory.openFile(dbPath)
        val ids = RandomUuidGenerator()
        val categories = SqlCategoryRepository(session.database, ids)
        val ledger = SqlLedgerRepository(session.database, ids)
        CategorySeeder(categories, ids, time).seedIfEmpty()
        val record = RecordAmount(categories, ledger, ids, time, SqlDelightTransactionRunner(session.database))
        val food = categories.bySection(app.finance.core.model.CategorySection.EXPENSE).first { it.name == "Продукты" }
        val jan = Period(2026, 1)
        record(AmountInput(LedgerSection.EXPENSE, food.id, jan, Money.ofUnits(100), null))

        val backups = LocalBackupService(session.driver, dir.resolve("backups"), time, "1.0.0", ZoneOffset.UTC)
        val first = assertIs<BackupResult.Created>(backups.backup(BackupTrigger.MANUAL))
        assertTrue(Files.exists(dir.resolve("backups").resolve(first.entry.fileName + ".sha256")))
        assertTrue(Files.readString(dir.resolve("backups").resolve(LocalBackupService.MANIFEST)).contains(first.entry.sha256))

        time.now += 60_000
        assertEquals(BackupResult.Unchanged, backups.backup(BackupTrigger.TIMER))

        record(AmountInput(LedgerSection.EXPENSE, food.id, jan, Money.ofUnits(50), null))
        time.now += 60_000
        assertIs<BackupResult.Created>(backups.backup(BackupTrigger.APP_CLOSE))
        assertEquals(2, backups.restorable().size)

        // Восстановление первого снапшота: база закрыта, файл подменён.
        val staged = backups.prepareRestore(first.entry, dbPath)
        session.close()
        LocalBackupService.swapIn(staged, dbPath)
        DatabaseFactory.openFile(dbPath).use { reopened ->
            val cell = SqlLedgerRepository(reopened.database, ids).findCell(food.id, jan)
            assertEquals(Money.ofUnits(100), cell?.amount)
        }
        assertTrue(Files.exists(dir.resolve("finance.db.before-restore")))

        // Испорченный снапшот не восстанавливается.
        Files.writeString(dir.resolve("backups").resolve(first.entry.fileName), "мусор")
        assertFailsWith<BackupRestoreException> { backups.prepareRestore(first.entry, dbPath) }
    }
}
