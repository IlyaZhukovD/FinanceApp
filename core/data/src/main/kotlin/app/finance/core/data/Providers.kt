package app.finance.core.data

import app.finance.core.data.db.FinanceDatabase
import app.finance.core.domain.IdGenerator
import app.finance.core.domain.TimeProvider
import app.finance.core.domain.TransactionRunner
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Системные часы. В тестах заменяется фиксированным временем. */
class SystemTimeProvider : TimeProvider {
    override fun nowUtcMillis(): Long = System.currentTimeMillis()
}

/** UUIDv4 на клиенте — обязательное требование готовности к синхронизации (SPEC.md §7). */
class RandomUuidGenerator : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

/**
 * Транзакция SQLite поверх suspend-блока.
 *
 * SQLDelight даёт только синхронную границу транзакции, а доменные use-case'ы
 * suspend-ные (это нужно для будущего удалённого репозитория, где вызовы сетевые).
 * Мост — [runBlocking] внутри `transactionWithResult`.
 *
 * ВАЖНО, и это единственное разрешённое место для runBlocking в проекте:
 * репозитории `core:data` обязаны выполнять работу синхронно, без `withContext`,
 * иначе вложенная смена диспетчера уведёт обращение к SQLite в другой поток
 * внутри открытой транзакции и приведёт к блокировке или падению. Вызывающий
 * слой (ViewModel) сам переводит весь use-case на фоновый диспетчер.
 */
class SqlDelightTransactionRunner(
    private val database: FinanceDatabase,
) : TransactionRunner {

    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.transactionWithResult { runBlocking { block() } }
}
