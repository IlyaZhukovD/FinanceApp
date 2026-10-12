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
    // Аналитика читает данные через интерфейсы core:domain, а считает — чистыми функциями
    // core:analytics (SPEC.md F6.4). Компоненты экрана берутся из core:ui.
    api(project(":core:ui"))
    api(project(":core:domain"))
    api(project(":core:analytics"))
    // Графики: экран собирает ChartSeries/DonutSlice из готовых расчётов аналитики.
    api(project(":core:charts"))
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
