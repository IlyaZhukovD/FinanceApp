import org.jetbrains.compose.desktop.application.dsl.TargetFormat
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
    // Аналитика: core:analytics приходит транзитивно через feature:analytics (api).
    implementation(project(":feature:analytics"))
    // Управление категориями (F3.1): граф создаёт по контроллеру на каждую секцию.
    implementation(project(":feature:categories"))
    // Настройки: выбор активной базы данных.
    implementation(project(":feature:settings"))
    // Импорт из Notion (F10.1): мастер и use-case'ы импорта.
    implementation(project(":feature:import"))
    // Синхронизация через Яндекс.Диск (docs/SYNC-YANDEX.md): движок, транспорт, экран.
    implementation(project(":feature:sync"))

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
            // Версия приложения по SPEC.md §2. Установщики требуют MAJOR > 0, поэтому не 0.x.
            packageVersion = "1.0.0"
            // Только ASCII: WiX собирает .msi с культурой en-us (кодовая страница 1252),
            // кириллица здесь роняет light.exe с кодом 311 (LGHT0311).
            description = "Personal finance: expenses, income and assets by month"
            vendor = "Finance"

            // Модули урезанной Java внутри сборки (./gradlew :app:desktop:suggestRuntimeModules).
            // Без java.sql драйвер SQLite не загрузится и приложение упадёт при открытии базы.
            // java.net.http — запросы к Яндексу, jdk.httpserver — возврат из браузера после
            // входа на 127.0.0.1, jdk.crypto.ec — TLS-шифры с эллиптическими кривыми (D-29).
            modules("java.instrument", "java.sql", "jdk.unsupported", "java.net.http", "jdk.httpserver", "jdk.crypto.ec")

            macOS {
                bundleID = "app.finance"
            }

            windows {
                // Ярлыки на рабочем столе и в меню «Пуск» после установки .msi.
                shortcut = true
                menu = true
                menuGroup = "Finance"
                dirChooser = true
                // Постоянный id продукта: новая версия .msi обновляет установленную, а не ставится рядом.
                upgradeUuid = "8f0c2a4e-6b1d-4c39-9e57-3a1f0d7b2c64"
            }
        }
    }
}
