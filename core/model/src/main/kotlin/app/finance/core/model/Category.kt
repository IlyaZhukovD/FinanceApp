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
    /**
     * Правило формирования баланса счетов категории (SPEC.md F4.6, D-20).
     *
     * Осмысленно только для секции ASSET: у потоковых категорий (расходы, доходы) баланса
     * нет, поэтому там всегда [AssetBalancePolicy.FLAT] — это проверяет `init`.
     */
    val balancePolicy: AssetBalancePolicy = AssetBalancePolicy.DEFAULT,
    /** Баланс счетов категории может быть отрицательным (кредитка, овердрафт). Только ASSET. */
    val allowsNegativeBalance: Boolean = false,
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
        // Правило баланса и отрицательный остаток — свойства активов: у ячейки
        // «категория + месяц» в потоковом учёте баланса нет вовсе (D-12).
        require(section == CategorySection.ASSET || balancePolicy == AssetBalancePolicy.DEFAULT) {
            "Правило баланса задаётся только категориям активов, а не $section"
        }
        require(section == CategorySection.ASSET || !allowsNegativeBalance) {
            "Отрицательный баланс задаётся только категориям активов, а не $section"
        }
    }

    /** Категория доступна для выбора в формах ввода. */
    val isSelectable: Boolean get() = !isArchived && deletedAtUtc == null

    companion object {
        private val COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

        /**
         * Палитра категорий по умолчанию: качественная схема Tol «muted» (11 цветов).
         *
         * Подобрана под дальтоников (SPEC.md §10): оттенки различаются и по тону, и по
         * светлоте, поэтому категории разделяются даже в оттенках серого. Прежний набор
         * Tableau-10 формально считался «различимым», но содержал пару зелёный/красный
         * (#59A14F и #E15759), которая при дейтеранопии сливается. Светло-серый #DDDDDD
         * из схемы исключён: на белом фоне карточки он почти невидим.
         */
        val DEFAULT_PALETTE: List<String> = listOf(
            "#332288", "#6699CC", "#88CCEE", "#44AA99", "#117733", "#999933",
            "#DDCC77", "#661100", "#CC6677", "#AA4466", "#882255",
        )

        fun defaultColor(index: Int): String =
            DEFAULT_PALETTE[Math.floorMod(index, DEFAULT_PALETTE.size)]
    }
}
