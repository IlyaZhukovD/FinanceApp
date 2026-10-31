import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_19)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_19
    targetCompatibility = JavaVersion.VERSION_19
}

dependencies {
    // Раздел «Расходы» — точка входа; сам экран общий с «Доходами» (SPEC.md F2.2).
    api(project(":feature:ledger"))
    implementation(project(":core:ui"))
    implementation(project(":core:model"))
}
