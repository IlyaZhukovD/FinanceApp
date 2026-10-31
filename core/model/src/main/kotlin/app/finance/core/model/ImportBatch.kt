package app.finance.core.model

/** Состояние пакета импорта. Отменённый пакет не удаляется — остаётся в истории. */
enum class ImportBatchStatus { APPLIED, UNDONE }

/** Файл, вошедший в пакет: по sha256 мастер узнаёт уже импортированный экспорт. */
data class ImportedFile(
    val name: String,
    val sha256: String,
    val section: LedgerSection,
)

/**
 * Пакет импорта — один запуск мастера (docs/IMPORT-NOTION.md §4, §5.1).
 *
 * Пакет нужен для двух вещей: отменить импорт целиком одной кнопкой и не импортировать
 * повторно уже перенесённые строки (ключи строк ссылаются на пакет).
 */
data class ImportBatch(
    val id: String,
    val source: String,
    val files: List<ImportedFile>,
    val rowsTotal: Int,
    val rowsImported: Int,
    val cellsTouched: Int,
    /** Категории, созданные этим пакетом: при отмене архивируются, если опустели. */
    val createdCategoryIds: List<String>,
    val status: ImportBatchStatus,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
    val rowVersion: Int = 1,
    val deletedAtUtc: Long? = null,
) {
    companion object {
        const val SOURCE_NOTION_CSV = "NOTION_CSV"
    }
}
