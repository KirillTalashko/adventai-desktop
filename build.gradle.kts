// Корневой проект кода не содержит и модули не конфигурирует — общая обвязка живёт в
// convention-плагинах `build-logic` (advent.kotlin-jvm / advent.kotlin-library), а версии — в
// gradle/libs.versions.toml. Здесь остаётся только объявить версии плагинов для модулей.
// `apply false`: плагин попадает на classpath сборки, но включается лишь там, где он нужен —
// поэтому Compose не протекает в fat-jar'ы :tools:*.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    // День 18: fat-jar'ы для деплоя на VPS.
    alias(libs.plugins.shadow) apply false
}
