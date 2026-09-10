package app.finance.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Деньги в минорных единицах (копейки). `Double`/`Float` для денег запрещены
 * (SPEC.md §5 и §9, правило detekt).
 *
 * Арифметика проверяет переполнение: лучше исключение, чем молча неверная сумма.
 */
data class Money(val minor: Long, val currency: String = DEFAULT_CURRENCY) : Comparable<Money> {

    init {
        require(currency.isNotBlank()) { "Код валюты не может быть пустым" }
    }

    operator fun plus(other: Money): Money =
        sameCurrency(other) { Money(Math.addExact(minor, other.minor), currency) }

    operator fun minus(other: Money): Money =
        sameCurrency(other) { Money(Math.subtractExact(minor, other.minor), currency) }

    operator fun unaryMinus(): Money = Money(Math.negateExact(minor), currency)

    fun isZero(): Boolean = minor == 0L

    fun isPositive(): Boolean = minor > 0L

    fun isNegative(): Boolean = minor < 0L

    /** Точное строковое представление: 12345 -> "123.45". Разделители разрядов добавляет UI. */
    fun toPlainString(): String =
        BigDecimal.valueOf(minor).movePointLeft(SCALE_DIGITS).toPlainString()

    override fun compareTo(other: Money): Int = sameCurrency(other) { minor.compareTo(other.minor) }

    override fun toString(): String = "${toPlainString()} $currency"

    private inline fun <T> sameCurrency(other: Money, block: () -> T): T {
        require(currency == other.currency) {
            "Операция над разными валютами: $currency и ${other.currency}"
        }
        return block()
    }

    companion object {
        const val DEFAULT_CURRENCY = "RUB"
        const val MINOR_PER_UNIT = 100L
        private const val SCALE_DIGITS = 2

        val ZERO: Money = Money(0L)

        /** Из целых единиц: ofUnits(150) == Money(15000). */
        fun ofUnits(units: Long, currency: String = DEFAULT_CURRENCY): Money =
            Money(Math.multiplyExact(units, MINOR_PER_UNIT), currency)

        /**
         * Разбор пользовательского ввода: "1500", "1500.5", "1 500,50", "-200.10".
         * Округление до копеек — HALF_UP. Пустая строка -> null, а не ноль:
         * отсутствие ввода не должно превращаться в трату.
         */
        fun parse(input: String, currency: String = DEFAULT_CURRENCY): Money? {
            val normalized = input.trim()
                .replace("\u00A0", "")
                .replace(" ", "")
                .replace(",", ".")
            if (normalized.isEmpty()) return null
            val decimal = runCatching { BigDecimal(normalized) }.getOrNull() ?: return null
            val minor = decimal.movePointRight(SCALE_DIGITS)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
            return Money(minor, currency)
        }
    }
}
