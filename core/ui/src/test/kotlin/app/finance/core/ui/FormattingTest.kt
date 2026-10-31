package app.finance.core.ui

import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import androidx.compose.ui.graphics.Color
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormattingTest {

    private val nbsp = "\u00A0"

    @Test
    fun `сумма форматируется с разрядами и запятой`() {
        assertEquals("1${nbsp}234,50 ₽", Money(123_450).formatRu())
        assertEquals("0,99 ₽", Money(99).formatRu())
        assertEquals("0,00 ₽", Money.ZERO.formatRu())
        assertEquals("1${nbsp}000${nbsp}000,00 ₽", Money(100_000_000).formatRu())
        assertEquals("-500,00 ₽", Money(-50_000).formatRu())
    }

    @Test
    fun `неизвестная валюта показывается кодом, известные - символом`() {
        assertEquals("10,00 \$", Money(1_000, "USD").formatRu())
        assertEquals("10,00 €", Money(1_000, "EUR").formatRu())
        assertEquals("10,00 XYZ", Money(1_000, "XYZ").formatRu())
    }

    @Test
    fun `знак дельты явно виден, ноль без знака`() {
        assertEquals("+1${nbsp}200,00 ₽", Money(120_000).formatSignedRu())
        // U+2212, а не дефис: знак и разряды в таблице совпадают визуально.
        assertEquals("\u2212300,00 ₽", Money(-30_000).formatSignedRu())
        assertEquals("0,00 ₽", Money.ZERO.formatSignedRu())
    }

    @Test
    fun `доля считается целочисленно с округлением HALF_UP`() {
        assertEquals(25, sharePercent(Money(5_000), Money(20_000)))
        assertEquals(33, sharePercent(Money(1_000), Money(3_000)))
        assertEquals(17, sharePercent(Money(1_000), Money(6_000)))
        assertEquals(100, sharePercent(Money(20_000), Money(20_000)))
        assertEquals(-17, sharePercent(Money(-1_000), Money(6_000)))
    }

    @Test
    fun `доля не определена при нулевом итоге и разных валютах`() {
        assertNull(sharePercent(Money(1_000), Money.ZERO))
        assertNull(sharePercent(Money(1_000), Money(-1_000)))
        assertNull(sharePercent(Money(1_000, "USD"), Money(10_000, "RUB")))
    }

    @Test
    fun `дельта к прошлому месяцу в процентах`() {
        assertEquals(20, deltaPercent(Money(120_000), Money(100_000)))
        assertEquals(-20, deltaPercent(Money(80_000), Money(100_000)))
        assertEquals(0, deltaPercent(Money(100_000), Money(100_000)))
    }

    @Test
    fun `дельта не определена, если прошлый месяц пустой`() {
        assertNull(deltaPercent(Money(120_000), Money.ZERO))
        assertNull(deltaPercent(Money(120_000), Money(100_000, "USD")))
    }

    @Test
    fun `период имеет человекочитаемый заголовок`() {
        assertEquals("Февраль 2026", Period(2026, 2).titleRu())
        assertEquals("Декабрь 2025", Period(2025, 12).titleRu())
        assertEquals("02.2026", Period(2026, 2).shortRu())
        assertEquals("Фев 2026", Period(2026, 2).chartLabelRu())
        assertEquals("Сен 2026", Period(2026, 9).chartLabelRu())
    }

    @Test
    fun `названия разделов`() {
        assertEquals("Расходы", LedgerSection.EXPENSE.titleRu())
        assertEquals("Доходы", LedgerSection.INCOME.titleRu())
    }

    @Test
    fun `метка времени ревизии показывается в заданном поясе`() {
        val millis = LocalDateTime.of(2026, 2, 9, 14, 35).toInstant(ZoneOffset.UTC).toEpochMilli()

        assertEquals("09.02.2026 14:35", formatTimestamp(millis, ZoneOffset.UTC))
        assertEquals(
            "09.02.2026 17:35",
            formatTimestamp(millis, ZoneOffset.ofHours(3)),
            "Время в БД хранится в UTC, пользователь видит его в своём поясе",
        )
    }

    @Test
    fun `цвет категории разбирается, а мусор не роняет экран`() {
        assertEquals(Color(0xFF4E79A7), parseHexColor("#4E79A7"))
        assertEquals(Color(0xFF4E79A7), parseHexColor("4e79a7"))
        assertEquals(MutedColor, parseHexColor("oops"))
        assertEquals(MutedColor, parseHexColor("#FFF"))
        assertEquals(MutedColor, parseHexColor(""))
    }

    @Test
    fun `маленькая доля показывается как меньше процента, а не ноль`() {
        assertEquals("<1 %", shareLabelRu(0, Money(55_000)))
        assertEquals("0 %", shareLabelRu(0, Money(0)))
        assertEquals("38 %", shareLabelRu(38, Money(3_240_000)))
        assertEquals("—", shareLabelRu(null, Money(100)))
    }
}
