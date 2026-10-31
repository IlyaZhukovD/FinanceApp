import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    // Сеть (SPEC.md §4): Яндекс OAuth и REST API Диска для синхронизации (D-29).
    // HTTP — java.net.http из JDK, а не Ktor: два десятка запросов не стоят новой
    // библиотеки, а модуль java.net.http всё равно входит в рантайм.
    api(project(":core:sync"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
