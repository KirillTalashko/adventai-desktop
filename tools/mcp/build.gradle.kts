import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("advent.kotlin-jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":core:data"))
    // День 18 (remote-транспорт MCP-сервера на VPS): Ktor-сервер + SSE + bearer-авторизация.
    implementation(libs.bundles.ktor.server)
    implementation(libs.ktor.server.sse)   // SSE нужен только MCP-серверу (транспорт на VPS)
    // День 32: в монолите эти два приезжали транзитивно из :core:data, хотя VisaMcpServer
    // импортирует их напрямую (клиентский ContentNegotiation + kotlinx-json).
    // Распил вскрыл скрытую зависимость — объявляем явно.
    implementation(libs.bundles.ktor.client)
    implementation(libs.mcp.sdk)
    implementation(libs.serialization.json)
    implementation(libs.sqlite.jdbc)
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
