import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    // Домен приезжает транзитивно через api в :core:data.
    implementation(project(":core:data"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // DI — см. коммент о версии в :core:data (тот же капкан с метаданными Kotlin).
    // Только koin-core: koin-compose тянет compose-runtime/foundation 1.8.2 против 1.7.3 здесь.
    implementation("io.insert-koin:koin-core:4.1.1")

    // РАНТАЙМ-связь, невидимая компилятору. McpClient поднимает MCP-сервер подпроцессом:
    //   java -cp <System.getProperty("java.class.path")> com.example.adventdesktop.mcp.*ServerKt
    // Компиляционной зависимости на :tools:mcp нет и быть не должно (граница слоёв цела), но без
    // этой строки локальный MCP и команда `/help` падали бы с ClassNotFoundException уже в рантайме.
    runtimeOnly(project(":tools:mcp"))

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("io.insert-koin:koin-test:4.1.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Тесты на JUnit Platform (JUnit 5); kotlin("test") сам подставляет вариант kotlin-test-junit5.
//   Запуск: .\gradlew.bat test
tasks.test {
    useJUnitPlatform()
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
