package app.finance

import app.finance.core.model.LedgerSection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

/**
 * Интеграционный тест композиционного корня.
 *
 * Проверяет связку, которую не покрывает ни один другой тест: открытие реальной файловой
 * SQLite через [AppGraph], посев категорий и создание контроллеров для обеих секций потока
 * (расходы и доходы используют один экран — SPEC.md F2.2, M3).
 *
 * До M3 сборка зависимостей не была покрыта тестами вообще: ошибка в графе проявилась бы
 * только при запуске окна.
 */
class AppGraphTest {

    @Test
    fun `граф открывает файловую базу, сеет категории и создаёт контроллеры секций`(
        @TempDir dir: Path,
    ) = runTest {
        val databasePath = dir.resolve("finance.db")

        val graph = AppGraph.create(
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            databasePath = databasePath,
        )

        graph.use { opened ->
            assertEquals(databasePath, opened.databasePath)
            assertTrue(Files.exists(databasePath), "Файл базы создан по переданному пути")

            // Контроллер создаётся для каждой секции потока; для неизвестной — исключение.
            LedgerSection.entries.forEach { section ->
                assertNotNull(opened.controller(section), "Нет контроллера для $section")
            }

            val expenses = opened.controller(LedgerSection.EXPENSE)
            val incomes = opened.controller(LedgerSection.INCOME)
            val assets = opened.assets
            expenses.start()
            incomes.start()
            assets.start()
            advanceUntilIdle()

            assertEquals(LedgerSection.EXPENSE, expenses.state.value.section)
            assertEquals(LedgerSection.INCOME, incomes.state.value.section)

            // Стартовые категории CategorySeeder на реальной базе: расходы 11, доходы 5.
            assertEquals(11, expenses.state.value.categories.size, "Категории расходов")
            assertEquals(5, incomes.state.value.categories.size, "Категории доходов")

            // Секции изолированы: зарплата не должна попасть в список расходов (F2.1).
            assertTrue(
                incomes.state.value.categories.any { it.name == "Зарплата" },
                "В доходах есть «Зарплата»",
            )
            assertTrue(
                expenses.state.value.categories.none { it.name == "Зарплата" },
                "В расходах «Зарплаты» быть не должно",
            )

            // Месяц пуст: ячеек ещё не вводили, поэтому дельта к прошлому месяцу отсутствует.
            assertTrue(expenses.state.value.rows.isEmpty())
            assertTrue(incomes.state.value.rows.isEmpty())

            // Активы (M4): свои стартовые категории и ни одного счёта до ввода пользователя.
            assertEquals(6, assets.state.value.assetCategories.size, "Категории активов")
            assertFalse(assets.state.value.hasAccounts, "Счетов ещё не создавали")
            assertTrue(assets.state.value.groups.isEmpty())
            assertEquals(0, assets.state.value.header.snapshotCount)
        }
    }
}
