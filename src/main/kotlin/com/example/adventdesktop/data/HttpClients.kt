package com.example.adventdesktop.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Единая фабрика Ktor CIO-клиента для JSON-API data-слоя. Сводит 5 почти одинаковых конструкций
 * (Embedders / LlmClient / LlmServiceClient / LocalLlmClient×2) в один вызов и **централизует
 * разъехавшиеся таймауты** (были 60/120/300/8 c порознь по файлам). SSE-клиент MCP и серверные
 * клиенты сюда НЕ входят — у них своя конфигурация (SSE, requestTimeout=0, defaultRequest-токен).
 *
 * @param useProxy включить прокси из [HttpProxy] (сети с локальным туннелем, где прямой выход закрыт).
 * @param contentNegotiation ставить `ContentNegotiation(json)`; `false` — если тело сериализуется вручную.
 */
fun cioJsonClient(
    requestTimeoutMs: Long,
    connectTimeoutMs: Long,
    json: Json,
    contentNegotiation: Boolean = true,
    useProxy: Boolean = false,
): HttpClient = HttpClient(CIO) {
    if (contentNegotiation) install(ContentNegotiation) { json(json) }
    install(HttpTimeout) {
        requestTimeoutMillis = requestTimeoutMs
        connectTimeoutMillis = connectTimeoutMs
    }
    if (useProxy) engine { HttpProxy.configOrNull()?.let { proxy = it } }
}
