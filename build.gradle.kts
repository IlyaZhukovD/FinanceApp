plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.sqldelight) apply false
}

allprojects {
    // Координаты group:name обязаны быть уникальными. При одинаковых Gradle считает два
    // проекта одним артефактом и подменяет один другим при разрешении конфликтов: пара
    // core:analytics и feature:analytics (SPEC.md §4) давала в отчёте зависимостей
    // «project ':core:analytics' -> project ':feature:analytics'» и цикл задач
    // classes -> compileJava -> compileKotlin -> jar -> classes.
    // Group ничего не публикует и в упаковке не участвует (bundleID задан явно в
    // app/desktop), поэтому слой включён в координаты.
    val layer = path.split(":").filter { it.isNotEmpty() }.dropLast(1).joinToString(".")
    group = if (layer.isEmpty()) "app.finance" else "app.finance.$layer"
    version = "1.0.0"
}
