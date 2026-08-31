// Корневой проект кода не содержит и модули не конфигурирует — общая обвязка живёт в
// convention-плагинах `build-logic` (advent.kotlin-jvm / advent.kotlin-library), а версии — в
// gradle/libs.versions.toml. Здесь остаётся объявить версии плагинов для модулей и гейт границ.
// `apply false`: плагин попадает на classpath сборки, но включается лишь там, где он нужен —
// поэтому Compose не протекает в fat-jar'ы :tools:*.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    // День 18: fat-jar'ы для деплоя на VPS.
    alias(libs.plugins.shadow) apply false
    // B3: гейт границ модулей — применяется к корню, а не к модулям.
    alias(libs.plugins.moduleGraphAssert)
}

// B3 (.claude/MODULARIZATION.md) — направление зависимостей как ПРОВЕРЯЕМОЕ правило, а не как
// структура, которую можно случайно нарушить одной строкой в build-файле.
//   Запуск: .\gradlew.bat assertModuleGraph
moduleGraphAssert {
    // :tools:cli -> :tools:mcp -> :core:data -> :core:domain — самая длинная цепочка.
    maxHeight = 4
    // Разрешено ровно это. Любое НЕ перечисленное ребро роняет задачу — включая случайно
    // добавленное `implementation(project(":tools:mcp"))` в :app.
    allowed = arrayOf(
        ":app -> :core:.*",
        ":app -> :tools:mcp",        // ТОЛЬКО runtimeOnly: подпроцесс MCP по java.class.path
        ":core:data -> :core:domain",
        ":tools:.* -> :core:.*",
        ":tools:cli -> :tools:mcp",  // харнессы поднимают MCP-серверы тем же способом
    )
    // Явные запреты — дублируют allowed по смыслу, но дают внятное сообщение об ошибке.
    restricted = arrayOf(
        ":core:.* -X> :app",
        ":core:.* -X> :tools:.*",
        ":core:domain -X> :core:data",
    )
}
