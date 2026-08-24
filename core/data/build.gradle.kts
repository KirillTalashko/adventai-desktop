plugins {
    id("advent.kotlin-library")
    kotlin("plugin.serialization")
}

dependencies {
    // api: доменные типы торчат в сигнатурах репозиториев и шлюзов (Conversation, Message,
    // LlmGateway, ToolGateway), поэтому потребители :core:data видят домен транзитивно.
    api(project(":core:domain"))

    implementation(libs.serialization.json)
    implementation(libs.bundles.ktor.client)
    // MCP (День 16): официальный Kotlin SDK (umbrella — клиент + сервер) + заглушка логов SLF4J.
    // Версия 0.10.0 — последняя на Kotlin 2.2.x; новее (0.11+) собраны на Kotlin 2.3 и
    // несовместимы с компилятором проекта 2.1.21 (читает метаданные только до 2.2.0).
    implementation(libs.mcp.sdk)
    implementation(libs.slf4j.nop)
    // День 18 (планировщик/дайджест): встроенная БД снимков визовых сводок.
    implementation(libs.sqlite.jdbc)
    // День 20: чтение текста PDF. Jar'ы лежат локально в libs/ (см. .claude) — путь от корня проекта,
    // а не от модуля, поэтому rootProject.file.
    implementation(
        files(
            rootProject.file("libs/pdfbox-2.0.31.jar"),
            rootProject.file("libs/fontbox-2.0.31.jar"),
            rootProject.file("libs/commons-logging-1.2.jar"),
        )
    )
}
