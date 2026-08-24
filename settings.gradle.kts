pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "AdventAiDesktop"

// День 32, шаг 2 — модули. Стрелки зависимостей идут строго вверх; вниз их не пропустит компилятор,
// а не код-ревью:
//   :core:domain    чистый Kotlin, знает только про корутины
//   :core:data      -> :core:domain                (Ktor-клиент, serialization, sqlite, pdfbox, MCP SDK)
//   :app            -> :core:data                 (Compose + Koin; composition root)
//   :tools:cli      -> :core:data                 (консольные харнессы + fat-jar visa-cli)
//   :tools:mcp      -> :core:data                 (MCP-серверы + fat-jar для VPS)
//   :tools:service  -> :core:data                 (приватный HTTP LLM-сервис + fat-jar)
include(":core:domain", ":core:data", ":app", ":tools:cli", ":tools:mcp", ":tools:service")
