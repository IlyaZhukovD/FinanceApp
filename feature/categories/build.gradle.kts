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
    // Управление категориями общее для трёх разделов: расходы, доходы и активы (SPEC.md
    // F3.1, F2.2 — копипаста экранов недопустима), поэтому и контроллер, и диалог живут
    // здесь, а разделы подключают модуль целиком.
    api(project(":core:ui"))
    api(project(":core:domain"))
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
