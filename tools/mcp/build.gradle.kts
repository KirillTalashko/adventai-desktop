import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":core:data"))
    // День 18 (remote-транспорт MCP-сервера на VPS): Ktor-сервер + SSE + bearer-авторизация.
    implementation("io.ktor:ktor-server-core:3.1.3")
    implementation("io.ktor:ktor-server-cio:3.1.3")
    implementation("io.ktor:ktor-server-sse:3.1.3")
    implementation("io.ktor:ktor-server-auth:3.1.3")
    // День 32: в монолите эти два приезжали транзитивно из :core:data, хотя VisaMcpServer
    // импортирует их напрямую (клиентский ContentNegotiation + kotlinx-json).
    // Распил вскрыл скрытую зависимость — объявляем явно.
    implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-cio:3.1.3")
    implementation("io.modelcontextprotocol:kotlin-sdk:0.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
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

// День 16 (MCP, Вариант 2): клиент подключается к локальному MCP-серверу (подпроцесс, stdio).
//   Запуск: .\gradlew.bat runMcpDemo
tasks.register<JavaExec>("runMcpDemo") {
    group = "application"
    description = "День 16: подключение к MCP и вывод списка доступных инструментов"
    mainClass.set("com.example.adventdesktop.mcp.McpDemoMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    // UTF-8 для вывода: file.encoding + stdout/stderr.encoding (Java 18+ берёт их для System.out/err).
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
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
    // День 32: Compose/skiko сюда больше не попадают — модуль их не видит. Флаг оставлен на случай
    // роста зависимостей (sqlite + Ktor + MCP SDK), стоит дёшево.
    isZip64 = true
}
