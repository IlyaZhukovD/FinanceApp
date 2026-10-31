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
    // Компоненты и тема переиспользуются всеми feature-модулями, поэтому api.
    api(compose.desktop.currentOs)
    // В CMP 1.12.0 аксессор помечен как deprecated («Specify dependency directly»), но
    // напрямую это org.jetbrains.compose.material3:material3:1.9.0 — версию выбирает плагин
    // compose, и она не совпадает с версией CMP. Фиксация 1.9.0 в каталоге версий
    // рассинхронизировалась бы при обновлении CMP, поэтому оставлен аксессор.
    // Проверено: ./gradlew :core:ui:dependencies --configuration compileClasspath
    api(compose.material3)
    api(project(":core:model"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
