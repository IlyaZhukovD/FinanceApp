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
    // Настройки приложения (SPEC.md §4, feature:settings): выбор активной базы данных.
    // Хранилище настроек живёт в core:data рядом с DatabaseFactory, поэтому зависимость
    // на него, а не на app:desktop — иначе получился бы цикл (граф собирается в app).
    api(project(":core:ui"))
    api(project(":core:data"))
    implementation(project(":core:model"))
    implementation(libs.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
