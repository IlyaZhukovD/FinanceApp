package app.finance.feature.importing

import app.finance.core.domain.CategoryRepository
import app.finance.core.domain.CategoryTarget
import app.finance.core.domain.ImportBatchRepository
import app.finance.core.domain.ImportLedgerBatch
import app.finance.core.domain.ImportRequest
import app.finance.core.domain.ImportRowInput
import app.finance.core.domain.UndoImportBatch
import app.finance.core.importing.CategorySimilarity
import app.finance.core.importing.ImportField
import app.finance.core.importing.NotionExportReader
import app.finance.core.importing.NotionRowParser
import app.finance.core.importing.NotionTableDetector
import app.finance.core.importing.RowStatus
import app.finance.core.importing.SourceFileResult
import app.finance.core.importing.TableTarget
import app.finance.core.model.CategorySection
import app.finance.core.model.ImportBatchStatus
import app.finance.core.model.ImportedFile
import app.finance.core.model.LedgerSection
import app.finance.core.model.Money
import app.finance.core.model.Period
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Мастер импорта из Notion (docs/IMPORT-NOTION.md §4).
 *
 * До шага «Импортировать» ничего не пишется в базу: разбор файлов, сопоставление и
 * предпросмотр живут только в состоянии мастера. Запись и отмена — use-case'ы домена.
 *
 * @param onImported хост перечитывает разделы после импорта или отмены: суммы месяцев
 *   изменились у всех открытых экранов.
 */
