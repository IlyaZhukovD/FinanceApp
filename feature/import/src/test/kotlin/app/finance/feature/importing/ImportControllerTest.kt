package app.finance.feature.importing

import app.finance.core.domain.DomainFixture
import app.finance.core.domain.FakeImportBatchRepository
import app.finance.core.domain.FakeSnapshotter
import app.finance.core.domain.ImportLedgerBatch
import app.finance.core.domain.UndoImportBatch
import app.finance.core.importing.TableTarget
import app.finance.core.model.CategorySection
import app.finance.core.model.Money
import app.finance.core.model.Period
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Сквозной сценарий мастера на фейках домена (docs/IMPORT-NOTION.md §7). */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportControllerTest {

    private val zone = ZoneOffset.UTC
    private val clock = Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"), zone)
    private val fx = DomainFixture()
    private val batches = FakeImportBatchRepository()
    private val snapshotter = FakeSnapshotter()
    private var refreshed = 0

    private fun TestScope.controller() = ImportController(
        categories = fx.categories,
        batches = batches,
        importLedger = ImportLedgerBatch(fx.categories, fx.ledger, batches, snapshotter, fx.ids, fx.time, fx.transactions),
        undoImport = UndoImportBatch(fx.categories, fx.ledger, batches, fx.ids, fx.time, fx.transactions),
        clock = clock,
        zone = zone,
        scope = this,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        onImported = { refreshed++ },
    )

    private fun exportZip(name: String, csvName: String, csv: String): Path {
        val dir = Files.createTempDirectory("notion-export")
        val path = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(path)).use { z ->
            // Фиксированное время записи: одинаковое содержимое даёт одинаковый sha256 файла.
            z.putNextEntry(ZipEntry(csvName).apply { time = 1_700_000_000_000L })
            z.write(("﻿" + csv).toByteArray())
            z.closeEntry()
        }
        return path
    }

    private val expenses = """
        |Name,Date,Number,Год,Категория,Месяц
        |,"January 1, 2025 → January 31, 2025",17000,2025,на покушать,Январь
        |обед,"January 1, 2025 → January 31, 2025",500,2025,на покушать,Январь
        |,"January 1, 2025 → January 31, 2025",10000,2025,маме,Январь
        |,"February 1, 2025 → February 28, 2025",2000,2025,мама,Февраль
        |,"February 1, 2025",,2025,спорт,Февраль
    """.trimMargin()

    private val income = """
        |Name,Number,Date
        |,170000,"January 1, 2025"
    """.trimMargin()

    @Test
    fun `импорт двух файлов, повтор и отмена`() = runTest {
        fx.category("salary", "Зарплата", section = CategorySection.INCOME)
        val controller = controller()
        controller.open()
        controller.addFiles(
            listOf(
                exportZip("notion-траты-2025.zip", "Траты f0a30cd67f11833096ed8183a305b284_all.csv", expenses),
                exportZip("notion-доход.zip", "Доход c2b30cd67f1183328de6016445b3694a_all.csv", income),
            ),
        )
        advanceUntilIdle()

        val files = controller.state.value
        assertEquals(listOf(TableTarget.EXPENSE, TableTarget.INCOME), files.tables.map { it.target })
        assertEquals(2025, files.tables.first().defaultYear)
        assertTrue(files.canGoNext)

        controller.next() // → колонки
        controller.next() // → категории
        advanceUntilIdle()
        val categories = controller.state.value
        assertEquals(ImportStep.CATEGORIES, categories.step)
        assertEquals(setOf("на покушать", "маме", "мама"), categories.categoryChoices.map { it.sourceName }.toSet())
        assertEquals(listOf("мама"), categories.categoryChoices.first { it.sourceName == "маме" }.similarTo)
        assertEquals("salary", categories.incomeCategoryId)

        // «мама» направляем в новую категорию «маме».
        controller.setCategoryChoice("мама", "${NEW_PREFIX}маме")
        controller.next()
        advanceUntilIdle()
        val preview = assertNotNull(controller.state.value.preview)
        assertEquals(5, preview.rowsToImport)
        assertEquals(1, preview.errors.size)
        assertEquals(listOf("на покушать", "маме"), preview.categoriesToCreate)
        assertEquals(Money(1_750_000 + 1_000_000), preview.months.first { it.period == Period(2025, 1) }.expense)

        controller.next() // импорт
        advanceUntilIdle()
        val done = controller.state.value
        assertEquals(ImportStep.DONE, done.step)
        val report = assertNotNull(done.report)
        assertEquals(5, report.rowsImported)
        assertEquals(1, refreshed)
        val mom = fx.categories.bySection(CategorySection.EXPENSE).single { it.name == "маме" }
        assertEquals(Money(200_000), fx.ledger.findCell(mom.id, Period(2025, 2))?.amount)
        assertEquals(Money(17_000_000), fx.ledger.findCell("salary", Period(2025, 1))?.amount)

        // Повторный импорт: сопоставление запомнено, новых строк нет.
        controller.restart()
        controller.addFiles(listOf(exportZip("notion-траты-2025.zip", "Траты f0a30cd67f11833096ed8183a305b284_all.csv", expenses)))
        advanceUntilIdle()
        assertNotNull(controller.state.value.tables.single().importedBeforeAtUtc)
        controller.next()
        controller.next()
        advanceUntilIdle()
        assertEquals(mom.id, controller.state.value.categoryChoices.first { it.sourceName == "маме" }.choice)
        controller.next()
        advanceUntilIdle()
        assertEquals(0, controller.state.value.preview?.rowsToImport)
        assertEquals(4, controller.state.value.preview?.rowsAlreadyImported)

        // Отмена из истории.
        controller.undo(assertNotNull(report.batch).id)
        advanceUntilIdle()
        assertNull(fx.ledger.findCell(mom.id, Period(2025, 2)))
        assertNull(fx.ledger.findCell("salary", Period(2025, 1)))
        assertEquals(2, refreshed)
    }
}
