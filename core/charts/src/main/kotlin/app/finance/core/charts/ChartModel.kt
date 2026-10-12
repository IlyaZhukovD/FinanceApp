package app.finance.core.charts

import app.finance.core.ui.ChartPalette

/**
 * Точка серии графика.
 *
 * Значение хранится в минорных единицах (`Long`), как `Money`: SPEC.md §5 запрещает
 * плавающую точку для денег, поэтому `Float` появляется только внутри отрисовки — при
 * переводе значения в пиксельную координату, — а не в данных графика.
 *
 * `valueMinor == null` означает пропуск (gap): точка не рисуется, линия разрывается.
 * Это критично для активов, где пропущенный месяц не равен нулю (SPEC.md §5), и безопасно
 * для потоков, где аналитика подставляет явный ноль вместо null.
 */
data class ChartPoint(
    /** Подпись по оси X, например «02.2026». */
    val label: String,
    val valueMinor: Long?,
)

/**
 * Одна линия (или группа столбцов) на графике.
 *
 * Все серии одного графика обязаны иметь одинаковый набор [points] по оси X — иначе
 * столбцы и линии разъедутся. Это соглашение проверяет вызывающий слой, собирая серии
 * из одного `PeriodRange`.
 */
data class ChartSeries(
    val id: String,
    val title: String,
    /** Цвет в HEX, как у категорий в БД: модель не должна тянуть Compose-типы. */
    val colorHex: String,
    val points: List<ChartPoint>,
) {
    val hasData: Boolean get() = points.any { it.valueMinor != null }
}

/**
 * Сектор круговой диаграммы (F6.1.3).
 *
 * Отрицательные и нулевые значения не запрещены конструктором намеренно: график не должен
 * ронять приложение из-за сторно в данных — такие секторы отбрасывает `DonutChart`.
 */
data class DonutSlice(
    val label: String,
    val colorHex: String,
    val valueMinor: Long,
)

/** Секторов больше восьми кольцо не читается: доли узкие, а легенда длиннее графика. */
const val MAX_DONUT_SLICES = 8

/** Подпись объединённого хвоста. */
const val OTHER_SLICE_LABEL = "Прочее"

/**
 * Оставляет не более [maxSlices] крупнейших секторов, объединяя хвост в «Прочее».
 *
 * Хвост суммируется целиком, поэтому итог не меняется и проценты остаются честными —
 * в отличие от простого обрезания списка, где «потерянные» категории молча исчезают из
 * суммы. Вход уже должен быть отсортирован по убыванию, но функция сортирует сама:
 * полагаться на порядок извне здесь не на чем.
 */
fun List<DonutSlice>.limitSlices(
    maxSlices: Int = MAX_DONUT_SLICES,
    otherLabel: String = OTHER_SLICE_LABEL,
    otherColorHex: String = ChartPalette.OTHER_HEX,
): List<DonutSlice> {
    require(maxSlices >= 2) { "Минимум два сектора: крупный и «Прочее», получено $maxSlices" }
    if (size <= maxSlices) return this

    val sorted = sortedByDescending { it.valueMinor }
    val head = sorted.take(maxSlices - 1)
    val otherMinor = sorted.drop(maxSlices - 1).sumOf { it.valueMinor }
    if (otherMinor <= 0L) return head
    return head + DonutSlice(label = otherLabel, colorHex = otherColorHex, valueMinor = otherMinor)
}
