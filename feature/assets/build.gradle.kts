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
    // Активы — отдельный экран: единица учёта не ячейка «категория + месяц», а снапшот
    // счёта (SPEC.md F4, DECISIONS D-12). Компоненты core:ui при этом переиспользуются.
    api(project(":core:ui"))
    api(project(":core:domain"))
    // Категории активов (F3.1) управляются тем же диалогом, что и в расходах/доходах.
    api(project(":feature:categories"))
    implementation(project(":core:model"))
    implementation(libs.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    // Фейки репозиториев и DomainFixture живут в test fixtures домена.
    testImplementation(testFixtures(project(":core:domain")))
}

tasks.test {
    useJUnitPlatform()
}
