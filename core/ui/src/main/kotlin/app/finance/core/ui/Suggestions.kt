package app.finance.core.ui

/**
 * Подбор подсказок для поля с автодополнением ([FinanceAutocomplete]).
 *
 * Сравнение без учёта регистра, «ё» приравнена к «е»: пользователь печатает быстро и не
 * должен думать, как именно записано имя категории. Порядок выдачи:
 * 1. имя начинается с запроса («прод» → «Продукты»);
 * 2. с запроса начинается одно из слов («рест» → «Кафе и рестораны», «жкх» → «Жильё и ЖКХ»);
 * 3. запрос встречается внутри имени.
 * Внутри одной группы сохраняется исходный порядок списка — он уже отражает порядок
 * категорий, заданный пользователем (F3.1), и выдача не «прыгает» при наборе.
 *
 * Пустой запрос возвращает весь список: поле только получило фокус, и пользователь
 * может выбрать категорию стрелками, ничего не печатая.
 */
fun <T> rankSuggestions(items: List<T>, query: String, label: (T) -> String): List<T> {
    val needle = normalizeForSearch(query.trim())
    if (needle.isEmpty()) return items
    return items
        .mapIndexedNotNull { index, item ->
            val name = normalizeForSearch(label(item))
            val rank = when {
                name.startsWith(needle) -> RANK_PREFIX
                name.split(WORD_SEPARATORS).any { it.startsWith(needle) } -> RANK_WORD
                name.contains(needle) -> RANK_SUBSTRING
                else -> return@mapIndexedNotNull null
            }
            Ranked(rank, index, item)
        }
        .sortedWith(compareBy<Ranked<T>>({ it.rank }, { it.index }))
        .map { it.item }
}

/**
 * Диапазон совпадения запроса в имени — для подсветки в выпадающем списке.
 * `null`, если запрос пуст или не найден. Нормализация не меняет длину строки
 * (нижний регистр кириллицы и латиницы, «ё» → «е»), поэтому индексы совпадают с исходным именем.
 */
fun matchRange(name: String, query: String): IntRange? {
    val needle = normalizeForSearch(query.trim())
    if (needle.isEmpty()) return null
    val start = normalizeForSearch(name).indexOf(needle)
    return if (start < 0) null else start until start + needle.length
}

internal fun normalizeForSearch(text: String): String = text.lowercase().replace('ё', 'е')

private data class Ranked<T>(val rank: Int, val index: Int, val item: T)

private const val RANK_PREFIX = 0
private const val RANK_WORD = 1
private const val RANK_SUBSTRING = 2

private val WORD_SEPARATORS = Regex("[\\s\\-–—,./()]+")
