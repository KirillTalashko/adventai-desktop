import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Базовая обвязка любого Kotlin-модуля проекта. Пришла на смену subprojects {} в корне:
// кросс-проектная конфигурация ломает project isolation и не даёт Gradle настраивать модули
// независимо. Здесь же — единственное место, где живёт JVM-таргет.
plugins {
    kotlin("jvm")
}

kotlin {
    // Совпадает с компилятором Java (JDK 21) — без провижининга toolchain.
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
