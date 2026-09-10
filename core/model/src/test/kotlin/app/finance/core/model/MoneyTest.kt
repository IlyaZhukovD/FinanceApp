package app.finance.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun `ofUnits переводит рубли в копейки`() {
        assertEquals(150_000L, Money.ofUnits(1500).minor)
        assertEquals(0L, Money.ofUnits(0).minor)
        assertEquals(-200L, Money.ofUnits(-2, currency = "USD").minor)
    }

    @Test
    fun `сложение аккумулирует сумму в той же валюте`() {
        val cell = Money(32_400) + Money(1_000)
        assertEquals(33_400L, cell.minor)
        assertEquals("RUB", cell.currency)
    }

    @Test
    fun `вычитание и смена знака`() {
        assertEquals(2_000L, (Money(5_000) - Money(3_000)).minor)
        assertEquals(-5_000L, (-Money(5_000)).minor)
        assertEquals(Money(5_000), -(-Money(5_000)))
    }

    @Test
    fun `операции с разными валютами запрещены`() {
        val error = assertFailsWith<IllegalArgumentException> { Money(100) + Money(100, "USD") }
        // Проверяем суть, а не точную формулировку: сообщение должно называть обе валюты.
        val message = error.message.orEmpty()
        assertTrue(message.contains("RUB") && message.contains("USD"), "Сообщение должно называть обе валюты: $message")
    }

    @Test
    fun `переполнение не проходит молча`() {
        assertFailsWith<ArithmeticException> { Money(Long.MAX_VALUE) + Money(1) }
        assertFailsWith<ArithmeticException> { Money(Long.MIN_VALUE) - Money(1) }
        assertFailsWith<ArithmeticException> { -Money(Long.MIN_VALUE) }
    }

    @Test
    fun `пустой код валюты отклоняется`() {
        assertFailsWith<IllegalArgumentException> { Money(100, " ") }
    }

    @Test
    fun `toPlainString точен для положительных и отрицательных копеек`() {
        assertEquals("123.45", Money(12_345).toPlainString())
        assertEquals("-123.45", Money(-12_345).toPlainString())
        assertEquals("0.05", Money(5).toPlainString())
        assertEquals("-0.05", Money(-5).toPlainString())
        assertEquals("0.00", Money.ZERO.toPlainString())
    }

    @Test
    fun `parse принимает обычный и русский ввод`() {
        assertEquals(150_000L, Money.parse("1500")?.minor)
        assertEquals(150_050L, Money.parse("1500.5")?.minor)
        assertEquals(150_050L, Money.parse("1 500,50")?.minor)
        assertEquals(150_050L, Money.parse("  1\u00A0500,50  ")?.minor)
        assertEquals(-20_010L, Money.parse("-200.10")?.minor)
        assertEquals("USD", Money.parse("10", "USD")?.currency)
    }

    @Test
    fun `parse округляет до копеек HALF_UP`() {
        assertEquals(1L, Money.parse("0.005")?.minor)
        assertEquals(0L, Money.parse("0.004")?.minor)
        assertEquals(-1L, Money.parse("-0.005")?.minor)
    }

    @Test
    fun `parse возвращает null на пустом и мусорном вводе, а не ноль`() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("   "))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("1.2.3"))
    }

    @Test
    fun `предикаты знака`() {
        assertTrue(Money.ZERO.isZero())
        assertTrue(Money(1).isPositive())
        assertFalse(Money(1).isNegative())
        assertTrue(Money(-1).isNegative())
        assertFalse(Money(-1).isZero())
    }

    @Test
    fun `сравнение работает внутри одной валюты`() {
        assertTrue(Money(100) < Money(200))
        assertEquals(0, Money(100).compareTo(Money(100)))
        assertFailsWith<IllegalArgumentException> { Money(100).compareTo(Money(100, "USD")) }
    }
}
