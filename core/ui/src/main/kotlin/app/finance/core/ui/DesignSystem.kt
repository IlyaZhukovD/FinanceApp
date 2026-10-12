package app.finance.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.finance.core.model.Category

/**
 * Токены дизайн-системы Finance (единый визуальный язык всех разделов).
 *
 * Всё, что здесь объявлено, — единственный источник размеров, цветов и отступов. Экраны
 * не должны заводить «свои» 13.dp или произвольные оттенки: иначе разделы разъезжаются,
 * а правка темы превращается в поиск по всему дереву.
 *
 * Шкала построена на 8px-сетке, светлая тема единственная (тёмная в v1 не поддерживается),
 * контраст текста подобран под WCAG AA.
 */

/**
 * Палитра.
 *
 * `Success` и `Error` семантические, а не «зелёный/красный для красоты»: они кодируют
 * направление изменения с учётом раздела — рост доходов и падение расходов одинаково
 * хороши, поэтому оба зелёные (см. [deltaColor]).
 */
@Immutable
object FinancePalette {
    val Primary = Color(0xFF1967D2)
    val PrimaryLight = Color(0xFFE8F0FE)
    val Success = Color(0xFF1E8E3E)
    val Error = Color(0xFFD93025)
    val Warning = Color(0xFFF9AB00)

    val TextPrimary = Color(0xFF202124)
    val TextSecondary = Color(0xFF5F6368)
    val TextDisabled = Color(0xFF9AA0A6)

    val Border = Color(0xFFE0E0E0)
    val Divider = Color(0xFFF1F3F4)
    val Background = Color(0xFFFFFFFF)
    val Surface = Color(0xFFF8F9FA)

    /** Подложка строки таблицы при наведении и через строку — едва заметная, не спорит с данными. */
    val RowHover = Color(0xFFF1F5FB)
    val RowAlternate = Color(0xFFFCFCFD)
}

/**
 * Палитра серий и категорий, безопасная для дейтеранопии.
 *
 * Категории различаются не только оттенком, но и светлотой, поэтому график читается и без
 * цвета. В круговой диаграмме показывается максимум восемь категорий: дальше хвост
 * объединяется в «Прочее» — иначе легенда и сектора превращаются в кашу.
 */
@Immutable
object ChartPalette {

    /**
     * Цвета в порядке назначения: первый — самой крупной категории, и так далее.
     *
     * Источник один на всё приложение — [Category.DEFAULT_PALETTE]. Два независимых списка
     * дали бы одну и ту же категорию разными оттенками в соседних графиках.
     */
    val Series: List<String> = Category.DEFAULT_PALETTE

    /** Цвет хвоста «Прочее»: нейтральный, чтобы не спорить с реальными категориями. */
    const val OTHER_HEX = "#9AA0A6"

    /** Цвет линии итога поверх столбцов. */
    const val TOTAL_HEX = "#202124"

    /**
     * Те же семантические цвета в HEX.
     *
     * Дубль [FinancePalette] вынужденный: модель графиков хранит строки, а не `Color`,
     * чтобы `core:charts` не тянул Compose-типы в данные. Значения обязаны совпадать —
     * расхождение дало бы два разных «зелёных» в одном экране.
     */
    const val PRIMARY_HEX = "#1967D2"
    const val SUCCESS_HEX = "#1E8E3E"
    const val ERROR_HEX = "#D93025"
    const val WARNING_HEX = "#F9AB00"

    fun colorAt(index: Int): String = Series[index % Series.size]
}

/** Отступы 8px-сетки. Половинный шаг [Xs] допустим только внутри плотных строк таблицы. */
@Immutable
object FinanceSpace {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
    val Xxl = 48.dp
}

/** Фиксированные высоты и толщины: единый ритм строк и контролов. */
@Immutable
object FinanceSize {
    val ControlHeight = 40.dp

    /**
     * Высота блока метки над полем: строка `Caption` 16px + отступ 4px.
     *
     * Фиксирована, потому что по ней выравниваются контролы без метки (кнопки, выпадающие
     * списки) в одной строке с полями ввода. Если метка «поедет» по высоте, кнопка снова
     * окажется между меткой и полем, а не на одной линии с ним.
     */
    val FieldLabelHeight = 20.dp

    val ChipHeight = 32.dp
    val TableRowHeight = 48.dp
    val SidebarWidth = 232.dp
    val DonutThickness = 32.dp
    val BarWidth = 48.dp
    val BarGap = 12.dp
    val LineStroke = 2.dp
    val LegendDot = 10.dp
}

