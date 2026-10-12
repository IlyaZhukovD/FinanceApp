package app.finance.core.charts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Тесты шкалы графиков.
 *
 * Расчёт оси вынесен из Compose именно ради этого: «красивый» шаг, выравнивание границ и
 * включение нуля проверяются числами, а не глазами на скриншоте.
 */
class VerticalScaleTest {

    @Test
    fun `шкала включает ноль и выравнивает границы до красивого шага`() {
        val scale = requireNotNull(VerticalScale.of(listOf(10_000L, 25_000L, 30_000L)))

        // Диапазон 0..30 000, шаг 7 500 округляется до 10 000.
        assertEquals(0L, scale.minValueMinor)
        assertEquals(30_000L, scale.maxValueMinor)
        assertEquals(10_000L, scale.stepMinor)
        assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L), scale.ticks)
    }

    @Test
    fun `отрицательные значения расширяют шкалу вниз от нуля`() {
        val scale = requireNotNull(VerticalScale.of(listOf(-75_000L, 50_000L)))

        // Диапазон −75 000..50 000, шаг 31 250 округляется до 50 000, границы наружу.
        assertEquals(-100_000L, scale.minValueMinor)
        assertEquals(50_000L, scale.maxValueMinor)
        assertEquals(50_000L, scale.stepMinor)
        assertEquals(listOf(-100_000L, -50_000L, 0L, 50_000L), scale.ticks)
    }

    @Test
    fun `пропуски игнорируются, а без значений шкалы нет`() {
        val scale = requireNotNull(VerticalScale.of(listOf(null, 5_000L, null)))

        assertEquals(0L, scale.minValueMinor)
        assertEquals(5_000L, scale.maxValueMinor)
        assertNull(VerticalScale.of(listOf<Long?>(null, null)))
        assertNull(VerticalScale.of(emptyList<Long?>()))
    }

    @Test
    fun `одинаковые положительные значения дают шкалу от нуля до значения`() {
        val scale = requireNotNull(VerticalScale.of(listOf(20_000L, 20_000L)))

        // Ноль включается в диапазон до проверки на вырожденность, поэтому шкала
        // не схлопывается в точку: 0..20 000 с шагом 5 000.
        assertEquals(0L, scale.minValueMinor)
        assertEquals(20_000L, scale.maxValueMinor)
        assertEquals(5_000L, scale.stepMinor)
        assertTrue(scale.maxValueMinor > scale.minValueMinor)
        assertTrue(20_000L in scale.minValueMinor..scale.maxValueMinor)
    }

    @Test
    fun `все нули дают симметричную шкалу вокруг нуля`() {
        val scale = requireNotNull(VerticalScale.of(listOf(0L, 0L)))

        assertEquals(-10_000L, scale.minValueMinor)
        assertEquals(10_000L, scale.maxValueMinor)
        assertEquals(0.5f, scale.zeroFraction)
    }

    @Test
    fun `доля значения считается от нижней границы и ограничивается диапазоном`() {
        val scale = VerticalScale(minValueMinor = 0L, maxValueMinor = 100_000L, stepMinor = 25_000L)

        assertEquals(0f, scale.fractionOf(0L))
        assertEquals(0.5f, scale.fractionOf(50_000L))
        assertEquals(1f, scale.fractionOf(100_000L))
        assertEquals(0f, scale.fractionOf(-1L))
        assertEquals(1f, scale.fractionOf(100_001L))
        assertEquals(0f, scale.zeroFraction)
    }

    @Test
    fun `нулевая линия на шкале с отрицательными значениями лежит в середине`() {
        val scale = VerticalScale(minValueMinor = -100_000L, maxValueMinor = 100_000L, stepMinor = 50_000L)

        assertEquals(0.5f, scale.zeroFraction)
    }

    @Test
    fun `шкала по нескольким сериям учитывает все точки`() {
        val series = listOf(
            ChartSeries(id = "a", title = "A", colorHex = "#000000", points = listOf(
                ChartPoint(label = "01", valueMinor = 10_000L),
                ChartPoint(label = "02", valueMinor = null),
            )),
            ChartSeries(id = "b", title = "B", colorHex = "#111111", points = listOf(
                ChartPoint(label = "01", valueMinor = -5_000L),
                ChartPoint(label = "02", valueMinor = 20_000L),
            )),
        )

        val scale = requireNotNull(VerticalScale.of(series))

        assertEquals(-10_000L, scale.minValueMinor)
        assertEquals(20_000L, scale.maxValueMinor)
        assertEquals(10_000L, scale.stepMinor)
    }

    @Test
    fun `некорректная шкала и число подписей отвергаются`() {
        assertFailsWith<IllegalArgumentException> {
            VerticalScale(minValueMinor = 10L, maxValueMinor = 0L, stepMinor = 5L)
        }
        assertFailsWith<IllegalArgumentException> {
            VerticalScale(minValueMinor = 0L, maxValueMinor = 10L, stepMinor = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            VerticalScale.of(listOf(1_000L), tickCount = 1)
        }
    }
}
