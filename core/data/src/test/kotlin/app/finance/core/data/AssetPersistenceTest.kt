package app.finance.core.data

import app.finance.core.domain.AccountInput
import app.finance.core.domain.AssetSummary
import app.finance.core.domain.CloseAccount
import app.finance.core.domain.CreateAccount
import app.finance.core.domain.RecordSnapshot
import app.finance.core.domain.SnapshotInput
import app.finance.core.domain.SnapshotResult
import app.finance.core.model.AssetAccount
import app.finance.core.model.AssetBalancePolicy
import app.finance.core.model.AssetSnapshot
import app.finance.core.model.Category
import app.finance.core.model.CategorySection
import app.finance.core.model.DepositCapitalization
import app.finance.core.model.Money
import app.finance.core.model.Period
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Интеграционные тесты активов на настоящей SQLite: доменные use-case'ы поверх
 * `SqlAssetRepository`.
 *
 * Проверяют то, что фейки проверить не могут: частичный уникальный индекс
 * `uq_asset_snapshot_active`, внешние ключи на `asset_account` и `category`,
 * хранение enum текстом и запись мутаций в `sync_outbox` (SPEC.md §7).
 */
class AssetPersistenceTest {

    private val session = DatabaseFactory.openInMemory()
    private val db = session.database
    private val time = FixedTime()
    private val ids = SeqIds()
    private val transactions = SqlDelightTransactionRunner(db)
    private val categories = SqlCategoryRepository(db)
    private val assets = SqlAssetRepository(db, ids)

    private val createAccount = CreateAccount(categories, assets, ids, time, transactions)
    private val closeAccount = CloseAccount(assets, time, transactions)
    private val recordSnapshot = RecordSnapshot(assets, categories, ids, time, transactions)
    private val summary = AssetSummary(assets, categories)

    private val jan = Period(2026, 1)
    private val feb = Period(2026, 2)

    @AfterTest
    fun tearDown() = session.close()

    private suspend fun seedAssetCategory(
        id: String = "banks",
        name: String = "Банки",
        allowsNegativeBalance: Boolean = false,
    ) {
        categories.create(
            Category(
                id = id,
                section = CategorySection.ASSET,
                name = name,
                color = "#4E79A7",
                // Категория вкладов: ставка известна, поэтому счёт может хранить
                // годовую ставку (D-20).
                balancePolicy = AssetBalancePolicy.FIXED_RATE,
                allowsNegativeBalance = allowsNegativeBalance,
                createdAtUtc = time.now,
                updatedAtUtc = time.now,
            ),
        )
    }

    private suspend fun newAccount(
        name: String = "Вклад",
        categoryId: String = "banks",
        currency: String = Money.DEFAULT_CURRENCY,
        annualRateBp: Int? = null,
        note: String? = null,
    ): AssetAccount {
        // Категория одна на тест: повторный insert нарушил бы PRIMARY KEY.
        if (categories.byId("banks") == null) seedAssetCategory()
        return createAccount(
            AccountInput(
                categoryId = categoryId,
                name = name,
                currency = currency,
                openedPeriod = jan,
                annualRateBp = annualRateBp,
                note = note,
            ),
        )
    }

    @Test
    fun `счёт сохраняется и читается обратно со всеми полями`() = runTest {
        val created = newAccount(
            name = "Вклад Т—Ж",
            annualRateBp = 850,
            note = "до 2027 года",
        )

        val stored = assets.accountById(created.id)

        assertEquals(created.id, stored?.id)
        assertEquals("Вклад Т—Ж", stored?.name)
        assertEquals(Money.DEFAULT_CURRENCY, stored?.currency)
        assertEquals(jan, stored?.openedPeriod)
        assertNull(stored?.closedPeriod)
        assertEquals(850, stored?.annualRateBp)
        assertEquals("до 2027 года", stored?.note)
        assertEquals(1, stored?.rowVersion)
        assertTrue(stored?.isOpen == true)
        assertEquals(listOf(created.id), assets.accounts(includeClosed = false).map { it.id })
    }

