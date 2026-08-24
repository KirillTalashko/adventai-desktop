import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Корневой проект кода не содержит — только объявляет версии плагинов для модулей.
// `apply false`: плагин попадает на classpath сборки, но включается лишь там, где он реально нужен
// (например, Compose — только в :app, поэтому в fat-jar'ы :tools:* skiko больше не попадает).
plugins {
    kotlin("jvm") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    // День 18: fat-jar'ы для деплоя на VPS.
    id("com.gradleup.shadow") version "8.3.6" apply false
}

// Общий JVM-таргет (DRY): срабатывает в момент, когда модуль подключает kotlin("jvm"),
// поэтому каждому build-файлу не нужно повторять один и тот же блок.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            // Совпадает с компилятором Java (JDK 21) — без провижининга toolchain.
            compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        }
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}
