rootProject.name = "finance"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// Модули добавляются по мере реализации (SPEC.md §4).
// Полный целевой набор: core:model, core:domain, core:analytics, core:data, core:charts,
// core:ui, core:network, core:backup, feature:ledger, feature:expenses, feature:income,
// feature:assets, feature:analytics, feature:ai, feature:settings, app:desktop.
// feature:ledger — общий экран расходов и доходов: F2.2 запрещает копипасту экранов,
// поэтому feature:expenses и feature:income остаются тонкими точками входа.
// feature:categories добавлен сверх списка SPEC.md §4: управление категориями (F3.1)
// нужно трём разделам сразу, а общего UI-модуля с доступом к домену в проекте нет
// (docs/DECISIONS.md, D-18).
include(
    ":core:model",
    ":core:domain",
    ":core:analytics",
    ":core:data",
    ":core:charts",
    ":core:ui",
    // Разбор экспорта Notion (docs/IMPORT-NOTION.md §5): чистые функции без БД и UI.
    ":core:import",
    // Синхронизация через Яндекс.Диск (docs/SYNC-YANDEX.md): движок, формат пакетов, слияние.
    ":core:sync",
    // Сеть: Яндекс OAuth и REST API Диска (SPEC §4 core:network).
    ":core:network",
    ":feature:ledger",
    ":feature:expenses",
    ":feature:income",
    ":feature:assets",
    ":feature:analytics",
    ":feature:categories",
    ":feature:settings",
    // Мастер импорта из Notion (F10.1, docs/IMPORT-NOTION.md §4).
    ":feature:import",
    // Интерфейс синхронизации: настройки, статус, автосинхронизация.
    ":feature:sync",
    ":app:desktop",
)
