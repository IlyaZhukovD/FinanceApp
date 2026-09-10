import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.sqldelight)
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

sqldelight {
    databases {
        create("FinanceDatabase") {
            packageName.set("app.finance.core.data.db")
            // Источник схемы и запросов: src/main/sqldelight (каталог по умолчанию для JVM).
            //
            // verifyMigrations отключена намеренно. Проверка требует базовый файл схемы
            // `<версия>.db`, а задача его генерации в SQLDelight 2.3.2 для Kotlin/JVM-модуля
            // не регистрируется (в списке задач есть только generateMainFinanceDatabaseInterface
            // и verifyMainFinanceDatabaseMigration). Миграций в v1 нет — версия схемы 1,
            // сверять нечего, поэтому проверка только ломала бы `./gradlew build`.
            //
            // Включить вместе с первой миграцией `2.sqm`: сначала получить базовый `1.db`
            // (см. docs/DECISIONS.md, D-11), затем вернуть verifyMigrations.set(true).
            verifyMigrations.set(false)
        }
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:domain"))

    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
