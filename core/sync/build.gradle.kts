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
    // Движок синхронизации (docs/SYNC-YANDEX.md): правила слияния и порядок обмена.
    // База и сеть — за интерфейсами SyncStore и SyncTransport, поэтому модуль не знает
    // ни SQL, ни HTTP, а тесты гоняют два «компьютера» в памяти.
    api(project(":core:domain"))
    api(project(":core:model"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testImplementation(testFixtures(project(":core:domain")))
}

tasks.test {
    useJUnitPlatform()
}
