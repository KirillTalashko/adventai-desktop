import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.7.3"
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
tasks.register<JavaExec>("runMcpDemo") {
    group = "application"
    description = "День 16: подключение к MCP и вывод списка доступных инструментов"
    mainClass.set("com.example.adventdesktop.mcp.McpDemoMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    // UTF-8 для вывода: file.encoding + stdout/stderr.encoding (Java 18+ берёт их для System.out/err).
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
