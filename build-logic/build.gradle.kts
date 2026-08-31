plugins {
    // Позволяет писать convention-плагины как обычные .gradle.kts в src/main/kotlin.
    `kotlin-dsl`
}

dependencies {
    // Нужен, чтобы в convention-плагине можно было написать plugins { kotlin("jvm") } и трогать
    // KotlinJvmProjectExtension.
    implementation(libs.kotlin.gradlePlugin)
    // B3: detekt применяется из convention-плагина, значит его Gradle-плагин нужен здесь.
    implementation(libs.detekt.gradlePlugin)
}