class ImportController(
    private val categories: CategoryRepository,
    private val batches: ImportBatchRepository,
    private val importLedger: ImportLedgerBatch,
    private val undoImport: UndoImportBatch,
    private val clock: Clock,
    private val zone: ZoneId,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val onImported: () -> Unit = {},
) {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** Разобранные строки по таблицам; пересчитываются при смене настроек. */
    private var parsed: List<ParsedTable> = emptyList()

    fun open() {
        _state.value = ImportUiState(isOpen = true)
        parsed = emptyList()
        scope.launch { reloadHistory() }
    }

    fun close() {
        if (_state.value.isBusy) return
        _state.update { it.copy(isOpen = false) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // ── Шаг 1. Файлы ──────────────────────────────────────────────────────────────

    fun addFiles(paths: List<Path>) {
        if (paths.isEmpty() || _state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            val results = io { paths.map(NotionExportReader::read) }
            val history = _state.value.history
            val currentYear = LocalDate.now(clock.withZone(zone)).year
            _state.update { state ->
                val known = state.tables.map { it.id }.toMutableSet()
                val added = mutableListOf<ImportTableState>()
                val errors = mutableListOf<String>()
                for (result in results) {
                    when (result) {
                        is SourceFileResult.Failed -> errors += "${result.fileName}: ${result.message}"
                        is SourceFileResult.Loaded -> for (table in result.tables) {
                            val id = "${result.sha256}:${table.tableId}"
                            if (!known.add(id)) continue
                            val mapping = NotionTableDetector.detectMapping(table.header)
                            val importedBefore = history
                                .filter { it.status == ImportBatchStatus.APPLIED && it.files.any { f -> f.sha256 == result.sha256 } }
                                .maxOfOrNull { it.createdAtUtc }
                            added += ImportTableState(
                                id = id,
                                table = table,
                                target = NotionTableDetector.detectTarget(table.header, mapping),
                                mapping = mapping,
                                defaultYear = NotionTableDetector.defaultYear(table, mapping, currentYear),
                                importedBeforeAtUtc = importedBefore,
                            )
                        }
                    }
                }
                state.copy(isBusy = false, tables = state.tables + added, fileErrors = state.fileErrors + errors)
            }
        }
    }

    fun removeTable(id: String) = updateTable(id) { null }

    fun setTarget(id: String, target: TableTarget) = updateTable(id) { it.copy(target = target) }

    fun setDefaultYear(id: String, text: String) {
        val year = text.trim().toIntOrNull()?.takeIf { it in Period.MIN_YEAR..Period.MAX_YEAR } ?: return
        updateTable(id) { it.copy(defaultYear = year) }
    }

    // ── Шаг 2. Колонки ────────────────────────────────────────────────────────────

    fun setColumn(id: String, field: ImportField, column: Int?) =
        updateTable(id) { it.copy(mapping = it.mapping.with(field, column)) }

    // ── Шаг 3. Категории ──────────────────────────────────────────────────────────

    fun setCategoryChoice(sourceName: String, choice: String) = _state.update { state ->
        state.copy(categoryChoices = state.categoryChoices.map { if (it.sourceName == sourceName) it.copy(choice = choice) else it })
    }

    fun setIncomeCategory(categoryId: String?) = _state.update { it.copy(incomeCategoryId = categoryId) }

    // ── Навигация ─────────────────────────────────────────────────────────────────

    fun back() = _state.update { state ->
        val previous = when (state.step) {
            ImportStep.FILES, ImportStep.DONE -> state.step
            ImportStep.COLUMNS -> ImportStep.FILES
            ImportStep.CATEGORIES -> ImportStep.COLUMNS
            ImportStep.PREVIEW -> ImportStep.CATEGORIES
        }
        state.copy(step = previous, error = null)
    }

    fun next() {
        val state = _state.value
        if (!state.canGoNext) return
        when (state.step) {
            ImportStep.FILES -> _state.update { it.copy(step = ImportStep.COLUMNS) }
            ImportStep.COLUMNS -> scope.launch { prepareCategories() }
            ImportStep.CATEGORIES -> scope.launch { preparePreview() }
            ImportStep.PREVIEW -> runImport()
            ImportStep.DONE -> Unit
        }
    }

    private suspend fun prepareCategories() {
        _state.update { it.copy(isBusy = true, error = null) }
        runCatching {
            val tables = _state.value.activeTables
            parsed = tables.map { table ->
                ParsedTable(table, NotionRowParser.parse(table.table, table.mapping, table.target, table.defaultYear))
            }
            val expense = io { categories.bySection(CategorySection.EXPENSE) }
            val income = io { categories.bySection(CategorySection.INCOME) }
            val saved = io { batches.savedCategoryMap() }

            val previous = _state.value.categoryChoices.associateBy { it.sourceName }
            val expenseRows = parsed.filter { it.state.target == TableTarget.EXPENSE }
                .flatMap { it.rows }
                .filter { it.isImportable && it.category != null }
            val grouped = expenseRows.groupBy { it.category!! }
            val names = grouped.keys.toList()
            val choices = grouped.map { (name, rows) ->
                val total = rows.fold(Money.ZERO) { acc, row -> acc + (row.amount ?: Money.ZERO) }
                val choice = previous[name]?.choice
                    ?: saved[mapKey(LedgerSection.EXPENSE, name)]?.takeIf { id -> expense.any { it.id == id } }
                    ?: expense.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
                    ?: CategoryChoice.CHOICE_NEW
                CategoryChoice(
                    sourceName = name,
                    rows = rows.size,
                    total = total,
                    choice = choice,
                    similarTo = names.filter { it != name && CategorySimilarity.similar(it, name) },
                )
            }.sortedByDescending { it.total.minor }

            val incomeId = _state.value.incomeCategoryId?.takeIf { id -> income.any { it.id == id } }
                ?: saved[mapKey(LedgerSection.INCOME, INCOME_KEY)]?.takeIf { id -> income.any { it.id == id } }
                ?: income.firstOrNull { it.name.equals(DEFAULT_INCOME_CATEGORY, ignoreCase = true) }?.id
                ?: income.firstOrNull()?.id

            _state.update {
                it.copy(
                    isBusy = false,
                    step = ImportStep.CATEGORIES,
                    categoryChoices = choices,
                    expenseCategories = expense,
                    incomeCategories = income,
                    incomeCategoryId = incomeId,
                )
            }
        }.onFailure { error -> fail(error) }
    }

    private suspend fun preparePreview() {
        _state.update { it.copy(isBusy = true, error = null) }
        runCatching {
            val state = _state.value
            val choices = state.categoryChoices.associateBy { it.sourceName }
            val allRows = parsed.flatMap { table -> table.rows.map { table to it } }
            val importable = allRows.filter { (_, row) -> row.isImportable }
            val alreadyImported = io { batches.importedRowKeys(importable.map { it.second.key }) }

            var skippedCategory = 0
            val toImport = importable.filter { (table, row) ->
                when {
                    row.key in alreadyImported -> false
                    table.state.target == TableTarget.EXPENSE && choices[row.category]?.choice == CategoryChoice.CHOICE_SKIP -> {
                        skippedCategory++
                        false
                    }
                    else -> true
                }
            }

            val months = toImport.groupBy { it.second.period!! }.toSortedMap().map { (period, rows) ->
                PreviewMonth(
                    period = period,
                    expense = rows.filter { it.first.state.target == TableTarget.EXPENSE }.sumMoney(),
                    income = rows.filter { it.first.state.target == TableTarget.INCOME }.sumMoney(),
                    rows = rows.size,
                )
            }
            fun issues(status: RowStatus) = allRows.filter { it.second.status == status }.map { (table, row) ->
                PreviewIssue(table.state.table.tableName, row.rowNumber, row.messages.joinToString("; "), row.preview)
            }
            val preview = ImportPreview(
                months = months,
                warnings = issues(RowStatus.WARNING),
                errors = issues(RowStatus.ERROR),
                rowsTotal = allRows.size,
                rowsToImport = toImport.size,
                rowsEmpty = allRows.count { it.second.status == RowStatus.EMPTY },
                rowsZero = allRows.count { it.second.status == RowStatus.ZERO },
                rowsAlreadyImported = importable.count { it.second.key in alreadyImported },
                rowsSkippedCategory = skippedCategory,
                expenseTotal = toImport.filter { it.first.state.target == TableTarget.EXPENSE }.sumMoney(),
                incomeTotal = toImport.filter { it.first.state.target == TableTarget.INCOME }.sumMoney(),
                categoriesToCreate = buildList {
                    // Новые категории — те, в которые реально попадут строки (с учётом «мама» → «маме»).
                    toImport.filter { it.first.state.target == TableTarget.EXPENSE }
                        .mapNotNull { (_, row) ->
                            when (val choice = choices[row.category]?.choice ?: CategoryChoice.CHOICE_NEW) {
                                CategoryChoice.CHOICE_NEW -> row.category
                                else -> choice.takeIf { it.startsWith(NEW_PREFIX) }?.removePrefix(NEW_PREFIX)
                            }
                        }
                        .distinct()
                        .filter { name -> state.expenseCategories.none { it.name.equals(name, ignoreCase = true) } }
                        .forEach { add(it) }
                    if (state.hasIncomeTables && state.incomeCategoryId == null) add(DEFAULT_INCOME_CATEGORY)
                },
            )
            _state.update { it.copy(isBusy = false, step = ImportStep.PREVIEW, preview = preview) }
        }.onFailure { error -> fail(error) }
    }

    private fun runImport() {
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching {
                val state = _state.value
                val request = buildRequest(state)
                val report = io { importLedger(request) }
                io { batches.saveCategoryMap(mergedCategoryMap(state, report)) }
                _state.update { it.copy(isBusy = false, step = ImportStep.DONE, report = report, undoReport = null) }
                reloadHistory()
                onImported()
            }.onFailure { error -> fail(error) }
        }
    }

    /** Отмена пакета из отчёта или из истории. */
    fun undo(batchId: String) {
        if (_state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            runCatching {
                val result = io { undoImport(batchId) }
                _state.update { it.copy(isBusy = false, undoReport = result) }
                reloadHistory()
                onImported()
            }.onFailure { error -> fail(error) }
        }
    }

    /** После импорта — начать заново (например, добавить ещё файл). */
    fun restart() {
        val history = _state.value.history
        parsed = emptyList()
        _state.value = ImportUiState(isOpen = true, history = history)
    }

    private fun buildRequest(state: ImportUiState): ImportRequest {
        val choices = state.categoryChoices.associateBy { it.sourceName }
        val rows = mutableListOf<ImportRowInput>()
        for (table in parsed) {
            val section = table.state.target.toLedgerSection() ?: continue
            for (row in table.rows) {
                if (!row.isImportable) continue
                val target = when (section) {
                    LedgerSection.INCOME -> state.incomeCategoryId?.let { CategoryTarget.Existing(it) }
                        ?: CategoryTarget.New(DEFAULT_INCOME_CATEGORY)
                    LedgerSection.EXPENSE -> {
                        val name = row.category ?: continue
                        when (val choice = choices[name]?.choice ?: CategoryChoice.CHOICE_NEW) {
                            CategoryChoice.CHOICE_SKIP -> continue
                            CategoryChoice.CHOICE_NEW -> CategoryTarget.New(name)
                            else -> if (choice.startsWith(NEW_PREFIX)) {
                                CategoryTarget.New(choice.removePrefix(NEW_PREFIX))
                            } else {
                                CategoryTarget.Existing(choice)
                            }
                        }
                    }
                }
                rows += ImportRowInput(
                    key = row.key,
                    section = section,
                    target = target,
                    period = row.period!!,
                    amount = row.amount!!,
                    comment = row.comment,
                )
            }
        }
        val files = parsed.mapNotNull { table ->
            val section = table.state.target.toLedgerSection() ?: return@mapNotNull null
            ImportedFile(table.state.table.fileName, table.state.table.fileSha256, section)
        }.distinct()
        return ImportRequest(files = files, rows = rows, rowsTotal = parsed.sumOf { it.rows.size })
    }

    /** Запоминаем выбор: в следующий раз категории сопоставятся сами (§4, шаг 3). */
    private fun mergedCategoryMap(
        state: ImportUiState,
        report: app.finance.core.domain.ImportReport,
    ): Map<String, String> {
        val created = report.categoriesCreated.associateBy { it.name.lowercase() }
        val map = mutableMapOf<String, String>()
        for (choice in state.categoryChoices) {
            val id = when (choice.choice) {
                CategoryChoice.CHOICE_SKIP -> null
                CategoryChoice.CHOICE_NEW -> created[choice.sourceName.lowercase()]?.id
                    ?: state.expenseCategories.firstOrNull { it.name.equals(choice.sourceName, ignoreCase = true) }?.id
                else -> if (choice.choice.startsWith(NEW_PREFIX)) {
                    created[choice.choice.removePrefix(NEW_PREFIX).lowercase()]?.id
                } else {
                    choice.choice
                }
            } ?: continue
            map[mapKey(LedgerSection.EXPENSE, choice.sourceName)] = id
        }
        val incomeId = state.incomeCategoryId ?: created[DEFAULT_INCOME_CATEGORY.lowercase()]?.id
        if (incomeId != null) map[mapKey(LedgerSection.INCOME, INCOME_KEY)] = incomeId
        return map
    }

    private suspend fun reloadHistory() {
        val history = runCatching { io { batches.batches() } }.getOrDefault(emptyList())
        _state.update { it.copy(history = history) }
    }

    private fun updateTable(id: String, change: (ImportTableState) -> ImportTableState?) = _state.update { state ->
        state.copy(tables = state.tables.mapNotNull { if (it.id == id) change(it) else it })
    }

    private fun fail(error: Throwable) {
        _state.update { it.copy(isBusy = false, error = error.message?.takeIf { m -> m.isNotBlank() } ?: "Не удалось выполнить импорт") }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    private fun List<Pair<ParsedTable, app.finance.core.importing.ParsedRow>>.sumMoney(): Money =
        fold(Money.ZERO) { acc, (_, row) -> acc + (row.amount ?: Money.ZERO) }

    private fun mapKey(section: LedgerSection, name: String) = "${section.name}:${name.trim().lowercase()}"

    companion object {
        const val DEFAULT_INCOME_CATEGORY = "Зарплата"
        private const val INCOME_KEY = "*"
    }
}
