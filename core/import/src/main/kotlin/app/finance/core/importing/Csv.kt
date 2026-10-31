package app.finance.core.importing

/**
 * Разбор CSV по RFC 4180: кавычки, удвоенные кавычки внутри поля, переводы строк внутри
 * кавычек, CRLF и LF. Notion пишет UTF-8 с BOM и запятую как разделитель; точку с запятой
 * поддерживаем на случай, если файл пересохраняли в Excel с русской локалью.
 */
object Csv {

    private const val BOM = '﻿'

    /** Разделитель определяется по первой строке вне кавычек: чего больше — то и он. */
    fun detectDelimiter(text: String): Char {
        var commas = 0
        var semicolons = 0
        var quoted = false
        for (ch in text.removePrefix(BOM.toString())) {
            when {
                ch == '"' -> quoted = !quoted
                quoted -> Unit
                ch == '\n' || ch == '\r' -> break
                ch == ',' -> commas++
                ch == ';' -> semicolons++
            }
        }
        return if (semicolons > commas) ';' else ','
    }

    /**
     * Все строки файла, включая заголовок. Отбрасываются только пустые строки текста;
     * строка из одних разделителей (`,,,`) остаётся — иначе сбились бы номера строк,
     * по которым пользователь ищет запись в Notion.
     */
    fun parse(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val source = text.removePrefix(BOM.toString())
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0

        fun endField() {
            row += field.toString()
            field.setLength(0)
        }

        fun endRow() {
            endField()
            if (row.size > 1 || row.any { it.isNotEmpty() }) rows += row
            row = mutableListOf()
        }

        while (i < source.length) {
            val ch = source[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < source.length && source[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> quoted = true
                    delimiter -> endField()
                    '\r' -> {
                        if (i + 1 < source.length && source[i + 1] == '\n') i++
                        endRow()
                    }
                    '\n' -> endRow()
                    else -> field.append(ch)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
