import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.7.3"
    // День 18: fat-jar MCP-сервера для деплоя на VPS.
    id("com.gradleup.shadow") version "8.3.6"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
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
    // День 18 (планировщик/дайджест): встроенная БД снимков визовых сводок (фоновый сбор по расписанию).
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    // День 20: чтение текста PDF для навыка docs (сверка, что документы на одного заявителя).
    // Jar'ы лежат локально в libs/ (у gradle в этой среде нет DNS до Maven Central — см. .claude).
    implementation(files("libs/pdfbox-2.0.31.jar", "libs/fontbox-2.0.31.jar", "libs/commons-logging-1.2.jar"))
    // День 18 (remote-транспорт MCP-сервера на VPS): Ktor-сервер + SSE + bearer-авторизация.
    implementation("io.ktor:ktor-server-core:3.1.3")
    implementation("io.ktor:ktor-server-cio:3.1.3")
    implementation("io.ktor:ktor-server-sse:3.1.3")
    implementation("io.ktor:ktor-server-auth:3.1.3")
}

kotlin {
    // Совпадает с компилятором Java (JDK 21) — без провижининга toolchain.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// День 16 (MCP, Вариант 2): консольная проверка — клиент подключается к локальному
// MCP-серверу (поднимается как подпроцесс через stdio) и печатает список инструментов.
//   Запуск: .\gradlew.bat runMcpDemo
// День 31: приёмка ассистента разработчика целиком (индексация доков + git через MCP + ответы).
//   Запуск: .\gradlew.bat runDevHelp   (нужна Ollama: nomic-embed-text + qwen2.5:7b)
tasks.register<JavaExec>("runDevHelp") {
    group = "application"
    description = "День 31: /help в консоли — RAG по докам проекта + git-ветка через MCP"
    mainClass.set("com.example.adventdesktop.cli.DevHelpMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// День 31: приёмка dev-MCP (git-инструменты ассистента разработчика).
//   Запуск: .\gradlew.bat runDevMcp
tasks.register<JavaExec>("runDevMcp") {
    group = "application"
    description = "День 31: dev-MCP — текущая ветка, статус и файлы проекта через MCP"
    mainClass.set("com.example.adventdesktop.mcp.DevMcpDemoMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.register<JavaExec>("runMcpDemo") {
    group = "application"
    description = "День 16: подключение к MCP и вывод списка доступных инструментов"
    mainClass.set("com.example.adventdesktop.mcp.McpDemoMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    // UTF-8 для вывода: file.encoding + stdout/stderr.encoding (Java 18+ берёт их для System.out/err).
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// Неделя 6, День 26: консольная проверка локальной LLM (Ollama) — 3 запроса разной сложности.
//   Запуск: .\gradlew.bat runLocalLlm   (модель по умолчанию qwen2.5:7b; своя — -Pmodel=llama3.2:3b)
tasks.register<JavaExec>("runLocalLlm") {
    group = "application"
    description = "День 26: обращение к локальной LLM через Ollama и вывод ответов на 3 запроса"
    mainClass.set("com.example.adventdesktop.cli.LocalLlmMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    // Своя модель: .\gradlew.bat runLocalLlm -Dmodel=llama3.2:3b (пробрасываем системное свойство в форк).
    System.getProperty("model")?.let { systemProperty("model", it) }
}

// День 18: fat-jar MCP-сервера для деплоя на VPS (java -jar visa-mcp-server-all.jar).
tasks.register<ShadowJar>("mcpServerJar") {
    group = "build"
    description = "Fat-jar MCP-сервера (День 18) для деплоя на VPS"
    archiveBaseName.set("visa-mcp-server")
    archiveClassifier.set("all")
    archiveVersion.set("")
    from(sourceSets["main"].output)
    configurations = listOf(project.configurations.runtimeClasspath.get())
    manifest { attributes["Main-Class"] = "com.example.adventdesktop.mcp.VisaMcpServerKt" }
    mergeServiceFiles()      // JDBC-драйвер и Ktor используют META-INF/services
    isZip64 = true           // в classpath есть крупные зависимости (compose/skiko)
}

// День 20: standalone CLI «Визового специалиста» (Skill + CLI). Запуск: java -jar visa-cli.jar <команда>.
tasks.register<ShadowJar>("visaCliJar") {
    group = "build"
    description = "Fat-jar локального CLI (День 20, Skill + CLI)"
    archiveBaseName.set("visa-cli")
    archiveClassifier.set("all")
    archiveVersion.set("")
    from(sourceSets["main"].output)
    configurations = listOf(project.configurations.runtimeClasspath.get())
    manifest { attributes["Main-Class"] = "com.example.adventdesktop.cli.VisaCliMainKt" }
    mergeServiceFiles()
    isZip64 = true
}

// Страновой скоуп RAG: приёмка «агент не отвечает по правилам чужой страны» (инварианты словаря + ретрив).
//   Запуск: .\gradlew.bat runRagCountryCheck   (часть B требует Ollama + построенный индекс)
tasks.register<JavaExec>("runRagCountryCheck") {
    group = "verification"
    description = "Проверить страновой скоуп RAG: чужая страна не попадает в выдачу"
    mainClass.set("com.example.adventdesktop.cli.RagCountryCheckMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// A1 (сеть безопасности рефакторинга): характеризующий харнесс потока задачи (TaskOrchestrator), без сети.
//   Запуск: .\gradlew.bat runTaskFlowCheck
tasks.register<JavaExec>("runTaskFlowCheck") {
    group = "verification"
    description = "Характеризующие проверки TaskOrchestrator (стадии/переходы) — сеть безопасности перед расшивкой ChatState"
    mainClass.set("com.example.adventdesktop.cli.TaskFlowCheckMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// День 30: fat-jar приватного LLM-сервиса (java -jar visa-llm-service-all.jar; настройки — через env).
tasks.register<ShadowJar>("llmServiceJar") {
    group = "build"
    description = "Fat-jar приватного LLM-сервиса (День 30) для деплоя на VPS/домашний сервер"
    archiveBaseName.set("visa-llm-service")
    archiveClassifier.set("all")
    archiveVersion.set("")
    from(sourceSets["main"].output)
    configurations = listOf(project.configurations.runtimeClasspath.get())
    manifest { attributes["Main-Class"] = "com.example.adventdesktop.service.LocalLlmServiceKt" }
    mergeServiceFiles()
    isZip64 = true
}

// День 30: локальный запуск сервиса для проверки (блокирует; настройки — env LLM_PORT/LLM_AUTH_TOKEN/…).
tasks.register<JavaExec>("runLocalLlmService") {
    group = "application"
    description = "День 30: поднять приватный LLM-сервис локально"
    mainClass.set("com.example.adventdesktop.service.LocalLlmServiceKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// День 30: клиент-проверка сервиса (сеть, чат, лимиты, стабильность). URL/токен — env LLM_SERVICE_URL/LLM_AUTH_TOKEN.
tasks.register<JavaExec>("runLlmServiceDemo") {
    group = "application"
    description = "День 30: проверить LLM-сервис (health, chat, rate/context limits, параллелизм)"
    mainClass.set("com.example.adventdesktop.service.LlmServiceDemoMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

compose.desktop {
    application {
        mainClass = "com.example.adventdesktop.MainKt"
        nativeDistributions {
            // .exe/.msi требуют WiX; для запуска без установщика используем app-image (createDistributable).
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "AdventAI"
            packageVersion = "1.0.0"
            description = "AdventAI — визовый специалист (desktop)"
            vendor = "AdventAI"
            windows {
                iconFile.set(project.file("icon.ico"))
            }
        }
    }
}
