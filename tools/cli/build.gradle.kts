import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("advent.kotlin-jvm")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":core:data"))
    // День 20: харнессы читают текст PDF напрямую (сверка ФИО/дат в приложенных документах).
    implementation(
        files(
            rootProject.file("libs/pdfbox-2.0.31.jar"),
            rootProject.file("libs/fontbox-2.0.31.jar"),
            rootProject.file("libs/commons-logging-1.2.jar"),
        )
    )
    // Рантайм: харнессы поднимают MCP-серверы подпроцессом по java.class.path — см. коммент в :app.
    runtimeOnly(project(":tools:mcp"))
}

// День 31: приёмка ассистента разработчика целиком (индексация доков + git через MCP + ответы).
//   Запуск: .\gradlew.bat runDevHelp   (нужна Ollama: nomic-embed-text + qwen2.5:7b)
tasks.register<JavaExec>("runDevHelp") {
    group = "application"
    description = "День 31: /help в консоли — RAG по докам проекта + git-ветка через MCP"
    mainClass.set("com.example.adventdesktop.cli.DevHelpMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// Неделя 6, День 26: консольная проверка локальной LLM (Ollama) — 3 запроса разной сложности.
//   Запуск: .\gradlew.bat runLocalLlm   (модель по умолчанию qwen2.5:7b; своя — -Dmodel=llama3.2:3b)
tasks.register<JavaExec>("runLocalLlm") {
    group = "application"
    description = "День 26: обращение к локальной LLM через Ollama и вывод ответов на 3 запроса"
    mainClass.set("com.example.adventdesktop.cli.LocalLlmMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    System.getProperty("model")?.let { systemProperty("model", it) }
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

// Режимы памяти (селектор «Память» в композере): что каждый режим кладёт в запрос, сколько реплик
// выбрасывает и сколько платных свёрток делает. Детерминированно, без сети.
//   Запуск: .\gradlew.bat runMemoryModeCheck
tasks.register<JavaExec>("runMemoryModeCheck") {
    group = "verification"
    description = "Проверить режимы памяти: окно истории, блоки памяти, число свёрток"
    mainClass.set("com.example.adventdesktop.cli.MemoryModeCheckMainKt")
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

// Аудит агента: headless-прогон сценариев (INTAKE→…→VALIDATION) с РЕАЛЬНЫМ оркестратором (облако+MCP+RAG+страж).
// Пишет транскрипты в build/agent-audit/. -Dout=<dir> переопределяет вывод.
//   Запуск: .\gradlew.bat runAgentProbe   (нужны: ключ DeepSeek, доступ к VPS-MCP, Ollama для RAG)
tasks.register<JavaExec>("runAgentProbe") {
    group = "verification"
    description = "Аудит: прогнать «Визового специалиста» по сценариям и записать транскрипты"
    mainClass.set("com.example.adventdesktop.cli.AgentProbeMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    System.getProperty("out")?.let { jvmArgs("-Dout=$it") }
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
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
