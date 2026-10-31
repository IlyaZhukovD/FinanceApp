package app.finance.core.importing

import app.finance.core.model.Money
import app.finance.core.model.Period
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Golden-тесты правил docs/IMPORT-NOTION.md §1.3 и §3 на обезличенных данных в формате
 * настоящего экспорта Notion (заголовок и формат дат взяты из экспорта владельца).
 */
class NotionRowParserTest {

    private val expensesCsv = """
        |Name,Date,Number,Год,Категория,Месяц
        |,"January 1, 2025 → January 31, 2025",17000,2025,на покушать,Январь
        |полки + пылесос,,19500,,дом,Октябрь
        |,"March 1, 2025 → March 31, 2025",1600,2025,транспорт,Февраль
        |гидрики,"July 1, 2025",5500,2025,"развлечение, гидрики",Июль
        |,,,,,
        |,"June 1, 2025",,2025,спорт,июнь
        |,"June 1, 2025",300,2025,,июнь
        |,"June 1, 2025",0,2025,спорт,июнь
        |,"June 1, 2025",17000,2025,на покушать,Январь
        |,"January 1, 2025 → January 31, 2025",17000,2025,на покушать,Январь
    """.trimMargin()

    private val incomeCsv = """
        |Name,Number,Date
        |,186472,"September 1, 2026"
        |,170000,"January 1, 2026 → January 31, 2026"
    """.trimMargin()

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun loadExpenses(fileName: String = "notion-траты-2026.zip"): SourceTable {
        val bom = "﻿"
        val inner = zip(
            mapOf(
                "2025 feb30cd67f1183f8a20d81558ebdb442.md" to "# 2025".toByteArray(),
                "Траты f0a30cd67f11833096ed8183a305b284.csv" to (bom + "Number,Name\n1,урезанный вид").toByteArray(),
                "Траты f0a30cd67f11833096ed8183a305b284_all.csv" to (bom + expensesCsv).toByteArray(),
            ),
        )
        val outer = zip(mapOf("ExportBlock-x-Part-1.zip" to inner))
        val loaded = assertIs<SourceFileResult.Loaded>(NotionExportReader.read(fileName, outer))
        return loaded.tables.single()
    }

    @Test
    fun `из вложенного ZIP берётся _all-таблица с id базы`() {
        val table = loadExpenses()
        assertEquals("Траты", table.tableName)
        assertEquals("f0a30cd67f11833096ed8183a305b284", table.tableId)
        assertEquals(listOf("Name", "Date", "Number", "Год", "Категория", "Месяц"), table.header)
        assertEquals(10, table.rows.size)
    }

    @Test
    fun `структура распознаётся по колонкам`() {
        val table = loadExpenses()
        val mapping = NotionTableDetector.detectMapping(table.header)
        assertEquals(2, mapping[ImportField.AMOUNT])
        assertEquals(4, mapping[ImportField.CATEGORY])
        assertEquals(TableTarget.EXPENSE, NotionTableDetector.detectTarget(table.header))
        assertEquals(TableTarget.INCOME, NotionTableDetector.detectTarget(listOf("Name", "Number", "Date")))
        assertEquals(TableTarget.SKIP, NotionTableDetector.detectTarget(listOf("Name", "Number", "Select", "Text")))
        assertEquals(2026, NotionTableDetector.defaultYear(table, mapping, fallback = 2030))
        val unnamed = table.copy(fileName = "ExportBlock.zip")
        assertEquals(2025, NotionTableDetector.defaultYear(unnamed, mapping, fallback = 2030))
    }

    @Test
    fun `правила строк расходов`() {
        val table = loadExpenses()
        val rows = NotionRowParser.parse(table, NotionTableDetector.detectMapping(table.header), TableTarget.EXPENSE, defaultYear = 2026)
        assertEquals(10, rows.size)

        rows[0].let {
            assertEquals(RowStatus.OK, it.status)
            assertEquals(Period(2025, 1), it.period)
            assertEquals(Money(1_700_000), it.amount)
            assertEquals("на покушать", it.category)
            assertEquals(2, it.rowNumber)
        }
        // Нет года и даты — год по умолчанию, предупреждение.
        rows[1].let {
            assertEquals(RowStatus.WARNING, it.status)
            assertEquals(Period(2026, 10), it.period)
            assertEquals("полки + пылесос", it.comment)
        }
        // «Месяц» главнее даты.
        rows[2].let {
            assertEquals(RowStatus.WARNING, it.status)
            assertEquals(Period(2025, 2), it.period)
            assertTrue(it.messages.single().contains("расходятся"))
        }
        // Две категории: первая основная, вторая в комментарий.
        rows[3].let {
            assertEquals(RowStatus.WARNING, it.status)
            assertEquals("развлечение", it.category)
            assertEquals("гидрики #гидрики", it.comment)
        }
        assertEquals(RowStatus.EMPTY, rows[4].status)
        assertEquals(RowStatus.ERROR, rows[5].status)
        assertEquals("нет суммы", rows[5].messages.single())
        rows[6].let {
            assertEquals(RowStatus.WARNING, it.status)
            assertEquals(FALLBACK_CATEGORY, it.category)
            assertEquals(Period(2025, 6), it.period)
        }
        assertEquals(RowStatus.ZERO, rows[7].status)
    }

    @Test
    fun `ключи строк детерминированы и различают одинаковые строки`() {
        val table = loadExpenses()
        val mapping = NotionTableDetector.detectMapping(table.header)
        val first = NotionRowParser.parse(table, mapping, TableTarget.EXPENSE, 2026)
        val second = NotionRowParser.parse(loadExpenses(), mapping, TableTarget.EXPENSE, 2026)
        assertEquals(first.map { it.key }, second.map { it.key })
        // Строки 1 и 10 совпадают полностью, но это две разные траты.
        assertNotEquals(first[0].key, first[9].key)
        assertEquals(first.size, first.map { it.key }.toSet().size)
        // Ключ не зависит от имени файла: переименованный ZIP — тот же экспорт.
        val renamed = NotionRowParser.parse(loadExpenses("другое-имя.zip"), mapping, TableTarget.EXPENSE, 2026)
        assertEquals(first.map { it.key }, renamed.map { it.key })
    }

    @Test
    fun `доходы без категорий`() {
        val loaded = assertIs<SourceFileResult.Loaded>(NotionExportReader.read("Доход.csv", incomeCsv.toByteArray()))
        val table = loaded.tables.single()
        val rows = NotionRowParser.parse(table, NotionTableDetector.detectMapping(table.header), TableTarget.INCOME, 2026)
        assertEquals(listOf(Period(2026, 9), Period(2026, 1)), rows.map { it.period })
        assertTrue(rows.all { it.status == RowStatus.OK && it.category == null })
    }

    @Test
    fun `не экспорт Notion — понятная ошибка`() {
        assertIs<SourceFileResult.Failed>(NotionExportReader.read("x.pdf", byteArrayOf(1)))
        assertIs<SourceFileResult.Failed>(NotionExportReader.read("x.zip", zip(mapOf("a.md" to byteArrayOf(1)))))
    }

    @Test
    fun `похожие категории`() {
        val pairs = CategorySimilarity.similarPairs(listOf("маме", "мама", "дом", "на дом", "такси", "спорт"))
        assertEquals(listOf("маме" to "мама", "дом" to "на дом"), pairs)
    }
}
