package app.finance.core.importing

import app.finance.core.model.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class NotionValuesTest {

    @Test
    fun `месяцы по-русски в любом регистре и номером`() {
        assertEquals(6, NotionValues.month("июнь"))
        assertEquals(6, NotionValues.month("Июнь"))
        assertEquals(10, NotionValues.month(" Октябрь "))
        assertEquals(3, NotionValues.month("3"))
        assertNull(NotionValues.month("13"))
        assertNull(NotionValues.month("Смарт"))
        assertNull(NotionValues.month(""))
    }

    @Test
    fun `даты в форматах Notion`() {
        val oct1 = LocalDate.of(2026, 10, 1)
        assertEquals(oct1, NotionValues.date("2026-10-01"))
        assertEquals(oct1, NotionValues.date("October 1, 2026"))
        assertEquals(oct1, NotionValues.date("Oct 1, 2026"))
        assertEquals(oct1, NotionValues.date("1 октября 2026 г."))
        assertEquals(oct1, NotionValues.date("01.10.2026"))
        assertEquals(oct1, NotionValues.date("October 1, 2026 10:30 AM"))
        // Реальный формат экспорта владельца: диапазон на весь месяц.
        assertEquals(LocalDate.of(2025, 1, 1), NotionValues.date("January 1, 2025 → January 31, 2025"))
        assertNull(NotionValues.date("вчера"))
        assertNull(NotionValues.date("February 30, 2025"))
    }

    @Test
    fun `суммы без плавающей точки`() {
        fun minor(text: String) = assertIs<NotionValues.AmountResult.Value>(NotionValues.amount(text)).money.minor
        assertEquals(1_950_000, minor("19500"))
        assertEquals(1_950_000, minor("19 500"))
        assertEquals(1_950_000, minor("19 500"))
        assertEquals(1_950_000, minor("19,500"))
        assertEquals(1_950_050, minor("₽19,500.50"))
        assertEquals(150_050, minor("1 500,5 руб"))
        assertEquals(150_000_000, minor("1.500.000"))
        assertEquals(-20_000, minor("-200"))
        assertEquals(1_950, minor("19.5"))
        assertEquals(NotionValues.AmountResult.Empty, NotionValues.amount("  "))
        assertIs<NotionValues.AmountResult.Invalid>(NotionValues.amount("12.345"))
        assertIs<NotionValues.AmountResult.Invalid>(NotionValues.amount("много"))
        assertEquals(Money.DEFAULT_CURRENCY, assertIs<NotionValues.AmountResult.Value>(NotionValues.amount("1")).money.currency)
    }

    @Test
    fun `мультиселект сохраняет порядок`() {
        assertEquals(listOf("развлечение", "гидрики"), NotionValues.multiSelect("развлечение, гидрики"))
        assertEquals(emptyList(), NotionValues.multiSelect(""))
        assertEquals(listOf("дом"), NotionValues.multiSelect("дом, дом"))
    }

    @Test
    fun `год из имени файла`() {
        assertEquals(2026, NotionValues.yearIn("notion-траты-2026.zip"))
        assertNull(NotionValues.yearIn("ExportBlock-a7259a00.zip"))
    }

    @Test
    fun `CSV с кавычками, переводами строк и BOM`() {
        val text = "﻿Name,Number\r\n\"a, b\",1\r\n\"мно\ngo \"\"x\"\"\",2\r\n,,\r\n"
        val rows = Csv.parse(text)
        // Пустая строка текста пропускается, строка из одних разделителей — нет (номера строк).
        assertEquals(
            listOf(listOf("Name", "Number"), listOf("a, b", "1"), listOf("мно\ngo \"x\"", "2"), listOf("", "", "")),
            rows,
        )
        assertEquals(';', Csv.detectDelimiter("a;b;c\n1;2;3"))
        assertEquals(',', Csv.detectDelimiter("\"a;b\",c\n"))
    }
}
