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
            toolGatewayFactory = { key, url, token ->
                if (!url.isNullOrBlank()) McpClient(sseUrl = url, authToken = token)
                else McpClient(deepseekApiKey = key)
            },
            scope = scope
        )
    }
    DisposableEffect(Unit) {
        onDispose { state.dispose() }
    }
    return state
}
