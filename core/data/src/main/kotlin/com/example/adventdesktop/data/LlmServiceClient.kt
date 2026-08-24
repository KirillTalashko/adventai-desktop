package com.example.adventdesktop.data

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Результат вызова приватного LLM-сервиса (День 30): HTTP-статус, тело ответа, задержка (мс). */
data class ServiceCall(val status: Int, val body: String, val ms: Long)

@Serializable
private data class ServiceChatReq(val prompt: String)

/**
 * Клиент к приватному LLM-сервису (День 30, [com.example.adventdesktop.service.LocalLlmService]) — для
 * dev-панели «обращение по HTTP». БЕЗ прокси (сервис локальный или за своим reverse-proxy — как
 * `OllamaEmbedder`/`LocalLlmClient`). Показывает сетевой доступ к приватному сервису прямо из UI приложения.
 */
class LlmServiceClient(baseUrl: String, private val token: String?) {
    private val base = baseUrl.trim().trimEnd('/')
    private val json = Json { encodeDefaults = true }
    private val http = cioJsonClient(requestTimeoutMs = 300_000, connectTimeoutMs = 5_000, json = json, contentNegotiation = false)

    /** `GET /health` — жив ли сервис и доступен ли по сети. */
    suspend fun health(): ServiceCall = timed { http.get("$base/health") }

    /** `POST /chat` — отправить запрос в локальную модель через сервис (с bearer-токеном, если задан). */
    suspend fun chat(prompt: String): ServiceCall = timed {
        http.post("$base/chat") {
            if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ServiceChatReq(prompt)))
        }
    }

    private suspend fun timed(block: suspend () -> HttpResponse): ServiceCall {
        val start = System.currentTimeMillis()
        return try {
            val r = block()
            ServiceCall(r.status.value, r.bodyAsText(), System.currentTimeMillis() - start)
        } catch (e: CancellationException) {
            throw e   // отмена запроса — не «сетевая ошибка», пробрасываем
        } catch (e: Exception) {
            ServiceCall(0, "ошибка сети: ${e.message}", System.currentTimeMillis() - start)
        }
    }

    fun close() = http.close()
}
