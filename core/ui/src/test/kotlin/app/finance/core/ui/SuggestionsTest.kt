package app.finance.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SuggestionsTest {

    private val categories = listOf(
        "Продукты", "Транспорт", "Жильё и ЖКХ", "Кафе и рестораны", "Здоровье",
        "Развлечения", "Одежда", "Связь и подписки", "Образование", "Путешествия", "Прочее",
    )

    private fun suggest(query: String) = rankSuggestions(categories, query) { it }

    @Test
    fun `пустой запрос возвращает весь список в исходном порядке`() {
        assertEquals(categories, suggest(""))
        assertEquals(categories, suggest("   "))
    }

    @Test
    fun `совпадение с начала имени идёт первым, затем начало слова, затем подстрока`() {
        // «Продукты» и «Прочее» — с начала имени; «Путешествия» не подходит.
        assertEquals(listOf("Продукты", "Прочее"), suggest("про"))
        // Сначала имена на «п», затем «Связь и подписки» (с начала слова), затем «Транспорт»
        // (буква внутри слова); внутри группы — исходный порядок списка.
        assertEquals(
            listOf("Продукты", "Путешествия", "Прочее", "Связь и подписки", "Транспорт"),
            suggest("п"),
        )
        assertEquals(listOf("Связь и подписки", "Транспорт"), suggest("по"))
        // «Транспорт» — подстрока «спорт» внутри слова.
        assertEquals(listOf("Транспорт"), suggest("спорт"))
    }

    @Test
    fun `регистр и ё не важны`() {
        assertEquals(listOf("Жильё и ЖКХ"), suggest("ЖИЛЬЕ"))
        assertEquals(listOf("Жильё и ЖКХ"), suggest("жкх"))
        assertEquals(listOf("Здоровье"), suggest("здоровьё"))
    }

    @Test
    fun `ничего не найдено - пустой список`() {
        assertEquals(emptyList(), suggest("такси"))
    }

    @Test
    fun `диапазон совпадения указывает на исходное имя`() {
        assertEquals(8..10, matchRange("Жильё и ЖКХ", "жкх"))
        assertEquals(3..4, matchRange("Жильё", "ье"))
        assertNull(matchRange("Продукты", "такси"))
        assertNull(matchRange("Продукты", ""))
    }
}
