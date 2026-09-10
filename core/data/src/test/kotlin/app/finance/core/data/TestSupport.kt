package app.finance.core.data

import app.finance.core.domain.IdGenerator
import app.finance.core.domain.TimeProvider

/** Фиксированное время: тесты не должны зависеть от часов машины. */
class FixedTime(var now: Long = 1_770_000_000_000L) : TimeProvider {
    override fun nowUtcMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

/** Последовательные идентификаторы: проще читать в падениях тестов, чем UUID. */
class SeqIds(private val prefix: String = "id") : IdGenerator {
    private var counter = 0

    override fun newId(): String = "$prefix-${++counter}"
}
