package app.finance.core.data

import app.finance.core.domain.AmountInput
import app.finance.core.domain.ConflictPolicy
import app.finance.core.domain.DeleteCell
import app.finance.core.domain.DuplicateOptions
import app.finance.core.domain.DuplicatePeriod
import app.finance.core.domain.RecordAmount
import app.finance.core.domain.RecordAmountResult
import app.finance.core.domain.RestoreCell
import app.finance.core.model.Category
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import app.finance.core.model.RevisionOp
import app.finance.core.model.RevisionSource
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Интеграционные тесты на настоящей SQLite: доменные use-case'ы поверх реального
 * репозитория. Проверяют то, что фейки проверить не могут — поведение частичного
 * уникального индекса, внешних ключей и транзакций.
 */
class LedgerPersistenceTest {

    private val session = DatabaseFactory.openInMemory()
    private val db = session.database
    private val time = FixedTime()
    private val ids = SeqIds()
    private val transactions = SqlDelightTransactionRunner(db)
    private val categories = SqlCategoryRepository(db)
    private val ledger = SqlLedgerRepository(db, ids)

    private val recordAmount = RecordAmount(categories, ledger, ids, time, transactions)
    private val deleteCell = DeleteCell(ledger, ids, time, transactions)
    private val restoreCell = RestoreCell(ledger, ids, time, transactions)
    private val duplicatePeriod = DuplicatePeriod(categories, ledger, ids, time, transactions)

    private val jan = Period(2026, 1)
    private val feb = Period(2026, 2)

    @AfterTest
    fun tearDown() = session.close()

    private suspend fun category(id: String = "food"): Category {
        val category = Category(
            id = id,
            section = CategorySection.EXPENSE,
            name = id,
            color = "#4E79A7",
            createdAtUtc = time.now,
            updatedAtUtc = time.now,
        )
        categories.create(category)
        return category
    }

    private fun expense(
        categoryId: String,
        period: Period = feb,
        amount: Long,
        comment: String? = null,
        key: String? = null,
    ) = AmountInput(LedgerSection.EXPENSE, categoryId, period, Money(amount), comment, idempotencyKey = key)

    private fun insertRawCell(id: String, categoryId: String, period: Period) {
        db.categoryMonthQueries.insert(
            id = id,
            section = LedgerSection.EXPENSE.name,
            category_id = categoryId,
            period = period.toDbKey(),
            amount_minor = 100,
            currency = "RUB",
            comment = null,
            created_at_utc = time.now,
            updated_at_utc = time.now,
            row_version = 1,
            deleted_at_utc = null,
        )
    }

    @Test
    fun `аккумулирование на реальной базе даёт одну строку со сложенной суммой`() = runTest {
        val food = category()

        recordAmount(expense(food.id, amount = 30_000, comment = "Большая закупка"))
        time.advance(1_000)
        val second = recordAmount(expense(food.id, amount = 2_400, comment = "Ещё продукты"))

        assertIs<RecordAmountResult.Accumulated>(second)
        assertEquals(1L, ledger.countCells(), "Вторая строка не должна появиться")
        val stored = ledger.findCell(food.id, feb)
        assertEquals(Money(32_400), stored?.amount)
        assertEquals("Большая закупка\nЕщё продукты", stored?.comment)
        assertEquals(2, stored?.rowVersion)
    }

    @Test
    fun `частичный уникальный индекс запрещает две живые ячейки на категорию и месяц`() = runTest {
        val food = category()

        insertRawCell("cell-1", food.id, feb)
        val error = assertFailsWith<Exception> { insertRawCell("cell-2", food.id, feb) }

        val message = error.message.orEmpty()
        assertTrue(
            message.contains("UNIQUE", ignoreCase = true) || message.contains("CONSTRAINT", ignoreCase = true),
            "Ожидали нарушение уникальности, получили: $message",
        )
    }

    @Test
    fun `после soft delete ту же категорию и месяц можно завести заново`() = runTest {
        // Главный смысл частичного индекса WHERE deleted_at IS NULL.
        val food = category()
        val first = recordAmount(expense(food.id, amount = 10_000))
        deleteCell(first.cell.id)

        val second = recordAmount(expense(food.id, amount = 3_000))

        assertIs<RecordAmountResult.Created>(second)
        assertEquals(2L, ledger.countCells(), "Tombstone обязан остаться в базе")
        assertEquals(1, ledger.cellsForPeriod(LedgerSection.EXPENSE, feb).size, "В списке месяца только живая")
        assertEquals(Money(3_000), ledger.findCell(food.id, feb)?.amount, "Новая ячейка не наследует старую сумму")
    }

    @Test
    fun `восстановление возвращает ячейку, а повторное удаление не ломает индекс`() = runTest {
        val food = category()
        val first = recordAmount(expense(food.id, amount = 10_000))
        deleteCell(first.cell.id)

        val restored = restoreCell(first.cell.id)

        assertEquals(Money(10_000), ledger.findCell(food.id, feb)?.amount)
        assertEquals(RevisionOp.UNDELETE, restored.revision.op)
        assertEquals(3, cellPending().size, "CREATE + DELETE + UNDELETE")
    }

