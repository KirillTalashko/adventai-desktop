plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}

dependencies {
    // api: доменные типы торчат в сигнатурах репозиториев и шлюзов (Conversation, Message,
    // LlmGateway, ToolGateway), поэтому потребители :core:data видят домен транзитивно.
    api(project(":core:domain"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-cio:3.1.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
    // MCP (День 16): официальный Kotlin SDK (umbrella — клиент + сервер) + заглушка логов SLF4J.
    // Версия 0.10.0 — последняя на Kotlin 2.2.x; новее (0.11+) собраны на Kotlin 2.3 и
    // несовместимы с компилятором проекта 2.1.21 (читает метаданные только до 2.2.0).
    implementation("io.modelcontextprotocol:kotlin-sdk:0.10.0")
    implementation("org.slf4j:slf4j-nop:2.0.16")
    // День 18 (планировщик/дайджест): встроенная БД снимков визовых сводок.
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
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