/** Радиусы: 8px у контролов, 12px у карточек, 20px у чипсов — и никаких исключений. */
@Immutable
object FinanceRadius {
    val Control = 8.dp
    val Card = 12.dp
    val Chip = 20.dp

    val controlShape = RoundedCornerShape(Control)
    val cardShape = RoundedCornerShape(Card)
    val chipShape = RoundedCornerShape(Chip)
}

/** Длительности анимаций в миллисекундах. Всё, что дольше, на десктопе ощущается вязким. */
@Immutable
object FinanceMotion {
    const val HOVER_MS = 150
    const val CLICK_MS = 100
    const val TRANSITION_MS = 200
}

/**
 * Табличные цифры (`tnum`): разряды одинаковой ширины, поэтому колонки сумм не «пилят»
 * и значения сравниваются глазом по вертикали. Системные шрифты macOS и Windows `tnum`
 * поддерживают; если нет — фича просто игнорируется.
 */
private const val TABULAR_NUMBERS = "tnum"

private val SansSerif = FontFamily.SansSerif

/**
 * Типографическая шкала.
 *
 * Стили намеренно не задают цвет: он наследуется от `LocalContentColor`, поэтому тот же
 * `Caption` корректен и на белой карточке, и внутри цветной панели уведомления. Нужный
 * оттенок экран указывает явно — чаще всего [FinancePalette.TextSecondary].
 */

/** Заголовок раздела: H1. */
val H1 = TextStyle(
    fontFamily = SansSerif,
    fontSize = 24.sp,
    lineHeight = 32.sp,
    fontWeight = FontWeight.Bold,
)

/** Заголовок карточки: H2. */
val H2 = TextStyle(
    fontFamily = SansSerif,
    fontSize = 20.sp,
    lineHeight = 28.sp,
    fontWeight = FontWeight.Medium,
)

/** Подзаголовок и название группы: H3. */
val H3 = TextStyle(
    fontFamily = SansSerif,
    fontSize = 16.sp,
    lineHeight = 24.sp,
    fontWeight = FontWeight.Medium,
)

/** Основной текст. */
val Body = TextStyle(
    fontFamily = SansSerif,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.Normal,
)

/** Основной текст с акцентом: значения в таблицах, важные строки. */
val BodyStrong = Body.copy(fontWeight = FontWeight.Medium)

/** Подписи, мета-информация, заголовки столбцов. */
val Caption = TextStyle(
    fontFamily = SansSerif,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Normal,
)

/** Подписи с акцентом: метки кнопок, активные пункты. */
val CaptionStrong = Caption.copy(fontWeight = FontWeight.Medium)

/** Денежные значения: те же 14/20, но medium и табличные цифры. */
val Numbers = BodyStrong.copy(fontFeatureSettings = TABULAR_NUMBERS)

/**
 * Крупное число-герой (Net Worth на экране активов).
 *
 * Вне базовой шкалы намеренно: это единственная витринная величина приложения, и она
 * обязана читаться первой. Табличные цифры обязательны — число меняется при навигации.
 */
val HeroNumber = TextStyle(
    fontFamily = SansSerif,
    fontSize = 32.sp,
    lineHeight = 40.sp,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = TABULAR_NUMBERS,
)

/**
 * Типографика Material 3, сопоставленная с нашей шкалой.
 *
 * Экраны исторически обращаются к ролям M3 (`bodyMedium`, `titleMedium`, …), поэтому
 * шкала задана здесь один раз: все разделы получают одинаковые размеры без правки каждого
 * `Text`. Новые компоненты берут токены выше напрямую — они читаются как спецификация.
 */
val FinanceTypography = Typography(
    headlineMedium = H1,
    headlineSmall = H1,
    titleLarge = H2,
    titleMedium = H2,
    titleSmall = H3,
    bodyLarge = BodyStrong,
    bodyMedium = Body,
    bodySmall = Caption,
    labelLarge = CaptionStrong,
    labelMedium = CaptionStrong,
    labelSmall = Caption,
)

/** Формы M3: `medium` — карточка, `small` — контрол, `extraLarge` — чипс. */
val FinanceShapes = Shapes(
    extraSmall = RoundedCornerShape(FinanceSpace.Xs),
    small = FinanceRadius.controlShape,
    medium = FinanceRadius.cardShape,
    large = FinanceRadius.chipShape,
    extraLarge = FinanceRadius.chipShape,
)
