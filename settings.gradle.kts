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
include(
    ":core:model",
    ":core:domain",
    ":core:analytics",
    ":core:data",
    ":core:ui",
    ":feature:ledger",
    ":feature:expenses",
    ":feature:income",
    ":feature:assets",
    ":feature:analytics",
    ":app:desktop",
)
