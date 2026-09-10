package app.finance.core.domain

import app.finance.core.model.AssetKind
import app.finance.core.model.Money
import app.finance.core.model.Period
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Ввод остатка счёта за месяц (SPEC.md F4.3): пара `(счёт, месяц)` уникальна,
 * поэтому повторный ввод — это редактирование, а не вторая строка.
 */
class RecordSnapshotTest {

    private val fx = DomainFixture()
    private val jan = Period(2026, 1)
    private val feb = Period(2026, 2)
    private val mar = Period(2026, 3)

    private fun input(
        accountId: String,
        period: Period = feb,
        balance: Money = Money(100_000),
        contribution: Money? = null,
        note: String? = null,
    ) = SnapshotInput(
        accountId = accountId,
        period = period,
        balance = balance,
        contribution = contribution,
        note = note,
    )

    @Test
    fun `первый ввод создаёт снапшот`() = runTest {
        val account = fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT)

        val result = fx.recordSnapshot(input(account.id, balance = Money(250_000), note = "  пополнение  "))

        val created = assertIs<SnapshotResult.Created>(result)
        assertEquals(Money(250_000), created.snapshot.balance)
        assertEquals(feb, created.snapshot.period)
        assertEquals(account.id, created.snapshot.accountId)
        assertEquals(1, created.snapshot.rowVersion)
        assertEquals("пополнение", created.snapshot.note, "Заметка обрезается по краям")
        assertEquals(1, fx.assets.snapshotsById.size)
        assertEquals(1, fx.transactions.transactionsStarted)
    }

    @Test
    fun `повторный ввод за тот же месяц правит существующий снапшот`() = runTest {
        val account = fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT)
        val existing = fx.snapshot(account.id, feb, balanceMinor = 200_000)

        val result = fx.recordSnapshot(input(account.id, balance = Money(240_000)))

        val updated = assertIs<SnapshotResult.Updated>(result)
        assertEquals(Money(200_000), updated.balanceBefore)
        assertEquals(Money(240_000), updated.snapshot.balance)
        assertEquals(existing.id, updated.snapshot.id, "Правится та же строка, вторая не создаётся")
        assertEquals(existing.rowVersion + 1, updated.snapshot.rowVersion)
        assertEquals(1, fx.assets.snapshotsById.size)
    }

    @Test
    fun `ввод за другой месяц создаёт отдельный снапшот`() = runTest {
        val account = fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT)
        fx.snapshot(account.id, feb, balanceMinor = 200_000)

        val result = fx.recordSnapshot(input(account.id, period = mar, balance = Money(210_000)))

        assertIs<SnapshotResult.Created>(result)
        assertEquals(2, fx.assets.snapshotsById.size)
        assertEquals(
            listOf(feb, mar),
            fx.assets.snapshotsByAccount(account.id).map { it.period },
        )
    }

    @Test
    fun `нулевой баланс допустим`() = runTest {
        val account = fx.account("cash", "Наличные", kind = AssetKind.CASH)

        val result = fx.recordSnapshot(input(account.id, balance = Money.ZERO))

        assertEquals(Money.ZERO, assertIs<SnapshotResult.Created>(result).snapshot.balance)
    }

    @Test
    fun `взнос сохраняется вместе с балансом`() = runTest {
        val account = fx.account("broker", "Брокер", kind = AssetKind.BROKERAGE)

        val result = fx.recordSnapshot(
            input(account.id, balance = Money(500_000), contribution = Money(150_000)),
        )

        assertEquals(Money(150_000), result.snapshot.contribution)
    }

    @Test
    fun `отрицательный баланс разрешён только для кредита`() = runTest {
        val deposit = fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT)
        val credit = fx.account("credit", "Кредитка", kind = AssetKind.CREDIT)

        val error = assertFailsWith<IllegalArgumentException> {
            fx.recordSnapshot(input(deposit.id, balance = Money(-1_000)))
        }
        assertTrue(error.message!!.contains("Отрицательный баланс"), "Сообщение: ${error.message}")

        val accepted = fx.recordSnapshot(input(credit.id, balance = Money(-75_000)))
        assertEquals(Money(-75_000), accepted.snapshot.balance)
    }

    @Test
    fun `неизвестный счёт отклоняется`() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            fx.recordSnapshot(input("no-such-account"))
        }
        assertTrue(error.message!!.contains("не найден"), "Сообщение: ${error.message}")
        assertTrue(fx.assets.snapshotsById.isEmpty())
    }

    @Test
    fun `период до открытия счёта отклоняется`() = runTest {
        val account = fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT, openedPeriod = feb)

        val error = assertFailsWith<IllegalArgumentException> {
            fx.recordSnapshot(input(account.id, period = jan))
        }
        assertTrue(error.message!!.contains("не активен"), "Сообщение: ${error.message}")
    }

    @Test
    fun `период после закрытия счёта отклоняется`() = runTest {
        val account = fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT, closedPeriod = feb)

        val error = assertFailsWith<IllegalArgumentException> {
            fx.recordSnapshot(input(account.id, period = mar))
        }
        assertTrue(error.message!!.contains("не активен"), "Сообщение: ${error.message}")
        assertTrue(error.message!!.contains("закрыт"), "Сообщение: ${error.message}")

        // Последний месяц жизни счёта остаётся доступным для ввода.
        val accepted = fx.recordSnapshot(input(account.id, period = feb, balance = Money(10_000)))
        assertEquals(feb, accepted.snapshot.period)
    }

    @Test
    fun `валюта баланса должна совпадать с валютой счёта`() = runTest {
        val account = fx.account("usd", "Брокер USD", kind = AssetKind.BROKERAGE, currency = "USD")

        val error = assertFailsWith<IllegalArgumentException> {
            fx.recordSnapshot(input(account.id, balance = Money(100_000, "RUB")))
        }
        assertTrue(error.message!!.contains("ведётся в USD"), "Сообщение: ${error.message}")
    }

    @Test
    fun `валюта взноса должна совпадать с валютой счёта`() = runTest {
        val account = fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT)

        val error = assertFailsWith<IllegalArgumentException> {
            fx.recordSnapshot(input(account.id, balance = Money(100_000), contribution = Money(5_000, "USD")))
        }
        assertTrue(error.message!!.contains("Взнос указан в USD"), "Сообщение: ${error.message}")
    }

    @Test
    fun `пустая заметка не сохраняется`() = runTest {
        val account = fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT)

        val created = fx.recordSnapshot(input(account.id, note = "   "))
        assertNull(created.snapshot.note)

        val updated = fx.recordSnapshot(input(account.id, balance = Money(120_000), note = ""))
        assertNull(updated.snapshot.note)
        assertEquals(1, fx.assets.snapshotsById.size)
    }

    @Test
    fun `правка без взноса сбрасывает прежний взнос`() = runTest {
        // Взнос относится к конкретному вводу: если пользователь его не повторил,
        // считать его занесённым повторно нельзя — иначе прирост будет занижен.
        val account = fx.account("broker", "Брокер", kind = AssetKind.BROKERAGE)
        fx.snapshot(account.id, feb, balanceMinor = 100_000, contributionMinor = 40_000)

        val result = fx.recordSnapshot(input(account.id, balance = Money(110_000)))

        assertNull(result.snapshot.contribution)
    }
}
