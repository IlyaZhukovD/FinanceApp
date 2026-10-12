package app.finance.feature.categories

import app.finance.core.model.AssetBalancePolicy
import app.finance.core.model.CategorySection

/**
 * Состояние диалога управления категориями раздела (SPEC.md F3.1, F3.2).
 *
 * Все значения подготовлены к отображению: контроллер сам формирует заголовок раздела и
 * тексты уведомлений, чтобы в Compose не оставалось логики (соглашение проекта).
 */
data class CategoriesUiState(
    val section: CategorySection,
    val isOpen: Boolean = false,
    val isLoading: Boolean = false,
    /**
     * Все категории раздела, включая архивные, в порядке `sort_order, name` — так их
     * возвращает репозиторий. Видимость архивных переключается в UI ([showArchived]).
     */
    val items: List<CategoryItem> = emptyList(),
    val showArchived: Boolean = false,
    val editor: CategoryEditorState? = null,
    /** Идёт операция (архивация, сохранение): кнопки блокируются. */
    val isBusy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    val visibleItems: List<CategoryItem>
        get() = if (showArchived) items else items.filterNot { it.isArchived }

    val activeCount: Int get() = items.count { !it.isArchived }

    val archivedCount: Int get() = items.count { it.isArchived }

    val title: String
        get() = when (section) {
            CategorySection.EXPENSE -> "Категории расходов"
            CategorySection.INCOME -> "Категории доходов"
            CategorySection.ASSET -> "Категории активов"
        }

    /** Подпись под заголовком: состав списка и правило архивации (F3.2). */
    val subtitle: String
        get() = buildString {
            append("Активных: $activeCount")
            if (archivedCount > 0) append(" · в архиве: $archivedCount")
            append(". Архивированная категория исчезает из пикеров, но её суммы и история остаются")
        }
}

/** Строка списка категорий: минимум данных, нужный для отображения и действий. */
data class CategoryItem(
    val id: String,
    val name: String,
    val colorHex: String,
    val isArchived: Boolean,
    /** Правило баланса категории активов (D-20); для потоковых секций всегда FLAT. */
    val balancePolicy: AssetBalancePolicy = AssetBalancePolicy.DEFAULT,
    val allowsNegativeBalance: Boolean = false,
)

/**
 * Редактор одной категории. `id == null` — создание, иначе правка имени, цвета и
 * правила баланса (F3.1, D-20).
 *
 * Правило баланса и признак отрицательного остатка показываются только для секции
 * [CategorySection.ASSET]: у категорий расходов и доходов баланса нет.
 *
 * Иконка и порядок drag-and-drop из F3.1 в v1 не реализованы: иконка в интерфейсе не
 * отображается, а новая категория добавляется в конец списка.
 */
data class CategoryEditorState(
    val id: String? = null,
    val name: String = "",
    val colorHex: String,
    val balancePolicy: AssetBalancePolicy = AssetBalancePolicy.DEFAULT,
    val allowsNegativeBalance: Boolean = false,
    val error: String? = null,
    val isSaving: Boolean = false,
) {
    val isNew: Boolean get() = id == null

    val title: String get() = if (isNew) "Новая категория" else "Изменить категорию"

    val saveLabel: String get() = if (isNew) "Создать" else "Сохранить"
}
