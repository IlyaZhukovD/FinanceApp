package app.finance.core.data

import app.finance.core.model.CategorySection
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CategorySeederTest {

    private val session = DatabaseFactory.openInMemory()
    private val time = FixedTime()
    private val categories = SqlCategoryRepository(session.database)
    private val seeder = CategorySeeder(categories, SeqIds(), time)

    @AfterTest
    fun tearDown() = session.close()

    @Test
    fun `создаёт стартовые категории всех трёх секций`() = runTest {
        val created = seeder.seedIfEmpty()

        assertEquals(CategorySeeder.EXPECTED_TOTAL, created)
        assertEquals(11, categories.bySection(CategorySection.EXPENSE).size)
        assertEquals(5, categories.bySection(CategorySection.INCOME).size)
        assertEquals(6, categories.bySection(CategorySection.ASSET).size)
    }

    @Test
    fun `повторный запуск ничего не добавляет`() = runTest {
        seeder.seedIfEmpty()
        val before = categories.count()

        val created = seeder.seedIfEmpty()

        assertEquals(0, created)
        assertEquals(before, categories.count())
    }

    @Test
    fun `архивация стартовой категории не приводит к её воссозданию`() = runTest {
        seeder.seedIfEmpty()
        val products = categories.bySection(CategorySection.EXPENSE).first()

        categories.setArchived(products.id, archived = true, updatedAtUtc = time.now)
        val created = seeder.seedIfEmpty()

        assertEquals(0, created, "Секция не пуста, поэтому сидер не должен ничего делать")
        assertEquals(10, categories.bySection(CategorySection.EXPENSE).size, "Архивная не выдаётся в формы ввода")
        assertEquals(
            11,
            categories.bySection(CategorySection.EXPENSE, includeArchived = true).size,
            "История категории сохранена",
        )
    }

    @Test
    fun `порядок категорий соответствует заданному`() = runTest {
        seeder.seedIfEmpty()

        val names = categories.bySection(CategorySection.EXPENSE).map { it.name }

        assertEquals(CategorySeeder.DEFAULTS.getValue(CategorySection.EXPENSE), names)
    }

    @Test
    fun `цвета берутся из палитры и не повторяются внутри секции`() = runTest {
        seeder.seedIfEmpty()

        val colors = categories.bySection(CategorySection.EXPENSE).map { it.color }

        assertEquals(colors.size, colors.distinct().size)
        assertTrue(colors.all { it.matches(Regex("^#[0-9A-Fa-f]{6}$")) })
    }

    @Test
    fun `идентификаторы категорий уникальны`() = runTest {
        seeder.seedIfEmpty()

        val ids = buildList {
            addAll(categories.bySection(CategorySection.EXPENSE).map { it.id })
            addAll(categories.bySection(CategorySection.INCOME).map { it.id })
            addAll(categories.bySection(CategorySection.ASSET).map { it.id })
        }

        assertEquals(ids.size, ids.distinct().size)
        assertEquals(CategorySeeder.EXPECTED_TOTAL.toLong(), categories.count())
    }
}
