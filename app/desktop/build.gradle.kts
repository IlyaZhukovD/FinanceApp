import org.jetbrains.compose.desktop.application.dsl.TargetFormat
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
    implementation(compose.desktop.currentOs)
    // Material3 используется напрямую (Button, Text, Surface, индикатор загрузки).
    // Про deprecated-аксессор и причину его сохранения — см. core/ui/build.gradle.kts.
    implementation(compose.material3)

    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":feature:expenses"))
    implementation(project(":feature:income"))
    implementation(project(":feature:assets"))

    implementation(libs.coroutines.core)
    // Без него запуск окна падает: "Module with the Main dispatcher is missing".
    // Найдено дымовым прогоном :app:desktop:run — ни компиляция, ни unit-тесты
    // эту ошибку не ловят, потому что Dispatchers.Main требуется только в рантайме.
    implementation(libs.coroutines.swing)

    // Интеграционный тест композиционного корня (AppGraphTest): реальная файловая SQLite.
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "app.finance.MainKt"

        nativeDistributions {
            // Dmg собирается только на macOS, Msi — только на Windows (нужен WiX).
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Finance"
            packageVersion = "0.1.0"
            description = "Личный учёт финансов: расходы, доходы и активы по месяцам"
            vendor = "Finance"

            macOS {
                bundleID = "app.finance"
            }
        }
    }
}
