import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Базовая обвязка любого Kotlin-модуля проекта. Пришла на смену subprojects {} в корне:
// кросс-проектная конфигурация ломает project isolation и не даёт Gradle настраивать модули
// независимо. Здесь же — единственное место, где живёт JVM-таргет и подключается detekt.
plugins {
    kotlin("jvm")
    id("io.gitlab.arturbosch.detekt")
}

kotlin {
    // Совпадает с компилятором Java (JDK 21) — без провижининга toolchain.
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// B3: detekt как fitness-функция трека расшивки. Конфиг один на проект и включает ТОЛЬКО
// правила сложности (LargeClass / LongMethod / TooManyFunctions) — остальные рулсеты выключены,
// чтобы сигнал не тонул в стилистическом шуме.
val detektBaseline = file("detekt-baseline.xml")
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    // baseline фиксирует ДОЛГ на момент конца фазы B: сборка зелёная, но новые нарушения ловятся.
    if (detektBaseline.exists()) baseline = detektBaseline
}
