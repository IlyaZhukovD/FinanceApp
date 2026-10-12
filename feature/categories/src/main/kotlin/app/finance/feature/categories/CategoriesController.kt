package app.finance.feature.categories

import app.finance.core.domain.CategoryRepository
import app.finance.core.domain.CreateCategory
import app.finance.core.domain.SetCategoryArchived
import app.finance.core.domain.UpdateCategory
import app.finance.core.model.AssetBalancePolicy
import app.finance.core.model.Category
import app.finance.core.model.CategorySection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Контроллер диалога управления категориями раздела (SPEC.md F3.1, F3.2).
 *
 * Один класс на все три раздела — расходы, доходы и активы различаются только значением
 * [section]: правила одинаковы, а копипаста экранов запрещена (F2.2). Compose здесь не
 * используется, состояние отдаётся как [StateFlow].
 *
 * Список перечитывается после каждой операции, поэтому разделу-владельцу достаточно
 * обновиться при закрытии диалога (`onClosed` у [CategoryManagerDialog]) — пикеры
 * подхватят новую категорию, а архивированная из них исчезнет.
 *
 * Все обращения к базе — на [ioDispatcher] (см. предупреждение о `runBlocking` внутри
 * `SqlDelightTransactionRunner` в `core:data`).
 */
class CategoriesController(
    val section: CategorySection,
    private val categories: CategoryRepository,
    private val createCategory: CreateCategory,
    private val updateCategory: UpdateCategory,
    private val setArchived: SetCategoryArchived,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val _state = MutableStateFlow(CategoriesUiState(section = section))

    val state: StateFlow<CategoriesUiState> = _state.asStateFlow()

    /** Открывает диалог и перечитывает категории раздела. */
    fun open() {
        _state.update { it.copy(isOpen = true, editor = null, error = null, notice = null) }
        reload()
    }

    fun close() {
        _state.update { it.copy(isOpen = false, editor = null, error = null, notice = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** Архивные категории видны отдельным списком и восстанавливаются (F3.2). */
    fun setShowArchived(show: Boolean) {
        _state.update { it.copy(showArchived = show) }
    }

    // ── Редактор (F3.1) ────────────────────────────────────────────────────────────

    fun startCreate() {
        _state.update { state ->
            state.copy(
                editor = CategoryEditorState(name = "", colorHex = suggestColor(state.items)),
                error = null,
                notice = null,
            )
        }
    }

    fun startEdit(categoryId: String) {
        val item = _state.value.items.firstOrNull { it.id == categoryId } ?: return
        _state.update {
            it.copy(
                editor = CategoryEditorState(
                    id = item.id,
                    name = item.name,
                    colorHex = item.colorHex,
                    balancePolicy = item.balancePolicy,
                    allowsNegativeBalance = item.allowsNegativeBalance,
                ),
                error = null,
                notice = null,
            )
        }
    }

    fun setEditorName(text: String) {
        _state.update { state -> state.copy(editor = state.editor?.copy(name = text, error = null)) }
    }

    fun setEditorColor(hexColor: String) {
        _state.update { state -> state.copy(editor = state.editor?.copy(colorHex = hexColor)) }
    }

    /** Правило баланса категории активов (D-20). */
    fun setEditorBalancePolicy(policy: AssetBalancePolicy) {
        _state.update { state -> state.copy(editor = state.editor?.copy(balancePolicy = policy)) }
    }

    /** Право на отрицательный остаток: кредитка или овердрафт (D-20). */
    fun setEditorAllowsNegative(allows: Boolean) {
        _state.update { state ->
            state.copy(editor = state.editor?.copy(allowsNegativeBalance = allows))
        }
    }

    fun cancelEditor() {
        _state.update { it.copy(editor = null) }
    }

    /**
     * Сохранение редактора: создание или правка в зависимости от [CategoryEditorState.id].
     *
     * Ошибка валидации (пустое имя, дубль имени в разделе, неверный формат цвета) показывается
     * в самом редакторе, а не в общем `error`: пользователь исправляет то поле, которое ввёл.
     */
    fun saveEditor() {
        val editor = _state.value.editor ?: return
        if (editor.isSaving) return

        val categoryId = editor.id
        val name = editor.name
        val colorHex = editor.colorHex
        val balancePolicy = editor.balancePolicy
        val allowsNegativeBalance = editor.allowsNegativeBalance

        scope.launch {
            _state.update { it.copy(editor = it.editor?.copy(isSaving = true, error = null)) }
            runCatching {
                io {
                    if (categoryId == null) {
                        createCategory(
                            section = section,
                            name = name,
                            colorHex = colorHex,
                            balancePolicy = balancePolicy,
                            allowsNegativeBalance = allowsNegativeBalance,
                        )
                    } else {
                        updateCategory(
                            categoryId = categoryId,
                            name = name,
                            colorHex = colorHex,
                            balancePolicy = balancePolicy,
                            allowsNegativeBalance = allowsNegativeBalance,
                        )
                    }
                }
            }
                .onFailure { error ->
                    _state.update { state ->
                        state.copy(editor = state.editor?.copy(isSaving = false, error = describe(error)))
                    }
                }
                .onSuccess { category ->
                    val message = if (categoryId == null) {
                        "Категория «${category.name}» добавлена"
                    } else {
                        "Категория «${category.name}» обновлена"
                    }
                    _state.update { it.copy(editor = null, notice = message, error = null) }
                    reload()
                }
        }
    }

    // ── Архивация (F3.2) ───────────────────────────────────────────────────────────

    fun archive(categoryId: String) = changeArchived(categoryId, archived = true)

    fun restore(categoryId: String) = changeArchived(categoryId, archived = false)

    private fun changeArchived(categoryId: String, archived: Boolean) {
        if (_state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching { io { setArchived(categoryId = categoryId, archived = archived) } }
                .onFailure { error ->
                    _state.update { it.copy(isBusy = false, error = describe(error)) }
                }
                .onSuccess { category ->
                    val message = if (archived) {
                        "«${category.name}» в архиве: категория исчезла из пикеров, суммы и история остались"
                    } else {
                        "«${category.name}» восстановлена"
                    }
                    _state.update { it.copy(isBusy = false, notice = message) }
                    reload()
                }
        }
    }

    // ── Служебное ──────────────────────────────────────────────────────────────────

    private fun reload() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            runCatching { io { readItems() } }
                .onFailure { error ->
                    _state.update { it.copy(isLoading = false, isBusy = false, error = describe(error)) }
                }
                .onSuccess { items ->
                    _state.update { it.copy(isLoading = false, isBusy = false, items = items, error = null) }
                }
        }
    }

    private suspend fun readItems(): List<CategoryItem> =
        categories.bySection(section, includeArchived = true).map { category ->
            CategoryItem(
                id = category.id,
                name = category.name,
                colorHex = category.color,
                isArchived = category.isArchived,
                balancePolicy = category.balancePolicy,
                allowsNegativeBalance = category.allowsNegativeBalance,
            )
        }

    /**
     * Цвет для новой категории: первый незанятый оттенок палитры, чтобы рядом не оказалось
     * двух категорий одного цвета. Если палитра исчерпана — по счёту, как при стартовом посеве.
     */
    private fun suggestColor(items: List<CategoryItem>): String {
        val used = items.filterNot { it.isArchived }.map { it.colorHex }.toSet()
        return Category.DEFAULT_PALETTE.firstOrNull { it !in used } ?: Category.defaultColor(items.size)
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    /** Сообщения доменных use-case'ов уже на русском и рассчитаны на пользователя. */
    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
}
