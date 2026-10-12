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
import app.finance.core.data.AppDataLocations
import app.finance.core.data.AppSettings
import app.finance.core.data.SettingsStore
import app.finance.core.model.CategorySection
import app.finance.core.model.LedgerSection
import app.finance.core.ui.AppSidebar
import app.finance.core.ui.FinanceTheme
import app.finance.core.ui.SectionNotReady
import app.finance.core.ui.SidebarItem
import app.finance.core.ui.SyncStatusFooter
import app.finance.feature.analytics.AnalyticsScreen
import app.finance.feature.analytics.AnalyticsSection
import app.finance.feature.assets.AssetsScreen
import app.finance.feature.assets.AssetsSection
import app.finance.feature.expenses.ExpensesScreen
import app.finance.feature.expenses.ExpensesSection
import app.finance.feature.income.IncomeScreen
import app.finance.feature.income.IncomeSection
import app.finance.feature.settings.SettingsController
import app.finance.feature.settings.SettingsScreen
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * Точка входа десктоп-приложения (SPEC.md §2).
 *
 * При запуске открывается раздел «Расходы» за текущий месяц (F1.1). База открывается
 * асинхронно: до готовности показывается индикатор, при ошибке — сообщение с повтором.
 *
 * Какую базу открывать, решает файл настроек `app.finance/settings.properties`
 * (`SettingsStore` в `core:data`): там лежит список баз и активная. Настройки живут вне
 * баз данных, поэтому переключение между «своими» данными и тестовой базой обратимо и не
 * зависит от содержимого ни одной из них.
 */
fun main() = application {
    val windowState = rememberWindowState(width = 1240.dp, height = 800.dp)
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    var graph by remember { mutableStateOf<AppGraph?>(null) }
    var store by remember { mutableStateOf<SettingsStore?>(null) }
    var settings by remember { mutableStateOf<AppSettings?>(null) }
    var startupError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        runCatching {
            val settingsStore = SettingsStore.default()
            val loaded = withContext(Dispatchers.IO) { settingsStore.load() }
            val opened = AppGraph.create(
                scope = scope,
                ioDispatcher = Dispatchers.IO,
                databasePath = Paths.get(loaded.active.path),
            )
            Startup(settingsStore, loaded, opened)
        }
            .onSuccess { started ->
                // След в логе нужен для диагностики запуска: окно может показать и ошибку,
                // но стектрейс в консоль иначе теряется (runCatching его проглатывает).
                println("Finance: база открыта — ${started.graph.databasePath}")
                store = started.store
                settings = started.settings
                graph = started.graph
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
            val currentStore = store
            val currentSettings = settings
            val errorMessage = startupError
            when {
                errorMessage != null -> StartupErrorView(
                    message = errorMessage,
                    databasePath = currentSettings?.active?.path ?: AppGraph.defaultDatabasePath().toString(),
                    onRetry = {
                        startupError = null
                        attempt++
                    },
                )

                currentGraph == null || currentStore == null || currentSettings == null -> StartupProgressView()

                else -> {
                    // Контроллер настроек создаётся один раз на хранилище: сам он базу не
                    // открывает (иначе feature:settings зависел бы от app:desktop), а просит
                    // это сделать хоста через switchDatabase.
                    val settingsController = remember(currentStore, currentSettings) {
                        SettingsController(
                            store = currentStore,
                            scope = scope,
                            ioDispatcher = Dispatchers.IO,
                            switchDatabase = { path ->
                                openDatabase(path = path, scope = scope) { opened ->
                                    // Прежний граф закрываем только после успешного открытия
                                    // нового: при ошибке приложение остаётся на текущей базе.
                                    val previous = graph
                                    graph = opened
                                    previous?.close()
                                }
                            },
                            initial = currentSettings,
                        )
                    }
                    AppShell(graph = currentGraph, settingsController = settingsController)
                }
            }
        }
    }
}

