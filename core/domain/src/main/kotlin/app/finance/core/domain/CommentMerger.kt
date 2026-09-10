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

    /** Число накопленных строк — для индикатора в списке и усечения в таблице. */
    fun lineCount(comment: String?): Int =
        comment?.split(LINE_SEPARATOR)?.count { it.isNotBlank() } ?: 0
}
