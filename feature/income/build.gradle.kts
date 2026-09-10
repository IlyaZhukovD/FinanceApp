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
    // Раздел «Доходы» — точка входа; сам экран общий с «Расходами» (SPEC.md F2.2).
    api(project(":feature:ledger"))
    implementation(project(":core:ui"))
    implementation(project(":core:model"))
}
