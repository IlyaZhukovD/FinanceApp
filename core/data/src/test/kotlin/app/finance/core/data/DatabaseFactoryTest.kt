package app.finance.core.data

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

/**
 * Проверяет создание базы, PRAGMA-настройки и поведение файловой БД.
 * WAL проверяется только на файловой базе: in-memory SQLite всегда в режиме `memory`.
 */
class DatabaseFactoryTest {

    @Test
    fun `in-memory база создаёт всю схему v1`() {
        DatabaseFactory.openInMemory().use { session ->
            val counts = session.database.metaQueries.counts().executeAsOne()

            assertEquals(0L, counts.categories)
            assertEquals(0L, counts.cells)
            assertEquals(0L, counts.revisions)
            assertEquals(0L, counts.accounts)
            assertEquals(0L, counts.snapshots)
            assertEquals(0L, counts.pendingSync)
        }
    }

    @Test
    fun `внешние ключи включены - ячейка без категории отклоняется`() {
        DatabaseFactory.openInMemory().use { session ->
            val error = assertFailsWith<Exception> {
                session.database.categoryMonthQueries.insert(
                    id = "orphan-cell",
                    section = "EXPENSE",
                    category_id = "несуществующая-категория",
                    period = 202602,
                    amount_minor = 100,
                    currency = "RUB",
                    comment = null,
                    created_at_utc = 1,
                    updated_at_utc = 1,
                    row_version = 1,
                    deleted_at_utc = null,
                )
            }

            val message = error.message.orEmpty()
            assertTrue(
                message.contains("FOREIGN KEY", ignoreCase = true),
                "PRAGMA foreign_keys должна быть включена, получили: $message",
            )
        }
    }

    @Test
    fun `диагностические запросы целостности выполняются и пусты на чистой базе`() {
        DatabaseFactory.openInMemory().use { session ->
            val meta = session.database.metaQueries

            assertTrue(meta.orphanCells().executeAsList().isEmpty())
            assertTrue(meta.orphanSnapshots().executeAsList().isEmpty())
            assertTrue(meta.orphanRevisions().executeAsList().isEmpty())
            assertTrue(meta.cellsInArchivedCategories().executeAsList().isEmpty())
        }
    }

    @Test
    fun `файловая база создаётся в WAL и переживает переоткрытие`(@TempDir dir: Path) {
        val dbPath = dir.resolve("finance.db")

        DatabaseFactory.openFile(dbPath).use { session ->
            assertEquals("wal", pragmaString(session.driver, "PRAGMA journal_mode"))
            // JDBC-драйвер может вернуть Boolean вместо Long, поэтому принимаем оба представления.
            val foreignKeys = pragmaString(session.driver, "PRAGMA foreign_keys")
            assertTrue(
                foreignKeys == "1" || foreignKeys.equals("true", ignoreCase = true),
                "PRAGMA foreign_keys должна быть включена, получили: $foreignKeys",
            )

            session.database.categoryQueries.insert(
                id = "cat-1",
                section = "EXPENSE",
                name = "Продукты",
                parent_id = null,
                color = "#4E79A7",
                icon = null,
                sort_order = 0,
                is_archived = false,
                created_at_utc = 1,
                updated_at_utc = 1,
                row_version = 1,
                deleted_at_utc = null,
                balance_policy = "FLAT",
                allows_negative_balance = false,
            )

            assertTrue(Files.exists(dbPath), "Файл базы должен быть создан")
        }

        DatabaseFactory.openFile(dbPath).use { session ->
            assertEquals(1L, session.database.categoryQueries.countAll().executeAsOne())
            assertEquals("Продукты", session.database.categoryQueries.selectById("cat-1").executeAsOne().name)

            // Поведенческая проверка: контроль внешних ключей действует и на переоткрытой базе,
            // а не только на том соединении, где выполнялся PRAGMA.
            val error = assertFailsWith<Exception> {
                session.database.categoryMonthQueries.insert(
                    id = "orphan-cell",
                    section = "EXPENSE",
                    category_id = "несуществующая-категория",
                    period = 202602,
                    amount_minor = 100,
                    currency = "RUB",
                    comment = null,
                    created_at_utc = 1,
                    updated_at_utc = 1,
                    row_version = 1,
                    deleted_at_utc = null,
                )
            }
            assertTrue(
                error.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true),
                "Ожидали нарушение внешнего ключа, получили: ${error.message}",
            )
        }
    }

    @Test
    fun `повторное открытие существующей базы не пересоздаёт схему`(@TempDir dir: Path) = runTest {
        val dbPath = dir.resolve("finance.db")

        DatabaseFactory.openFile(dbPath).use { first ->
            val repository = SqlCategoryRepository(first.database)
            CategorySeeder(repository, SeqIds(), FixedTime()).seedIfEmpty()
        }

        DatabaseFactory.openFile(dbPath).use { second ->
            val repository = SqlCategoryRepository(second.database)
            // Если бы схема пересоздавалась при каждом открытии, данные пропали бы.
            assertEquals(CategorySeeder.EXPECTED_TOTAL.toLong(), repository.count())
        }
    }
}
