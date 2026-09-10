package app.finance.core.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.finance.core.data.db.FinanceDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Открытая база и её драйвер. Закрывать обязательно: SQLite держит файловые блокировки. */
class DatabaseSession(
    val database: FinanceDatabase,
    val driver: SqlDriver,
) : AutoCloseable {
    override fun close() {
        driver.close()
    }
}

/**
 * Создание и открытие базы.
 *
 * Схема применяется автоматически: для файловой базы драйверу передаётся
 * `FinanceDatabase.Schema`, поэтому новый файл создаётся, а существующий
 * мигрируется (SPEC.md §6: миграции только `.sqm`, версии не правятся задним числом).
 */
object DatabaseFactory {

    /** In-memory база для тестов: живёт ровно столько, сколько соединение. */
    fun openInMemory(): DatabaseSession = open(JdbcSqliteDriver.IN_MEMORY)

    /**
     * Файловая база пользователя: `~/Library/Application Support/app.finance/finance.db`
     * или `%APPDATA%\app.finance\finance.db` (SPEC.md §2).
     */
    fun openFile(path: Path): DatabaseSession {
        path.parent?.let { Files.createDirectories(it) }
        return open("jdbc:sqlite:${path.toAbsolutePath()}")
    }

    private fun open(url: String): DatabaseSession {
        val driver = JdbcSqliteDriver(
            url = url,
            properties = connectionPragmas(),
            schema = FinanceDatabase.Schema,
        )
        return DatabaseSession(FinanceDatabase(driver), driver)
    }

    /**
     * PRAGMA передаются как свойства соединения, а не отдельными запросами.
     *
     * Проверено диагностикой на sqlite-jdbc 3.51.3.0: `journal_mode` сохраняется в файле базы,
     * а `foreign_keys`, `synchronous` и `busy_timeout` — свойства каждого соединения.
     * Разовый `PRAGMA foreign_keys=ON` через драйвер действовал только на одно соединение,
     * из-за чего на файловой базе контроль ключей фактически оставался выключенным.
     * Свойства соединения драйвер применяет при каждом открытии.
     *
     * WAL: чтение не блокирует запись — важно для бэкапа и фоновых выборок (SPEC.md §7).
     * synchronous=NORMAL: баланс durability и скорости при WAL.
     */
    private fun connectionPragmas(): Properties = Properties().apply {
        setProperty("foreign_keys", "true")
        setProperty("journal_mode", "WAL")
        setProperty("synchronous", "NORMAL")
        setProperty("busy_timeout", "5000")
        setProperty("temp_store", "MEMORY")
    }
}

/** Служебный запрос PRAGMA в обход сгенерированного кода: SQLDelight не знает системных таблиц. */
internal fun pragmaString(driver: SqlDriver, sql: String): String? =
    driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor -> app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0)) },
        parameters = 0,
    ).value
