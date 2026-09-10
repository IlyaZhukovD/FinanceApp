package app.finance.core.data

import app.finance.core.domain.CategoryRepository
import app.finance.core.domain.IdGenerator
import app.finance.core.domain.TimeProvider
import app.finance.core.model.Category
import app.finance.core.model.CategorySection

/**
 * Стартовые категории при первом запуске (SPEC.md §6, «Seed»).
 *
 * Идемпотентен: секция, в которой уже есть категории, не трогается. Это позволяет
 * вызывать сидер при каждом старте и не бояться ни повторного запуска, ни базы,
 * где пользователь уже удалил или переименовал стартовые категории.
 */
class CategorySeeder(
    private val categories: CategoryRepository,
    private val ids: IdGenerator,
    private val time: TimeProvider,
) {
    /** @return число созданных категорий; 0, если база уже заполнена. */
    suspend fun seedIfEmpty(): Int {
        val now = time.nowUtcMillis()
        var created = 0

        DEFAULTS.forEach { (section, names) ->
            // includeArchived = true: если пользователь заархивировал все стартовые
            // категории, воссоздавать их не нужно.
            if (categories.bySection(section, includeArchived = true).isNotEmpty()) return@forEach

            names.forEachIndexed { index, name ->
                categories.create(
                    Category(
                        id = ids.newId(),
                        section = section,
                        name = name,
                        color = Category.defaultColor(index),
                        sortOrder = index,
                        createdAtUtc = now,
                        updatedAtUtc = now,
                    ),
                )
                created++
            }
        }

        return created
    }

    companion object {
        val DEFAULTS: Map<CategorySection, List<String>> = linkedMapOf(
            CategorySection.EXPENSE to listOf(
                "Продукты", "Транспорт", "Жильё и ЖКХ", "Кафе и рестораны", "Здоровье",
                "Развлечения", "Одежда", "Связь и подписки", "Образование", "Путешествия", "Прочее",
            ),
            CategorySection.INCOME to listOf(
                "Зарплата", "Фриланс", "Бонусы", "Проценты и дивиденды", "Прочее",
            ),
            CategorySection.ASSET to listOf(
                "Наличные", "Вклады", "Брокерский счёт", "ИИС и паи", "Недвижимость", "Прочее",
            ),
        )

        val EXPECTED_TOTAL: Int = DEFAULTS.values.sumOf { it.size }
    }
}
