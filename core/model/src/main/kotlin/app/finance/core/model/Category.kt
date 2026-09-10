package app.finance.core.model

/**
 * Категория. Глобальная в рамках нескольких месяцев и лет, принадлежит одной секции.
 * Список плоский: `parentId` зарезервирован под будущую иерархию и в v1 всегда null
 * (docs/DECISIONS.md, D-04).
 *
 * Удаление категории — архивация (`isArchived`), история и суммы сохраняются.
 * `deletedAtUtc` — tombstone для будущей синхронизации, physical delete запрещён.
 */
data class Category(
    val id: String,
    val section: CategorySection,
    val name: String,
    val color: String,
    val icon: String? = null,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val isArchived: Boolean = false,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
    val rowVersion: Int = 1,
    val deletedAtUtc: Long? = null,
) {
    init {
        require(name.isNotBlank()) { "Название категории не может быть пустым" }
        require(COLOR_REGEX.matches(color)) { "Цвет должен быть в формате #RRGGBB, получено: $color" }
        require(parentId == null) { "Иерархия категорий в v1 не поддерживается (D-04)" }
    }

    /** Категория доступна для выбора в формах ввода. */
    val isSelectable: Boolean get() = !isArchived && deletedAtUtc == null

    companion object {
        private val COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

        /** Палитра по умолчанию: различима при дейтеранопии (SPEC.md §10). */
        val DEFAULT_PALETTE: List<String> = listOf(
            "#4E79A7", "#F28E2B", "#59A14F", "#E15759", "#B07AA1",
            "#76B7B2", "#EDC948", "#9C755F", "#FF9DA7", "#86BCB6",
            "#D37295", "#8CD17D",
        )

        fun defaultColor(index: Int): String =
            DEFAULT_PALETTE[Math.floorMod(index, DEFAULT_PALETTE.size)]
    }
}
