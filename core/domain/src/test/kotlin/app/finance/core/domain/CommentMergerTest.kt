package app.finance.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommentMergerTest {

    @Test
    fun `пусто с обеих сторон остаётся пустым`() {
        assertNull(CommentMerger.merge(null, null))
        assertNull(CommentMerger.merge("", "   "))
        assertNull(CommentMerger.merge("  ", null))
    }

    @Test
    fun `новый ввод без комментария сохраняет существующий текст`() {
        assertEquals("Большая закупка", CommentMerger.merge("Большая закупка", null))
        assertEquals("Большая закупка", CommentMerger.merge("Большая закупка", ""))
        assertEquals("Большая закупка", CommentMerger.merge("Большая закупка", "   "))
    }

    @Test
    fun `первый комментарий записывается, если его ещё не было`() {
        assertEquals("Отпуск", CommentMerger.merge(null, "Отпуск"))
        assertEquals("Отпуск", CommentMerger.merge("", "Отпуск"))
    }

    @Test
    fun `разные комментарии дописываются новой строкой`() {
        assertEquals(
            "Ремонт машины\nЕщё запчасти",
            CommentMerger.merge("Ремонт машины", "Ещё запчасти"),
        )
    }

    @Test
    fun `повтор того же текста не дублируется`() {
        assertEquals("Подписка", CommentMerger.merge("Подписка", "Подписка"))
        assertEquals("Подписка", CommentMerger.merge("Подписка", "  Подписка  "))
    }

    @Test
    fun `пробелы по краям нормализуются`() {
        assertEquals("Текст", CommentMerger.merge("  Текст  ", null))
        assertEquals("Текст", CommentMerger.merge(null, "  Текст  "))
    }

    @Test
    fun `явная замена не сливает текст`() {
        assertEquals("Новый", CommentMerger.replace("Новый"))
        assertNull(CommentMerger.replace("   "))
        assertNull(CommentMerger.replace(null))
    }

    @Test
    fun `счётчик строк нужен для индикатора и усечения в таблице`() {
        assertEquals(0, CommentMerger.lineCount(null))
        assertEquals(1, CommentMerger.lineCount("Одна"))
        assertEquals(2, CommentMerger.lineCount("Первая\nВторая"))
        assertEquals(3, CommentMerger.lineCount("Первая\nВторая\nТретья"))
    }
}
