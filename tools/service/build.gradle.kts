import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":core:data"))
    implementation("io.ktor:ktor-server-core:3.1.3")
    implementation("io.ktor:ktor-server-cio:3.1.3")
    implementation("io.ktor:ktor-server-auth:3.1.3")
    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-cio:3.1.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
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
    // День 32: после распила модуль не видит Compose/skiko, и jar резко похудел — раньше zip64
    // был обязателен именно из-за них.
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
