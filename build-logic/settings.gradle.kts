// Отдельная сборка (included build) для convention-плагинов. Каталог версий переиспользуем
// из основного проекта — версия Kotlin обязана совпадать с той, что применяется в модулях.
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
