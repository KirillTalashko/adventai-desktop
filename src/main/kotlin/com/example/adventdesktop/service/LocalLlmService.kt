package com.example.adventdesktop.service

import com.example.adventdesktop.data.LOCAL_LLM_SYSTEM
import com.example.adventdesktop.data.LocalLlmClient
import com.example.adventdesktop.domain.LlmParams
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * День 30 — **приватный AI-сервис на базе локальной LLM**. Тонкий HTTP-фасад вокруг [LocalLlmClient] (Ollama):
 * отдаёт чат по сети с токен-авторизацией и базовыми ограничениями. Разворачивается на VPS/домашнем сервере
 * тем же паттерном, что `VisaMcpServer` (Ktor CIO + bearer + бинд на loopback за reverse-proxy).
 *
 * Эндпойнты:
 * - `GET  /health` — liveness (без авторизации; для reverse-proxy и мониторинга).
 * - `POST /chat`   — чат (bearer-токен). Тело: `{"prompt":"…"}` ИЛИ `{"messages":[{"role","content"}], …}`;
 *   опц. `temperature`, `maxTokens`. Ответ: `{"reply","model","promptTokens","completionTokens","totalTokens","ms"}`.
 *
 * Базовые ограничения (ДЗ дня):
 * - **rate limit** — не более `LLM_RATE_PER_MIN` запросов/мин на токен → `429`.
 * - **max context** — суммарная длина сообщений > `LLM_MAX_CONTEXT_CHARS` символов → `413`.
 * - **параллелизм** — не более `LLM_MAX_INFLIGHT` одновременных генераций (CPU-модель) → `503` (защита от перегруза).
 *
 * Всё через env (секретов в коде нет): `LLM_PORT` (3002), `LLM_HOST` (127.0.0.1), `LLM_AUTH_TOKEN`,
 * `LLM_MODEL` (qwen2.5:7b), `OLLAMA_URL` (http://localhost:11434), `LLM_RATE_PER_MIN` (20),
 * `LLM_MAX_CONTEXT_CHARS` (8000), `LLM_MAX_INFLIGHT` (2).
 */

@Serializable
private data class ChatMessageDto(val role: String = "user", val content: String = "")

@Serializable
private data class ChatRequest(
    val prompt: String? = null,
    val messages: List<ChatMessageDto> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

@Serializable
private data class ChatResponse(
    val reply: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val ms: Long,
)

@Serializable
private data class ErrorResponse(val error: String)

/** Простой in-memory rate-limiter: скользящее окно 60 c на ключ (токен). Потокобезопасен (грубый общий лок). */
private class RateLimiter(private val perMinute: Int) {
    private val hits = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun allow(key: String, nowMs: Long): Boolean {
        val dq = hits.getOrPut(key) { ArrayDeque() }
        val windowStart = nowMs - 60_000
        while (dq.isNotEmpty() && dq.first() < windowStart) dq.removeFirst()
        if (dq.size >= perMinute) return false
        dq.addLast(nowMs)
        return true
    }
}

private fun env(name: String): String? = System.getenv(name)?.trim()?.ifBlank { null }

private fun roleOf(s: String): Role = when (s.lowercase()) {
    "system" -> Role.System
    "assistant" -> Role.Assistant
    else -> Role.User
}

fun main() {
    val port = env("LLM_PORT")?.toIntOrNull() ?: 3002
    val host = env("LLM_HOST") ?: "127.0.0.1"   // loopback за reverse-proxy (defense-in-depth); 0.0.0.0 — осознанно
    val token = env("LLM_AUTH_TOKEN")
    val model = env("LLM_MODEL") ?: LocalLlmClient.DEFAULT_MODEL
    val ollamaUrl = env("OLLAMA_URL") ?: "http://localhost:11434"
    val maxChars = env("LLM_MAX_CONTEXT_CHARS")?.toIntOrNull() ?: 8000
    val ratePerMin = env("LLM_RATE_PER_MIN")?.toIntOrNull() ?: 20
    val maxInflight = env("LLM_MAX_INFLIGHT")?.toIntOrNull() ?: 2

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val client = LocalLlmClient(ollamaUrl, model)
    val limiter = RateLimiter(ratePerMin)
    val inflight = Semaphore(maxInflight)

    System.err.println(
        "local-llm-service host=$host port=$port model=$model auth=${token != null} " +
            "rate=$ratePerMin/min maxChars=$maxChars maxInflight=$maxInflight ollama=$ollamaUrl",
    )
    if (token == null) System.err.println("ВНИМАНИЕ: LLM_AUTH_TOKEN не задан — /chat открыт без авторизации (только для локальной отладки).")

    suspend fun handleChat(call: ApplicationCall) {
        // Rate limit по токену (для приватного сервиса — общий бюджет на ключ). Проверяем ДО генерации.
        if (!limiter.allow(token ?: "anon", System.currentTimeMillis())) {
            call.respondText(json.encodeToString(ErrorResponse("rate limit exceeded ($ratePerMin/min)")), ContentType.Application.Json, HttpStatusCode.TooManyRequests)
            return
        }
        val req = runCatching { json.decodeFromString<ChatRequest>(call.receiveText()) }.getOrNull()
        if (req == null || (req.prompt.isNullOrBlank() && req.messages.isEmpty())) {
            call.respondText(json.encodeToString(ErrorResponse("bad request: нужен 'prompt' или 'messages'")), ContentType.Application.Json, HttpStatusCode.BadRequest)
            return
        }
        val messages = if (req.messages.isNotEmpty()) {
            req.messages.map { Message(roleOf(it.role), it.content) }
        } else {
            listOf(Message(Role.System, LOCAL_LLM_SYSTEM), Message(Role.User, req.prompt!!))
        }
        val totalChars = messages.sumOf { it.text.length }
        if (totalChars > maxChars) {
            call.respondText(json.encodeToString(ErrorResponse("context too large: $totalChars символов > лимита $maxChars")), ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
            return
        }
        // Кап параллельных генераций: CPU-модель тянет мало запросов разом — лишние отбиваем сразу (503), не копим очередь.
        if (!inflight.tryAcquire()) {
            call.respondText(json.encodeToString(ErrorResponse("server busy: слишком много одновременных запросов")), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            return
        }
        try {
            val start = System.currentTimeMillis()
            val resp = client.complete(messages, params = LlmParams(temperature = req.temperature ?: 0.3, maxTokens = req.maxTokens))
            val ms = System.currentTimeMillis() - start
            val u = resp.usage
            call.respondText(
                json.encodeToString(ChatResponse(resp.text, model, u?.prompt ?: 0, u?.completion ?: 0, u?.total ?: 0, ms)),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            call.respondText(json.encodeToString(ErrorResponse("generation error: ${e.message}")), ContentType.Application.Json, HttpStatusCode.InternalServerError)
        } finally {
            inflight.release()
        }
    }

    embeddedServer(ServerCIO, host = host, port = port) {
        install(Authentication) {
            bearer("llm") {
                authenticate { cred -> if (token != null && cred.token == token) UserIdPrincipal("client") else null }
            }
        }
        routing {
            get("/health") { call.respondText("""{"status":"ok","model":"$model"}""", ContentType.Application.Json) }
            if (token != null) {
                authenticate("llm") { post("/chat") { handleChat(call) } }
            } else {
                post("/chat") { handleChat(call) }
            }
        }
    }.start(wait = true)
}