    private fun cellPending() = db.syncQueries.selectPending(100L).executeAsList()
        .filter { it.entity_type == SqlLedgerRepository.ENTITY_TYPE }

    @Test
    fun `журнал ревизий читается из базы в порядке создания`() = runTest {
        val food = category()
        val first = recordAmount(expense(food.id, amount = 1_000))
        time.advance(1_000)
        recordAmount(expense(food.id, amount = 2_000))
        time.advance(1_000)
        recordAmount(expense(food.id, amount = 3_000))

        val revisions = ledger.revisionsOf(first.cell.id)

        assertEquals(listOf(RevisionOp.CREATE, RevisionOp.ADD, RevisionOp.ADD), revisions.map { it.op })
        assertEquals(listOf(1_000L, 3_000L, 6_000L), revisions.map { it.amountAfter.minor })
        assertEquals(listOf(null, 1_000L, 3_000L), revisions.map { it.amountBefore?.minor })
        assertEquals("RUB", revisions.last().amountAfter.currency, "Валюта восстанавливается из журнала")
    }

    @Test
    fun `каждая мутация попадает в outbox в той же транзакции`() = runTest {
        val food = category()

        recordAmount(expense(food.id, amount = 1_000))
        assertEquals(1, cellPending().size)

        time.advance(1_000)
        recordAmount(expense(food.id, amount = 500))
        assertEquals(2, cellPending().size)
        // Ревизии тоже уходят в outbox (D-29): по одной на каждый ввод.
        assertEquals(
            2,
            db.syncQueries.selectPending(100L).executeAsList().count { it.entity_type == SqlLedgerRepository.REVISION_ENTITY_TYPE },
        )

        val pending = cellPending()
        assertEquals(
            listOf(SqlLedgerRepository.OPERATION_CREATE, SqlLedgerRepository.OPERATION_UPDATE),
            pending.map { it.operation },
        )
        assertTrue(pending.all { it.entity_type == SqlLedgerRepository.ENTITY_TYPE })
        assertTrue(pending.all { it.status == "pending" })
    }

    @Test
    fun `откат транзакции не оставляет частичных данных`() = runTest {
        val food = category()
        recordAmount(expense(food.id, amount = 1_000))
        val cellsBefore = ledger.countCells()

        // Валюта расходится с уже накопленной: Money.plus бросает исключение внутри транзакции.
        assertFailsWith<IllegalArgumentException> {
            recordAmount(AmountInput(LedgerSection.EXPENSE, food.id, feb, Money(500, "USD")))
        }

        assertEquals(cellsBefore, ledger.countCells(), "Новая ячейка не должна остаться")
        assertEquals(Money(1_000), ledger.findCell(food.id, feb)?.amount, "Сумма не должна измениться")
        assertEquals(1L, ledger.countRevisions(), "Лишняя ревизия не должна остаться")
    }

    @Test
    fun `ключ идемпотентности защищает от повторного импорта`() = runTest {
        val food = category()

        recordAmount(expense(food.id, amount = 5_000, key = "notion-row-42").copy(source = RevisionSource.IMPORT))
        val repeat = recordAmount(
            expense(food.id, amount = 5_000, key = "notion-row-42").copy(source = RevisionSource.IMPORT),
        )

        assertIs<RecordAmountResult.AlreadyApplied>(repeat)
        assertEquals(Money(5_000), ledger.findCell(food.id, feb)?.amount, "Сумма не должна удвоиться")
        assertEquals(1L, ledger.countRevisions())
    }

    @Test
    fun `дублирование месяца работает на реальной базе`() = runTest {
        val food = category("food")
        category("rent")
        recordAmount(expense("food", period = jan, amount = 10_000))
        recordAmount(expense("rent", period = jan, amount = 25_000))

        val report = duplicatePeriod(LedgerSection.EXPENSE, jan, feb)

        assertEquals(2, report.created)
        assertEquals(Money(10_000), ledger.findCell("food", feb)?.amount)
        assertEquals(Money(25_000), ledger.findCell("rent", feb)?.amount)
        assertNull(ledger.findCell("food", feb)?.comment, "Комментарии по умолчанию не переносятся")

        val duplicateRevision = ledger.revisionsOf(ledger.findCell("food", feb)!!.id).single()
        assertEquals(RevisionOp.DUPLICATE, duplicateRevision.op)
        assertEquals(RevisionSource.DUPLICATE, duplicateRevision.source)
        assertEquals(jan, duplicateRevision.sourcePeriod, "Ревизия обязана хранить месяц-источник")
    }

    @Test
    fun `дублирование с политикой ADD накапливает сумму`() = runTest {
        category("food")
        recordAmount(expense("food", period = jan, amount = 10_000))
        recordAmount(expense("food", period = feb, amount = 1_500))

        val report = duplicatePeriod(
            LedgerSection.EXPENSE, jan, feb, DuplicateOptions(conflictPolicy = ConflictPolicy.ADD),
        )

        assertEquals(1, report.added)
        assertEquals(Money(11_500), ledger.findCell("food", feb)?.amount)
        assertEquals(2L, ledger.countCells(), "Ячеек две: январь и февраль")
    }
}
