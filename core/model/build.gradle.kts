import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Явный jvmTarget: иначе Kotlin целится в 1.8, а javac — в JDK запуска (19),
// и сборка падает на несовместимости таргетов.
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
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
