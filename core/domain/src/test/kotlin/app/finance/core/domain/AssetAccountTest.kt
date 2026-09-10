package app.finance.core.domain

import app.finance.core.model.AssetKind
import app.finance.core.model.CategorySection
import app.finance.core.model.Money
import app.finance.core.model.Period
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Счета активов: создание (SPEC.md F4.2) и закрытие (F4.5).
 *
 * Закрытие — не удаление: история снапшотов сохраняется, счёт лишь перестаёт
 * участвовать в текущем месяце и в дублировании периода.
 */
class AssetAccountTest {

    private val fx = DomainFixture()
    private val jan = Period(2026, 1)
    private val feb = Period(2026, 2)
    private val mar = Period(2026, 3)

    private fun assetCategory(id: String = "assets", name: String = "Банки", archived: Boolean = false) =
        fx.category(id, name, section = CategorySection.ASSET, archived = archived)

    private fun input(
        categoryId: String,
        name: String = "Вклад Т—Ж",
        kind: AssetKind = AssetKind.DEPOSIT,
        currency: String = Money.DEFAULT_CURRENCY,
        openedPeriod: Period = jan,
        annualRateBp: Int? = null,
        note: String? = null,
    ) = AccountInput(
        categoryId = categoryId,
        name = name,
        kind = kind,
        currency = currency,
        openedPeriod = openedPeriod,
        annualRateBp = annualRateBp,
        note = note,
    )

    @Test
    fun `создаёт счёт в категории активов`() = runTest {
        val category = assetCategory()

        val account = fx.createAccount(
            input(categoryId = category.id, name = "  Вклад Т—Ж  ", annualRateBp = 850, note = " до 2027 "),
        )

        assertEquals(category.id, account.categoryId)
        assertEquals("Вклад Т—Ж", account.name, "Название обрезается по краям")
        assertEquals(AssetKind.DEPOSIT, account.kind)
        assertEquals(Money.DEFAULT_CURRENCY, account.currency)
        assertEquals(jan, account.openedPeriod)
        assertEquals(850, account.annualRateBp)
        assertEquals("до 2027", account.note)
        assertEquals(1, account.rowVersion)
        assertTrue(account.isOpen)
        assertTrue(account.isActiveAt(feb))
        assertEquals(1, fx.assets.accountsById.size)
        assertEquals(1, fx.transactions.transactionsStarted)
    }

    @Test
    fun `отклоняет категорию другой секции`() = runTest {
        val expense = fx.category("food", "Продукты", section = CategorySection.EXPENSE)

        val error = assertFailsWith<IllegalArgumentException> {
            fx.createAccount(input(categoryId = expense.id))
        }

        assertTrue(error.message!!.contains("относится к секции EXPENSE"), "Сообщение: ${error.message}")
        assertTrue(fx.assets.accountsById.isEmpty())
    }

    @Test
    fun `отклоняет архивную категорию`() = runTest {
        val archived = assetCategory(id = "old", name = "Старые банки", archived = true)

        val error = assertFailsWith<IllegalArgumentException> {
            fx.createAccount(input(categoryId = archived.id))
        }

        assertTrue(error.message!!.contains("в архиве"), "Сообщение: ${error.message}")
    }

    @Test
    fun `отклоняет неизвестную категорию и пустое название`() = runTest {
        assetCategory()

        val unknown = assertFailsWith<IllegalArgumentException> {
            fx.createAccount(input(categoryId = "no-such"))
        }
        assertTrue(unknown.message!!.contains("не найдена"), "Сообщение: ${unknown.message}")

        val blank = assertFailsWith<IllegalArgumentException> {
            fx.createAccount(input(categoryId = "assets", name = "   "))
        }
        assertTrue(blank.message!!.contains("не может быть пустым"), "Сообщение: ${blank.message}")
    }

    @Test
    fun `годовая ставка допустима только для вклада и не может быть отрицательной`() = runTest {
        assetCategory()

        val wrongKind = assertFailsWith<IllegalArgumentException> {
            fx.createAccount(input(categoryId = "assets", kind = AssetKind.BROKERAGE, annualRateBp = 500))
        }
        assertTrue(wrongKind.message!!.contains("только для вклада"), "Сообщение: ${wrongKind.message}")

        val negative = assertFailsWith<IllegalArgumentException> {
            fx.createAccount(input(categoryId = "assets", annualRateBp = -1))
        }
        assertTrue(negative.message!!.contains("отрицательной"), "Сообщение: ${negative.message}")
    }

    @Test
    fun `закрытие счёта сохраняет историю снапшотов`() = runTest {
        val account = fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT, openedPeriod = jan)
        fx.snapshot("deposit", jan, balanceMinor = 100_000)
        fx.snapshot("deposit", feb, balanceMinor = 105_000)

        val closed = fx.closeAccount(account.id, feb)

        assertEquals(feb, closed.closedPeriod)
        assertFalse(closed.isOpen)
        assertEquals(account.rowVersion + 1, closed.rowVersion)
        // История остаётся доступной: F4.5 и F4.4 требуют сохранения снапшотов.
        assertEquals(
            listOf(jan, feb),
            fx.assets.snapshotsByAccount(account.id).map { it.period },
        )
        // Счёт активен по месяц закрытия включительно и не активен позже.
        assertTrue(closed.isActiveAt(feb))
        assertFalse(closed.isActiveAt(mar))
    }

    @Test
    fun `закрытый счёт исключается из активных, но виден в полном списке`() = runTest {
        fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT, openedPeriod = jan)
        val closing = fx.account("cash", "Наличные", kind = AssetKind.CASH, openedPeriod = jan)

        fx.closeAccount(closing.id, feb)

        val active = fx.assets.accounts(includeClosed = false).map { it.id }
        val all = fx.assets.accounts(includeClosed = true).map { it.id }

        assertEquals(listOf("deposit"), active)
        assertEquals(setOf("deposit", "cash"), all.toSet())
    }

    @Test
    fun `повторное закрытие отклоняется`() = runTest {
        val account = fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT, openedPeriod = jan, closedPeriod = feb)

        val error = assertFailsWith<IllegalArgumentException> {
            fx.closeAccount(account.id, mar)
        }

        assertTrue(error.message!!.contains("уже закрыт"), "Сообщение: ${error.message}")
        assertEquals(feb, fx.assets.accountById(account.id)?.closedPeriod, "Период закрытия не изменился")
    }

    @Test
    fun `период закрытия раньше открытия отклоняется`() = runTest {
        val account = fx.account("deposit", "Вклад", kind = AssetKind.DEPOSIT, openedPeriod = feb)

        val error = assertFailsWith<IllegalArgumentException> {
            fx.closeAccount(account.id, jan)
        }

        assertTrue(error.message!!.contains("раньше периода открытия"), "Сообщение: ${error.message}")
    }

    @Test
    fun `закрытие неизвестного счёта отклоняется`() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            fx.closeAccount("no-such-account", feb)
        }

        assertTrue(error.message!!.contains("не найден"), "Сообщение: ${error.message}")
    }
}
