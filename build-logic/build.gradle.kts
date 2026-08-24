plugins {
    // Позволяет писать convention-плагины как обычные .gradle.kts в src/main/kotlin.
    `kotlin-dsl`
}

dependencies {
    // Нужен, чтобы в convention-плагине можно было написать plugins { kotlin("jvm") } и трогать
    // KotlinJvmProjectExtension.
    implementation(libs.kotlin.gradlePlugin)
}