    @Test
    fun `день начисления и выплата процентов в конце срока сохраняются`() = runTest {
        seedAssetCategory()
        val account = AssetAccount(
            id = "yandex",
            categoryId = "banks",
            name = "Сейв",
            openedPeriod = jan,
            annualRateBp = 1400,
            termMonths = 3,
            interestDay = 8,
            capitalization = DepositCapitalization.AT_MATURITY,
            monthlyReminder = true,
            createdAtUtc = 1,
            updatedAtUtc = 1,
        )
        assets.insertAccount(account)

        val stored = assets.accountById("yandex")
        assertEquals(8, stored?.interestDay)
        assertEquals(DepositCapitalization.AT_MATURITY, stored?.capitalization)
        assertEquals(true, stored?.monthlyReminder)

        assets.updateAccount(account.copy(capitalization = DepositCapitalization.MONTHLY, updatedAtUtc = 2))
        assertEquals(DepositCapitalization.MONTHLY, assets.accountById("yandex")?.capitalization)
    }

    @Test
    fun `валюта снапшота берётся из счёта`() = runTest {
        // В asset_snapshot нет колонки currency: валюта остатка равна валюте счёта.
        val account = newAccount(name = "Брокер USD", currency = "USD")

        recordSnapshot(SnapshotInput(accountId = account.id, period = feb, balance = Money(2_500, "USD")))
        val stored = assets.findSnapshot(account.id, feb)

        assertEquals(Money(2_500, "USD"), stored?.balance)
        assertEquals("USD", stored?.balance?.currency)
        assertEquals(listOf("USD"), assets.snapshotsForPeriod(feb).map { it.balance.currency })
    }

    @Test
    fun `повторный ввод остатка за месяц правит строку, а не создаёт вторую`() = runTest {
        val account = newAccount()

        val first = recordSnapshot(SnapshotInput(account.id, feb, Money(100_000)))
        val second = recordSnapshot(SnapshotInput(account.id, feb, Money(140_000), note = "уточнил"))

        assertIs<SnapshotResult.Created>(first)
        val updated = assertIs<SnapshotResult.Updated>(second)
        assertEquals(Money(100_000), updated.balanceBefore)
        assertEquals(first.snapshot.id, updated.snapshot.id, "Правится та же строка")
        assertEquals(2, updated.snapshot.rowVersion)
        assertEquals(1, assets.snapshotsForPeriod(feb).size, "Второй строки за месяц не появилось")
        assertEquals("уточнил", assets.findSnapshot(account.id, feb)?.note)
    }

    @Test
    fun `частичный уникальный индекс отклоняет второй живой снапшот за месяц`() = runTest {
        val account = newAccount()
        val stored = requireNotNull(recordSnapshot(SnapshotInput(account.id, feb, Money(100_000))).snapshot)

        val duplicate = AssetSnapshot(
            id = "another-id",
            accountId = account.id,
            period = feb,
            balance = Money(200_000),
            createdAtUtc = time.now,
            updatedAtUtc = time.now,
        )

        val error = assertFailsWith<Exception> { assets.insertSnapshot(duplicate) }
        assertTrue(
            error.message.orEmpty().contains("UNIQUE", ignoreCase = true),
            "Ожидали нарушение уникальности, получили: ${error.message}",
        )
        assertEquals(Money(100_000), assets.findSnapshot(account.id, feb)?.balance, "Прежний остаток не изменён")
        assertEquals(1, assets.snapshotsByAccount(stored.accountId).size)
    }

