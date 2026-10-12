import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    // Графики — примитивы отрисовки на Canvas: Compose и типы денег/периодов из core:model.
    // Зависимости на core:analytics и core:data здесь намеренно нет (SPEC.md §4):
    // chart принимает готовые серии, а считает их аналитика.
    api(compose.desktop.currentOs)
    // Про deprecated-аксессор material3 — см. core/ui/build.gradle.kts.
    api(compose.material3)
    api(project(":core:model"))
    // Из core:ui берутся parseHexColor и форматтеры денег/долей: дублировать разбор HEX и
    // округление процентов в третьем месте хуже, чем зависимость внутри слоя core.
    api(project(":core:ui"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
