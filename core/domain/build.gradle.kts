import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    // Фейки репозиториев вынесены в test fixtures: их используют тесты домена
    // и тесты feature-модулей (SPEC.md F2.2 — экраны переиспользуются, а не копируются).
    `java-test-fixtures`
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
    // Домен знает только модели: ни SQL, ни Compose (SPEC.md §4).
    api(project(":core:model"))
    implementation(libs.coroutines.core)

    // Фикстуры видят основной исходный набор; модели добавляем явно для читаемости.
    testFixturesApi(project(":core:model"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