    @Test
    fun `снапшот без счёта отклоняется внешним ключом`() = runTest {
        val orphan = AssetSnapshot(
            id = "orphan",
            accountId = "несуществующий-счёт",
            period = feb,
            balance = Money(100_000),
            createdAtUtc = time.now,
            updatedAtUtc = time.now,
        )

        val error = assertFailsWith<Exception> { assets.insertSnapshot(orphan) }
        assertTrue(
            error.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true),
            "Ожидали нарушение внешнего ключа, получили: ${error.message}",
        )
    }

    @Test
    fun `закрытие счёта сохраняется и исключает счёт из активных`() = runTest {
        val account = newAccount(name = "Наличные")
        recordSnapshot(SnapshotInput(account.id, feb, Money(30_000)))

        val closed = closeAccount(account.id, feb)

        assertEquals(feb, closed.closedPeriod)
        assertEquals(account.rowVersion + 1, closed.rowVersion)
        assertTrue(assets.accounts(includeClosed = false).isEmpty(), "Закрытый счёт не в активных")
        assertEquals(listOf(account.id), assets.accounts(includeClosed = true).map { it.id })
        // История сохраняется (F4.5): снапшот закрытого счёта остаётся читаемым.
        assertEquals(Money(30_000), assets.findSnapshot(account.id, feb)?.balance)
    }

    @Test
    fun `каждая мутация активов попадает в sync_outbox`() = runTest {
        val account = newAccount()
        // Категория счёта тоже попадает в outbox (D-29) — считаем только записи активов.
        val before = assetPending()

        recordSnapshot(SnapshotInput(account.id, jan, Money(100_000)))
        recordSnapshot(SnapshotInput(account.id, feb, Money(120_000)))
        closeAccount(account.id, feb)

        // Два снапшота и закрытие счёта.
        assertEquals(before + 3, assetPending())
        assertEquals(1L, before, "создание счёта")
    }

    private fun assetPending(): Long = db.syncQueries.selectPending(100L).executeAsList()
        .count { it.entity_type == SqlAssetRepository.ENTITY_ACCOUNT || it.entity_type == SqlAssetRepository.ENTITY_SNAPSHOT }
        .toLong()

    @Test
    fun `Net Worth на реальной базе учитывает отрицательный баланс кредита`() = runTest {
        val deposit = newAccount(name = "Вклад")
        // Право на минус задаёт категория (D-20, D-22): кредитный счёт живёт в категории
        // с разрешённым отрицательным остатком, у самого счёта признака больше нет.
        seedAssetCategory(id = "credits", name = "Кредиты", allowsNegativeBalance = true)
        val credit = newAccount(name = "Кредитка", categoryId = "credits")

        recordSnapshot(SnapshotInput(deposit.id, jan, Money(400_000)))
        recordSnapshot(SnapshotInput(credit.id, jan, Money(-50_000)))
        recordSnapshot(SnapshotInput(deposit.id, feb, Money(500_000), contribution = Money(50_000)))
        recordSnapshot(SnapshotInput(credit.id, feb, Money(-75_000)))

        val rub = requireNotNull(summary(feb).of())

        assertEquals(Money(425_000), rub.netWorth, "5 000.00 − 750.00 = 4 250.00 ₽")
        assertEquals(Money(350_000), rub.previousNetWorth)
        assertEquals(Money(75_000), rub.delta)
        assertEquals(Money(50_000), rub.contributions)
        assertEquals(Money(25_000), rub.gain)
        assertEquals(2, rub.accountCount)
    }

    @Test
    fun `снапшоты месяца возвращаются по убыванию баланса`() = runTest {
        val small = newAccount(name = "Наличные")
        val big = newAccount(name = "Вклад")

        recordSnapshot(SnapshotInput(small.id, feb, Money(10_000)))
        recordSnapshot(SnapshotInput(big.id, feb, Money(900_000)))

        assertEquals(
            listOf(Money(900_000), Money(10_000)),
            assets.snapshotsForPeriod(feb).map { it.balance },
        )
    }

    @Test
    fun `история счёта возвращается по месяцам`() = runTest {
        val account = newAccount()
        recordSnapshot(SnapshotInput(account.id, feb, Money(120_000)))
        recordSnapshot(SnapshotInput(account.id, jan, Money(100_000)))

        assertEquals(
            listOf(jan, feb),
            assets.snapshotsByAccount(account.id).map { it.period },
            "Порядок от старых к новым, независимо от порядка ввода",
        )
    }
}
