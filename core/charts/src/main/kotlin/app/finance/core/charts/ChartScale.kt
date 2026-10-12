package app.finance.core.charts

import kotlin.jvm.JvmName
import kotlin.math.abs

/**
 * Вертикальная шкала графика: границы и шаг сетки в минорных единицах.
 *
 * Расчёт вынесен из Compose в чистую функцию, потому что «красивый» шаг и выравнивание
 * границ — типичный источник кривых осей, и проверять это нужно unit-тестами, а не глазами.
 */
data class VerticalScale(
    val minValueMinor: Long,
    val maxValueMinor: Long,
    val stepMinor: Long,
) {

    init {
        require(stepMinor > 0L) { "Шаг шкалы должен быть положительным: $stepMinor" }
        require(minValueMinor <= maxValueMinor) {
            "Начало шкалы $minValueMinor больше конца $maxValueMinor"
        }
    }

    /** Подписи сетки снизу вверх, включая обе границы. */
    val ticks: List<Long>
        get() {
            val result = mutableListOf<Long>()
            var value = minValueMinor
            while (value <= maxValueMinor) {
                result += value
                if (value > Long.MAX_VALUE - stepMinor) break
                value += stepMinor
            }
            return result
        }

    /** Доля значения в высоте области графика: 0f — нижняя граница, 1f — верхняя. */
    fun fractionOf(valueMinor: Long): Float {
        val span = maxValueMinor - minValueMinor
        if (span == 0L) return 0f
        // Единственное место, где деньги становятся Float: это пиксельная координата,
        // а не арифметика над суммами (SPEC.md §5).
        return ((valueMinor - minValueMinor).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }

    /** Базовая линия столбцов. Ноль всегда входит в диапазон, но clamp защищает от края. */
    val zeroFraction: Float get() = fractionOf(0L)

    companion object {

        /**
         * Шкала по значениям всех точек.
         *
         * [includeZero] разделяет два разных случая. Столбцам ноль нужен всегда: без базовой
         * линии они висят в воздухе, а дельта cash flow теряет наглядность. Линии, наоборот,
         * интересны именно ходом — если капитал меняется в диапазоне 1,0–1,2 млн, принудительный
         * ноль сплющит тренд в прямую у верхней границы. Поэтому для линий ноль не добавляется,
         * а диапазон расширяется на ±10 % («воздух» сверху и снизу).
         *
         * `null` возвращается, только если значений нет вовсе — тогда график показывает пустое
         * состояние, а не ось вокруг нуля.
         */
        fun of(
            values: List<Long?>,
            tickCount: Int = DEFAULT_TICK_COUNT,
            includeZero: Boolean = true,
        ): VerticalScale? {
            require(tickCount >= 2) { "Нужно минимум две подписи сетки, получено $tickCount" }
            val present = values.filterNotNull()
            if (present.isEmpty()) return null

            var min = present.min()
            var max = present.max()
            if (includeZero) {
                if (min > 0L) min = 0L
                if (max < 0L) max = 0L
            }

            if (min == max) {
                // Все значения равны: без запаса шкала вырождается в точку.
                val pad = when {
                    max == 0L -> FLAT_PAD_MINOR
                    includeZero -> abs(max)
                    else -> maxOf(abs(max) / PADDING_DIVISOR, 1L)
                }
                min -= pad
                max += pad
            } else if (!includeZero) {
                // Деление, а не умножение на процент: переполнение Long невозможно даже
                // на нереалистично больших суммах.
                val pad = (max - min) / PADDING_DIVISOR
                min -= pad
                max += pad
            }

            val step = niceStep(Math.ceilDiv(max - min, tickCount - 1L))
            return VerticalScale(
                // Границы выравниваются наружу до шага, чтобы подписи были круглыми.
                minValueMinor = Math.floorDiv(min, step) * step,
                maxValueMinor = Math.ceilDiv(max, step) * step,
                stepMinor = step,
            )
        }

        /**
         * Шкала по нескольким сериям одного графика.
         *
         * `@JvmName` здесь обязателен: после стирания типов обе перегрузки дают одинаковую
         * JVM-сигнатуру `of(List, int)`, и компилятор падает с «platform declaration clash»,
         * хотя в Kotlin аргументы различаются.
         */
        @JvmName("ofSeries")
        fun of(
            series: List<ChartSeries>,
            tickCount: Int = DEFAULT_TICK_COUNT,
            includeZero: Boolean = true,
        ): VerticalScale? =
            of(series.flatMap { it.points.map { point -> point.valueMinor } }, tickCount, includeZero)

        /**
         * Округляет шаг до «красивого» числа вида 1/2/5 × 10^k.
         *
         * Без этого подписи оси выглядят как 37 412 ₽ и не дают глазу зацепиться за масштаб.
         */
        private fun niceStep(rawStep: Long): Long {
            if (rawStep <= 1L) return 1L
            var magnitude = 1L
            while (magnitude * 10 <= rawStep) {
                magnitude *= 10
            }
            val normalized = rawStep / magnitude
            val multiplier = when {
                normalized <= 1L -> 1L
                normalized <= 2L -> 2L
                normalized <= 5L -> 5L
                else -> 10L
            }
            return multiplier * magnitude
        }

        const val DEFAULT_TICK_COUNT = 5

        /** Запас по краям диапазона линии: делитель 10 даёт те самые ±10 %. */
        private const val PADDING_DIVISOR = 10L

        /** Запас для вырожденной шкалы: 100,00 ₽ — заметно, но не абсурдно. */
        private const val FLAT_PAD_MINOR = 10_000L
    }
}
