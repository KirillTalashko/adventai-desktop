package com.example.adventdesktop.data

import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.ProxyConfig
import io.ktor.http.Url

/**
 * Процесс-глобальный HTTP-прокси для всех Ktor-клиентов приложения (LLM + удалённый MCP). На сетях, где
 * прямой выход и DNS закрыты, но есть локальный туннель (напр. `http://127.0.0.1:10809`), без прокси Ktor
 * падает с `UnresolvedAddressException`. Значение выставляется из настроек (`DesktopConfig.httpProxy`) при
 * пересборке агента; пусто → прямое соединение (для обычных сетей поведение не меняется).
 */
object HttpProxy {
    @Volatile
    var url: String? = null

    /** Готовый [ProxyConfig] для `engine { proxy = … }`, либо null (прямое соединение). */
    fun configOrNull(): ProxyConfig? =
        url?.trim()?.takeIf { it.isNotEmpty() }?.let { ProxyBuilder.http(Url(it)) }
}
