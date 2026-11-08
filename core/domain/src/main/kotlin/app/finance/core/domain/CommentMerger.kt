package app.finance.core.domain

/**
 * Правило слияния комментария ячейки при аккумулировании суммы (SPEC.md F1.5, D-03).
 *
 * Комментарий у ячейки один, а вводов за месяц может быть несколько, поэтому при
 * повторном вводе текст не затирается, а дописывается. Исключение — явное
 * редактирование в UI: это перезапись, а не слияние.
 */
object CommentMerger {

    /** Разделитель строк при дописывании комментария. */
    const val LINE_SEPARATOR = "\n"

    /**
     * @param existing текущий комментарий ячейки (может отсутствовать)
     * @param incoming комментарий нового ввода (может отсутствовать)
     * @return итоговый комментарий или null, если текста не осталось
     */
    fun merge(existing: String?, incoming: String?): String? {
        val current = existing?.trim().orEmpty()
        val added = incoming?.trim().orEmpty()

        // Новый ввод без комментария — существующий текст не трогаем.
        if (added.isEmpty()) return current.ifEmpty { null }
        // У ячейки ещё не было комментария — записываем новый.
        if (current.isEmpty()) return added
        // Тот же текст повторно не дублируем.
        if (current == added) return current
        // Оба непустые и разные — дописываем новой строкой.
        return current + LINE_SEPARATOR + added
    }

    /** Явная перезапись комментария пользователем: слияние не применяется. */
    fun replace(comment: String?): String? = comment?.trim()?.ifEmpty { null }

    /**
     * Текст, который внесла одна операция: обратная к [merge] сторона.
     *
     * Ячейка хранит накопленный комментарий, а в истории изменений нужно видеть, что
     * пришло именно с этим вводом. Если [after] — это [before] с дописанной строкой,
     * возвращается только дописанный хвост; если текст не изменился — null; при
     * любой другой перемене (перезапись, первый комментарий) — [after] целиком.
     */
    fun addedPart(before: String?, after: String?): String? {
        val previous = before?.trim().orEmpty()
        val current = after?.trim().orEmpty()
        if (current.isEmpty() || current == previous) return null
        if (previous.isNotEmpty() && current.startsWith(previous + LINE_SEPARATOR)) {
            return current.substring(previous.length + LINE_SEPARATOR.length).trim().ifEmpty { null }
        }
        return current
    }

    /** Число накопленных строк — для индикатора в списке и усечения в таблице. */
    fun lineCount(comment: String?): Int =
        comment?.split(LINE_SEPARATOR)?.count { it.isNotBlank() } ?: 0
}
