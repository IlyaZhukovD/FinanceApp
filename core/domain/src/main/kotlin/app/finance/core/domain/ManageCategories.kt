package app.finance.core.domain

import app.finance.core.model.AssetBalancePolicy
import app.finance.core.model.Category
import app.finance.core.model.CategorySection

/**
 * Управление категориями раздела (SPEC.md F3.1, F3.2, F3.4).
 *
 * Три use-case'а вместо одного «CategoryManager»: каждый выражает одну операцию продукта
 * и проверяет только свои инварианты. Ошибки — `IllegalArgumentException` с русским
 * сообщением, как в остальных use-case'ах: контроллеры разделов показывают их в `NoticeBar`
 * без дополнительного преобразования.
 *
 * Не реализовано и сознательно отложено: иконка (`Category.icon` в v1 не отображается)
 * и порядок drag-and-drop — новая категория добавляется в конец списка.
 */

/**
 * Создание категории в разделе (F3.1).
 *
 * Правила:
 * - имя уникально среди НЕархивных категорий секции, сравнение без учёта регистра
 *   (архивная «Продукты» не мешает завести новую с тем же именем);
 * - цвет — из палитры [Category.DEFAULT_PALETTE], если не задан явно: берётся оттенок
 *   по числу активных категорий, поэтому раздел различим без ручного подбора;
 * - порядок — в конец списка (`sortOrder = max + 1`);
 * - [balancePolicy] и [allowsNegativeBalance] осмысленны только для секции ASSET:
 *   у потоковой категории баланса нет, и сочетание отклоняет `Category.init` (D-20).
 *
 * Иерархия плоская: `parentId` всегда null (D-04), это дополнительно проверяет
 * `Category.init`.
 */
class CreateCategory(
    private val categories: CategoryRepository,
    private val ids: IdGenerator,
    private val time: TimeProvider,
) {
    /** @param colorHex цвет в формате `#RRGGBB`; null — подобрать из палитры. */
    suspend operator fun invoke(
        section: CategorySection,
        name: String,
        colorHex: String? = null,
        balancePolicy: AssetBalancePolicy = AssetBalancePolicy.DEFAULT,
        allowsNegativeBalance: Boolean = false,
    ): Category {
        val trimmed = normalizeName(name)
        val active = categories.bySection(section, includeArchived = false)
        requireUniqueName(trimmed, siblings = active, exceptId = null)

        val now = time.nowUtcMillis()
        val category = Category(
            id = ids.newId(),
            section = section,
            name = trimmed,
            color = colorHex ?: Category.defaultColor(active.size),
            sortOrder = (active.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            balancePolicy = balancePolicy,
            allowsNegativeBalance = allowsNegativeBalance,
            createdAtUtc = now,
            updatedAtUtc = now,
        )
        categories.create(category)
        return category
    }
}

/**
 * Переименование, смена цвета и правила баланса (F3.1, F3.4, D-20).
 *
 * `id` стабилен, поэтому история ячеек, суммы и аналитика не затрагиваются:
 * переименование видно во всех прошлых месяцах сразу.
 *
 * Параметры правила баланса обязательны, а не заданы по умолчанию: редактор всегда
 * передаёт полное состояние, иначе сохранение имени молча сбросило бы правило
 * категории активов на [AssetBalancePolicy.FLAT].
 */
class UpdateCategory(
    private val categories: CategoryRepository,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(
        categoryId: String,
        name: String,
        colorHex: String,
        balancePolicy: AssetBalancePolicy,
        allowsNegativeBalance: Boolean,
    ): Category {
        val current = requireFound(categories, categoryId)
        val trimmed = normalizeName(name)
        val active = categories.bySection(current.section, includeArchived = false)
        requireUniqueName(trimmed, siblings = active, exceptId = categoryId)

        // `Category.copy` проходит те же проверки init, что и конструктор: пустое имя,
        // цвет не в формате #RRGGBB или правило баланса у потоковой категории
        // отклоняются моделью, а не слоем UI.
        val updated = current.copy(
            name = trimmed,
            color = colorHex,
            balancePolicy = balancePolicy,
            allowsNegativeBalance = allowsNegativeBalance,
            updatedAtUtc = time.nowUtcMillis(),
        )
        categories.update(updated)
        return updated
    }
}

/**
 * «Удаление» категории — архивация (F3.2).
 *
 * Архивная категория исчезает из пикеров ввода, но её суммы и история ревизий остаются
 * в месяцах: физическое удаление запрещено (tombstone-модель, D-06). `archived = false`
 * восстанавливает категорию, если её имя к тому моменту не занято активной.
 */
class SetCategoryArchived(
    private val categories: CategoryRepository,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(categoryId: String, archived: Boolean): Category {
        val current = requireFound(categories, categoryId)
        if (!archived) {
            val active = categories.bySection(current.section, includeArchived = false)
            requireUniqueName(current.name, siblings = active, exceptId = categoryId)
        }
        categories.setArchived(categoryId, archived, time.nowUtcMillis())
        return current.copy(isArchived = archived)
    }
}

/** Имя категории: trimmed, непустое. Ограничение длины задаёт модель, не use-case. */
private fun normalizeName(name: String): String {
    val trimmed = name.trim()
    require(trimmed.isNotEmpty()) { "Название категории не может быть пустым" }
    return trimmed
}

/**
 * Уникальность имени среди активных категорий секции (F3.1).
 *
 * Сравнение без учёта регистра: «продукты» и «Продукты» в одном разделе — дубликаты,
 * которые в пикере выглядят как две разные категории с одинаковым названием.
 */
private fun requireUniqueName(name: String, siblings: List<Category>, exceptId: String?) {
    val clash = siblings.firstOrNull { it.id != exceptId && it.name.equals(name, ignoreCase = true) }
    require(clash == null) { "Категория «${clash?.name.orEmpty()}» в этом разделе уже есть" }
}

private suspend fun requireFound(categories: CategoryRepository, categoryId: String): Category =
    requireNotNull(categories.byId(categoryId)) { "Категория $categoryId не найдена" }
