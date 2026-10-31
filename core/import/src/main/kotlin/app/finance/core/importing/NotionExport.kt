package app.finance.core.importing

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Таблица из экспорта Notion: одна база данных = один CSV.
 *
 * @property fileName имя файла, который выбрал пользователь (ZIP или CSV).
 * @property tableName имя базы без id и суффикса, например «Траты».
 * @property tableId 32-символьный id базы из имени CSV, если он там есть; иначе имя базы.
 *   Входит в ключ строки: две базы с одинаковыми строками не должны гасить друг друга.
 */
data class SourceTable(
    val fileName: String,
    val fileSha256: String,
    val tableName: String,
    val tableId: String,
    val header: List<String>,
    val rows: List<List<String>>,
) {
    /** Значение колонки строки; короткие строки дополняются пустыми значениями. */
    fun cell(row: List<String>, column: Int?): String =
        if (column == null || column !in row.indices) "" else row[column].trim()
}

/** Результат чтения файла: таблицы или понятная ошибка — без исключений наружу. */
sealed interface SourceFileResult {
    val fileName: String

    data class Loaded(override val fileName: String, val sha256: String, val tables: List<SourceTable>) :
        SourceFileResult

    data class Failed(override val fileName: String, val message: String) : SourceFileResult
}

/**
 * Чтение того, что сохраняет «Экспорт → Markdown & CSV» в Notion (docs/IMPORT-NOTION.md §2).
 *
 * Принимает ZIP как есть (в том числе ZIP внутри ZIP: Notion кладёт большие экспорты
 * частями `…-Part-1.zip`) или отдельный CSV. Из пары `Траты <id>.csv` и
 * `Траты <id>_all.csv` берётся `_all`: в нём все строки и колонки, а не только текущий вид.
 * Markdown-страницы и вложения пропускаются.
 */
object NotionExportReader {

    private const val MAX_NESTING = 3
    private val ALL_SUFFIX = Regex("_all$", RegexOption.IGNORE_CASE)
    private val NOTION_ID = Regex("""\s([0-9a-f]{32})$""", RegexOption.IGNORE_CASE)

    fun read(path: Path): SourceFileResult {
        val fileName = path.fileName.toString()
        return runCatching { read(fileName, Files.readAllBytes(path)) }
            .getOrElse { SourceFileResult.Failed(fileName, "Не удалось прочитать файл: ${it.message ?: it.javaClass.simpleName}") }
    }

    fun read(fileName: String, bytes: ByteArray): SourceFileResult {
        val sha = sha256(bytes)
        val csvFiles = linkedMapOf<String, ByteArray>()
        when {
            fileName.endsWith(".zip", ignoreCase = true) -> try {
                collectCsv(ByteArrayInputStream(bytes), csvFiles, depth = 0, Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                // Имена файлов не в UTF-8 (архив пересобран сторонней программой).
                csvFiles.clear()
                collectCsv(ByteArrayInputStream(bytes), csvFiles, depth = 0, Charset.forName("CP866"))
            }
            fileName.endsWith(".csv", ignoreCase = true) -> csvFiles[fileName] = bytes
            else -> return SourceFileResult.Failed(fileName, "Поддерживаются только файлы .zip и .csv из экспорта Notion")
        }
        if (csvFiles.isEmpty()) {
            return SourceFileResult.Failed(
                fileName,
                "В архиве нет таблиц CSV. В Notion выберите формат «Markdown & CSV».",
            )
        }

        val tables = pickPreferred(csvFiles).mapNotNull { (entry, content) ->
            val text = decode(content)
            val rows = Csv.parse(text)
            if (rows.isEmpty()) return@mapNotNull null
            val baseName = entry.substringAfterLast('/').removeSuffix(".csv").removeSuffix(".CSV")
                .replace(ALL_SUFFIX, "")
            val id = NOTION_ID.find(baseName)?.groupValues?.get(1)?.lowercase()
            val name = (if (id != null) baseName.removeRange(NOTION_ID.find(baseName)!!.range) else baseName).trim()
            SourceTable(
                fileName = fileName,
                fileSha256 = sha,
                tableName = name.ifEmpty { fileName },
                tableId = id ?: name.ifEmpty { fileName },
                header = rows.first().map { it.trim() },
                rows = rows.drop(1),
            )
        }
        if (tables.isEmpty()) return SourceFileResult.Failed(fileName, "Таблицы в файле пустые")
        return SourceFileResult.Loaded(fileName, sha, tables)
    }

    private fun collectCsv(input: InputStream, out: MutableMap<String, ByteArray>, depth: Int, charset: Charset) {
        ZipInputStream(input, charset).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                when {
                    name.endsWith(".csv", ignoreCase = true) -> out[name] = zip.readBytes()
                    name.endsWith(".zip", ignoreCase = true) && depth < MAX_NESTING ->
                        collectCsv(ByteArrayInputStream(zip.readBytes()), out, depth + 1, charset)
                }
            }
        }
    }

    /** Если есть `X_all.csv`, то `X.csv` — урезанный вид той же базы, и он не нужен. */
    private fun pickPreferred(files: Map<String, ByteArray>): List<Pair<String, ByteArray>> {
        val withoutExt = files.keys.associateWith { it.removeSuffix(".csv").removeSuffix(".CSV") }
        val allBases = withoutExt.values.filter { ALL_SUFFIX.containsMatchIn(it) }
            .map { it.replace(ALL_SUFFIX, "") }
            .toSet()
        return files.entries
            .filter { (name, _) -> withoutExt.getValue(name) !in allBases }
            .map { it.key to it.value }
    }

    /** UTF-8 (Notion); если файл пересохранили в Excel — Windows-1251. */
    private fun decode(bytes: ByteArray): String {
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strict.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1251"))
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
