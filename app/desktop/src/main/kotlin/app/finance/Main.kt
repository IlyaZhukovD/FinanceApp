package app.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.finance.core.model.LedgerSection
import app.finance.core.ui.AppSidebar
import app.finance.core.ui.FinanceTheme
import app.finance.core.ui.SectionNotReady
import app.finance.core.ui.SidebarItem
import app.finance.feature.assets.AssetsScreen
import app.finance.feature.assets.AssetsSection
import app.finance.feature.expenses.ExpensesScreen
import app.finance.feature.expenses.ExpensesSection
import app.finance.feature.income.IncomeScreen
import app.finance.feature.income.IncomeSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Точка входа десктоп-приложения (SPEC.md §2).
 *
 * При запуске открывается раздел «Расходы» за текущий месяц (F1.1). База открывается
 * асинхронно: до готовности показывается индикатор, при ошибке — сообщение с повтором.
 */
fun main() = application {
    val windowState = rememberWindowState(width = 1240.dp, height = 800.dp)
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    var graph by remember { mutableStateOf<AppGraph?>(null) }
    var startupError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        runCatching { AppGraph.create(scope = scope, ioDispatcher = Dispatchers.IO) }
            .onSuccess { opened ->
                // След в логе нужен для диагностики запуска: окно может показать и ошибку,
                // но стектрейс в консоль иначе теряется (runCatching его проглатывает).
                println("Finance: база открыта — ${opened.databasePath}")
                graph = opened
            }
            .onFailure { error ->
                System.err.println("Finance: не удалось открыть базу — ${describe(error)}")
                error.printStackTrace()
                startupError = describe(error)
            }
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Finance",
    ) {
        FinanceTheme {
            // Освобождаем базу и отменяем фоновые задачи при закрытии окна.
            DisposableEffect(Unit) {
                onDispose {
                    graph?.close()
                    scope.cancel()
                }
            }

            val currentGraph = graph
            val errorMessage = startupError
            when {
                errorMessage != null -> StartupErrorView(
                    message = errorMessage,
                    onRetry = {
                        startupError = null
                        attempt++
                    },
                )

                currentGraph == null -> StartupProgressView()
                else -> AppShell(graph = currentGraph)
            }
        }
    }
}

/** Оболочка: боковая навигация и содержимое выбранного раздела. */
@Composable
private fun AppShell(graph: AppGraph) {
    var selectedId by remember { mutableStateOf(ExpensesSection.NAVIGATION_ID) }

    Row(modifier = Modifier.fillMaxSize()) {
        AppSidebar(
            title = "Finance",
            items = navigationItems(selectedId = selectedId),
            onSelect = { id -> selectedId = id },
            footer = {
                Text(
                    text = "Данные хранятся локально",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        when (selectedId) {
            ExpensesSection.NAVIGATION_ID -> ExpensesScreen(
                controller = graph.controller(LedgerSection.EXPENSE),
                modifier = Modifier.fillMaxSize(),
            )

            // F2.1: поведение идентично расходам при section = INCOME и своём наборе категорий.
            // F2.2: экран не копируется — та же LedgerScreen и тот же контроллер из AppGraph.
            IncomeSection.NAVIGATION_ID -> IncomeScreen(
                controller = graph.controller(LedgerSection.INCOME),
                modifier = Modifier.fillMaxSize(),
            )

            // Активы — отдельный экран и отдельный контроллер: единица учёта здесь не ячейка
            // «категория + месяц», а снапшот счёта (SPEC.md F4, DECISIONS D-12).
            AssetsSection.NAVIGATION_ID -> AssetsScreen(
                controller = graph.assets,
                modifier = Modifier.fillMaxSize(),
            )
            ANALYTICS_ID -> SectionNotReady(title = "Аналитика", milestone = "M6", modifier = Modifier.fillMaxSize())
            SETTINGS_ID -> SectionNotReady(title = "Настройки", milestone = "M7", modifier = Modifier.fillMaxSize())
            else -> SectionNotReady(title = "Раздел", milestone = "—", modifier = Modifier.fillMaxSize())
        }
    }
}

private fun navigationItems(selectedId: String): List<SidebarItem> = listOf(
    ExpensesSection.NAVIGATION_ID to "Расходы",
    IncomeSection.NAVIGATION_ID to "Доходы",
    AssetsSection.NAVIGATION_ID to "Активы",
    ANALYTICS_ID to "Аналитика",
    SETTINGS_ID to "Настройки",
).map { (id, label) ->
    SidebarItem(id = id, label = label, selected = id == selectedId)
}

@Composable
private fun StartupProgressView() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Text(
                text = "Открываем базу…",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StartupErrorView(message: String, onRetry: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "Не удалось открыть базу", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Путь к базе: ${AppGraph.defaultDatabasePath()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) { Text("Повторить") }
        }
    }
}

private fun describe(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

// Разделы, которые ещё не реализованы: показывают заглушку с вехой (SPEC.md F2.4).
private const val ANALYTICS_ID = "analytics"
private const val SETTINGS_ID = "settings"
