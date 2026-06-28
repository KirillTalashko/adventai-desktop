package com.example.adventdesktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.adventdesktop.data.AccountStore
import com.example.adventdesktop.data.ConfigStore
import com.example.adventdesktop.data.FileStore
import com.example.adventdesktop.data.McpClient
import com.example.adventdesktop.data.McpRouter
import com.example.adventdesktop.data.appHomeDir
import com.example.adventdesktop.ui.App
import com.example.adventdesktop.ui.ChatState

fun main() = application {
    val state = rememberAppState()
    Window(
        onCloseRequest = ::exitApplication,
        title = "Визовый специалист",
        icon = painterResource("icon.png"),
        state = rememberWindowState(width = 1100.dp, height = 740.dp)
    ) {
        App(state)
    }
}

/**
 * День 20: команда запуска стороннего MCP — референс-сервер `@modelcontextprotocol/server-everything` через
 * npx (stdio). На Windows npx — это `npx.cmd`, поэтому зовём через `cmd /c`.
 */
private fun everythingCmd(): List<String> {
    // --prefer-offline: брать пакет из кэша npm без сетевой сверки с реестром (быстрее старт после 1-й установки).
    val base = listOf("npx", "--yes", "--prefer-offline", "@modelcontextprotocol/server-everything")
    return if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
        listOf("cmd.exe", "/c") + base else base
}

/** Composition root: собираем зависимости вручную (KISS). */
@Composable
private fun rememberAppState(): ChatState {
    val scope = rememberCoroutineScope()
    val state = remember {
        val store = FileStore(appHomeDir())
        ChatState(
            accounts = AccountStore(store),
            configStore = ConfigStore(store),
            // День 18: задан URL удалённого MCP (VPS) → ходим туда по SSE+токен; иначе локальный подпроцесс.
            // День 20: extraMcp=true → оборачиваем в McpRouter и добавляем СТОРОННЕЕ MCP (server-everything по stdio).
            toolGatewayFactory = { key, url, token, includeVisa, includeExtra ->
                val servers = buildList {
                    if (includeVisa) add(
                        "visa-info" to (if (!url.isNullOrBlank()) McpClient(sseUrl = url, authToken = token)
                        else McpClient(deepseekApiKey = key)),
                    )
                    if (includeExtra) add("server-everything" to McpClient(stdioCommand = everythingCmd()))
                }
                when {
                    servers.size == 1 -> servers[0].second               // один сервер — без роутера (быстрее)
                    servers.isNotEmpty() -> McpRouter(servers)            // несколько — маршрутизатор
                    !url.isNullOrBlank() -> McpClient(sseUrl = url, authToken = token)  // ничего не выбрано — дефолт visa
                    else -> McpClient(deepseekApiKey = key)
                }
            },
            scope = scope
        )
    }
    DisposableEffect(Unit) {
        onDispose { state.dispose() }
    }
    return state
}
