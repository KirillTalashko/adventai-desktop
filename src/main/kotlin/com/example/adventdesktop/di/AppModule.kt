package com.example.adventdesktop.di

import com.example.adventdesktop.data.AccountStore
import com.example.adventdesktop.data.ConfigStore
import com.example.adventdesktop.data.FileStore
import com.example.adventdesktop.data.McpClient
import com.example.adventdesktop.data.McpRouter
import com.example.adventdesktop.data.appHomeDir
import com.example.adventdesktop.domain.DevToolGatewayFactory
import com.example.adventdesktop.domain.ToolGatewayFactory
import com.example.adventdesktop.ui.ChatState
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * День 32 — DI на Koin.
 *
 * Граф намеренно разрезан НА МОДУЛИ ПО СЛОЯМ, а не свален в один `appModule`: когда проект поедет
 * на Gradle-модули (шаг 2), каждый из них уедет в свой `:core:data` / `:feature:*` без переписывания —
 * границы проведены уже здесь.
 *
 * Правило: зависимости внедряются В КОНСТРУКТОРЫ. `koinInject()` внутри композаблов сознательно не
 * используется (и `koin-compose` не подключён) — иначе контейнер превращается в service locator,
 * который прячет зависимости от компилятора и от тестов.
 */

/** Слой data: файловая песочница `~/.adventai` и репозитории поверх неё. */
val dataModule = module {
    // single: состояние на диске одно на приложение. FileStore в init делает mkdirs(),
    // поэтому создавать его на каждый get() было бы лишним обращением к ФС.
    single { FileStore(appHomeDir()) }
    single { AccountStore(get()) }
    single { ConfigStore(get()) }
}

/**
 * Слой шлюзов MCP.
 *
 * Отдаём ФАБРИКИ, а не готовые `ToolGateway`: шлюз пересоздаётся на лету при смене настроек
 * (ключ DeepSeek, URL удалённого MCP на VPS, набор включённых серверов), а Koin не знает актуальной
 * конфигурации в момент сборки графа. Именованные `fun interface` вместо голых функциональных типов —
 * см. коммент в `domain/Ports.kt`.
 */
val gatewayModule = module {
    // День 18: задан URL удалённого MCP (VPS) — идём туда по SSE+токен; иначе локальный подпроцесс.
    // День 20: includeExtra — добавляем СТОРОННЕЕ MCP (server-everything по stdio) через McpRouter.
    single<ToolGatewayFactory> {
        ToolGatewayFactory { key, url, token, includeVisa, includeExtra ->
            val servers = buildList {
                if (includeVisa) add(
                    "visa-info" to (if (!url.isNullOrBlank()) McpClient(sseUrl = url, authToken = token)
                    else McpClient(deepseekApiKey = key)),
                )
                if (includeExtra) add("server-everything" to McpClient(stdioCommand = everythingCmd()))
            }
            when {
                servers.size == 1 -> servers[0].second              // один сервер — без роутера (быстрее)
                servers.isNotEmpty() -> McpRouter(servers)           // несколько — маршрутизатор
                !url.isNullOrBlank() -> McpClient(sseUrl = url, authToken = token)  // ничего не выбрано — дефолт visa
                else -> McpClient(deepseekApiKey = key)
            }
        }
    }

    // День 31: ассистент разработчика — свой локальный MCP-сервер с git-инструментами по проекту.
    // Отдельный от визового: `/help` обязан работать независимо от настроек MCP-панели.
    single<DevToolGatewayFactory> {
        DevToolGatewayFactory { McpClient(serverMainClass = "com.example.adventdesktop.mcp.DevMcpServerKt") }
    }
}

/** Слой UI-состояния. */
val stateModule = module {
    // Объявляем зависимость слоя от нижних слоёв — ровно так же, как на шаге 2 это сделает
    // Gradle (`:feature:chat` зависит от `:core:data`). Плюс без includes не работает verify():
    // verifyAll() проверяет КАЖДЫЙ модуль в изоляции, и stateModule сам по себе не видел бы
    // ни AccountStore, ни ConfigStore.
    includes(dataModule, gatewayModule)

    // factory, а НЕ single: ChatState живёт ровно столько, сколько композиция окна, и получает её
    // CoroutineScope параметром. Единственность гарантирует remember в Main.kt, а не контейнер —
    // будь тут single, Koin держал бы ChatState (и его Ktor-клиенты, MCP-подпроцессы, SQLite)
    // дольше окна, и dispose() перестал бы что-либо значить.
    factory { (scope: CoroutineScope) -> ChatState(get(), get(), get(), get(), scope) }
}

/** Полный граф приложения. Порядок не важен — Koin резолвит лениво. */
val appModules: List<Module> = listOf(dataModule, gatewayModule, stateModule)

/**
 * День 20: команда запуска стороннего MCP — референс-сервер `@modelcontextprotocol/server-everything`
 * через npx (stdio). На Windows npx — это `npx.cmd`, поэтому зовём через `cmd /c`.
 */
private fun everythingCmd(): List<String> {
    // --prefer-offline: брать пакет из кэша npm без сетевой сверки с реестром (быстрее старт после 1-й установки).
    val base = listOf("npx", "--yes", "--prefer-offline", "@modelcontextprotocol/server-everything")
    return if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
        listOf("cmd.exe", "/c") + base else base
}
