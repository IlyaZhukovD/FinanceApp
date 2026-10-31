import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.sqldelight)
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

sqldelight {
    databases {
        create("FinanceDatabase") {
            packageName.set("app.finance.core.data.db")
            // Диалект SQLite 3.35 вместо стандартного 3.18: парсер 3.18 не знает
            // `ALTER TABLE ... DROP COLUMN`, которым миграция `3.sqm` удаляет колонку
            // `kind` (D-22). Рантайм это умеет — драйвер тянет sqlite-jdbc 3.51.3.0.
            // Пересбор таблицы через CREATE/DROP TABLE не годится: внешние ключи включены
            // свойством соединения на том же драйвере, а `PRAGMA foreign_keys=OFF` внутри
            // транзакции игнорируется SQLite.
            // Версия берётся из каталога; типобезопасный аксессор не используется, потому
            // что Gradle не строит вложенный доступ для числового сегмента алиаса.
            dialect("app.cash.sqldelight:sqlite-3-35-dialect:${libs.versions.sqldelight.get()}")
            // Источник схемы и запросов: src/main/sqldelight (каталог по умолчанию для JVM).
            //
            // verifyMigrations включена вместе с первой миграцией (условие из D-11).
            // Базовый файл схемы `1.db` получен из настоящей базы v1: свежий файл,
            // созданный текущей схемой, с очищенными таблицами (PRAGMA user_version = 1).
            // Задача генерации базовой схемы в SQLDelight 2.3.2 для Kotlin/JVM-модуля
            // по-прежнему не регистрируется, поэтому `1.db` создан вручную и лежит рядом
            // с `.sq`-файлами.
            //
            // ВАЖНО: файл миграции называется по версии ДО применения — `1.sqm` переводит
            // схему из 1 в 2 (колонки категории, D-20), `2.sqm` — из 2 в 3 (`term_months`
            // счёта, D-21), `3.sqm` — из 3 в 4 (удаление колонки `kind`, D-22).
            // В комментарии D-11 первая миграция ошибочно названа `2.sqm`.
            // Новые колонки в `.sq` объявляются В КОНЦЕ таблицы: запросы используют
            // `SELECT *`, а `ALTER TABLE ADD COLUMN` дописывает колонки в конец, поэтому
            // порядок в свежей и мигрированной базе обязан совпадать (D-20). При удалении
            // колонки то же правило: `DROP COLUMN` сохраняет порядок остальных, и он
            // обязан совпадать с `CREATE TABLE` в `.sq` (D-22).
            verifyMigrations.set(true)
        }
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:domain"))
    // SqlSyncStore реализует SyncStore движка синхронизации (D-29).
    implementation(project(":core:sync"))

    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.coroutines.core)
    // Манифест и журнал локальных бэкапов (D-30).
    implementation(libs.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
