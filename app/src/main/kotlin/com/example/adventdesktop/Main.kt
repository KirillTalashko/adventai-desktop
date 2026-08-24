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
import com.example.adventdesktop.di.appModules
import com.example.adventdesktop.ui.App
import com.example.adventdesktop.ui.ChatState
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.parameter.parametersOf

/**
 * День 32: контейнер поднимаем ДО композиции — граф не зависит от Compose и живёт всё время процесса.
 * `Koin` передаём в композабл параметром, а не достаём глобально через `KoinPlatform.getKoin()`:
 * явная передача оставляет зависимость видимой компилятору и позволяет подменить граф в тесте.
 */
fun main() {
    val koin = startKoin { modules(appModules) }.koin
    application {
        val state = rememberAppState(koin)
        Window(
            onCloseRequest = ::exitApplication,
            title = "Визовый специалист",
            icon = painterResource("icon.png"),
            state = rememberWindowState(width = 1100.dp, height = 740.dp)
        ) {
            App(state)
        }
    }
}

/**
 * Точка сборки UI-состояния. Сам граф описан в `di/AppModule.kt`; здесь остаётся только связать
 * его со временем жизни композиции.
 */
@Composable
private fun rememberAppState(koin: Koin): ChatState {
    val scope = rememberCoroutineScope()
    // parametersOf(scope): CoroutineScope не может лежать в графе — он принадлежит композиции,
    // а не приложению, и отменяется вместе с ней. remember даёт единственность экземпляра.
    val state = remember { koin.get<ChatState> { parametersOf(scope) } }
    DisposableEffect(Unit) {
        onDispose { state.dispose() }
    }
    return state
}