/** Оболочка: боковая навигация и содержимое выбранного раздела. */
@Composable
private fun AppShell(graph: AppGraph, settingsController: SettingsController) {
    var selectedId by remember { mutableStateOf(ExpensesSection.NAVIGATION_ID) }

    Row(modifier = Modifier.fillMaxSize()) {
        AppSidebar(
            title = "Finance",
            items = navigationItems(selectedId = selectedId),
            onSelect = { id -> selectedId = id },
            footer = {
                // Статус честный: бэкапа в v1 нет, данные живут только на этом компьютере.
                // С появлением копий на Яндекс.Диске (M6) тот же компонент покажет время
                // последней выгрузки и сменит тон на жёлтый или красный.
                SyncStatusFooter(text = "Данные хранятся локально на этом компьютере")
            },
        )

        when (selectedId) {
            ExpensesSection.NAVIGATION_ID -> ExpensesScreen(
                controller = graph.controller(LedgerSection.EXPENSE),
                categories = graph.categories(CategorySection.EXPENSE),
                modifier = Modifier.fillMaxSize(),
            )

            // F2.1: поведение идентично расходам при section = INCOME и своём наборе категорий.
            // F2.2: экран не копируется — та же LedgerScreen и тот же контроллер из AppGraph.
            IncomeSection.NAVIGATION_ID -> IncomeScreen(
                controller = graph.controller(LedgerSection.INCOME),
                categories = graph.categories(CategorySection.INCOME),
                modifier = Modifier.fillMaxSize(),
            )

            // Активы — отдельный экран и отдельный контроллер: единица учёта здесь не ячейка
            // «категория + месяц», а снапшот счёта (SPEC.md F4, DECISIONS D-12).
            AssetsSection.NAVIGATION_ID -> AssetsScreen(
                controller = graph.assets,
                categories = graph.categories(CategorySection.ASSET),
                modifier = Modifier.fillMaxSize(),
            )
            // Аналитика читает те же данные, что и разделы ввода, но ничего не мутирует:
            // расчёты — чистые функции core:analytics (SPEC.md F6.4).
            AnalyticsSection.NAVIGATION_ID -> AnalyticsScreen(
                controller = graph.analytics,
                modifier = Modifier.fillMaxSize(),
            )
            // Настройки: выбор активной базы данных (свои данные и тестовая база).
            SETTINGS_ID -> SettingsScreen(
                controller = settingsController,
                modifier = Modifier.fillMaxSize(),
            )
            else -> SectionNotReady(title = "Раздел", milestone = "—", modifier = Modifier.fillMaxSize())
        }
    }
}

private fun navigationItems(selectedId: String): List<SidebarItem> = listOf(
    ExpensesSection.NAVIGATION_ID to "Расходы",
    IncomeSection.NAVIGATION_ID to "Доходы",
    AssetsSection.NAVIGATION_ID to "Активы",
    AnalyticsSection.NAVIGATION_ID to "Аналитика",
    SETTINGS_ID to "Настройки",
).map { (id, label) ->
    SidebarItem(id = id, label = label, selected = id == selectedId)
}

/**
 * Открывает базу по пути и передаёт новый граф в [onOpened].
 *
 * @return true, если база открыта. При ошибке ничего не меняется: вызывающий продолжает
 *   работать с прежним подключением, поэтому данные текущей сессии не теряются.
 */
private suspend fun openDatabase(
    path: String,
    scope: CoroutineScope,
    onOpened: (AppGraph) -> Unit,
): Boolean {
    val opened = runCatching {
        AppGraph.create(
            scope = scope,
            ioDispatcher = Dispatchers.IO,
            databasePath = Paths.get(path),
        )
    }.getOrElse { error ->
        System.err.println("Finance: не удалось открыть базу $path — ${describe(error)}")
        error.printStackTrace()
        return false
    }
    onOpened(opened)
    println("Finance: база открыта — ${opened.databasePath}")
    return true
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
private fun StartupErrorView(message: String, databasePath: String, onRetry: () -> Unit) {
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
                text = "Путь к базе: $databasePath",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Список баз и активную можно поправить вручную: ${AppDataLocations.settingsFile()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) { Text("Повторить") }
        }
    }
}

private fun describe(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

/** Комплект успешного запуска: хранилище настроек, сами настройки и открытый граф. */
private data class Startup(
    val store: SettingsStore,
    val settings: AppSettings,
    val graph: AppGraph,
)

/**
 * Раздел настроек в боковой навигации: выбор активной базы данных.
 * Заглушка [app.finance.core.ui.SectionNotReady] осталась для ветки `else` — неизвестный id.
 */
private const val SETTINGS_ID = "settings"
