package com.example.adventdesktop.data

import com.example.adventdesktop.domain.GatewayResponse
import com.example.adventdesktop.domain.LlmGateway
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.TokenUsage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Куда и каким ключом ходить. */
data class LlmConfig(val baseUrl: String, val apiKey: String, val model: String)

@Serializable
private data class WireMessage(val role: String, val content: String)

@Serializable
private data class WireRequest(val model: String, val messages: List<WireMessage>, val temperature: Double? = null)

@Serializable
private data class WireChoice(val message: WireMessage)

@Serializable
private data class WireUsage(val prompt_tokens: Int = 0, val completion_tokens: Int = 0, val total_tokens: Int = 0)

@Serializable
private data class WireResponse(val choices: List<WireChoice> = emptyList(), val usage: WireUsage? = null)

@Serializable
private data class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
private data class ErrorBody(val message: String? = null, val code: Int? = null)

/** Реализация порта [LlmGateway] на Ktor + JVM-движок CIO. Ошибки API пробрасываются с понятным текстом. */
class LlmClient(private val config: LlmConfig) : LlmGateway {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
        }
    }

    override suspend fun complete(messages: List<Message>): GatewayResponse {
        val response: HttpResponse = http.post(config.baseUrl) {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(
                WireRequest(
                    model = config.model,
                    messages = messages.map { WireMessage(it.role.wire, it.text) },
                    temperature = 0.4
                )
            )
        }

        if (!response.status.isSuccess()) {
            val raw = response.bodyAsText()
            val message = runCatching { json.decodeFromString<ErrorEnvelope>(raw).error?.message }.getOrNull()
                ?: raw.take(300)
            throw IllegalStateException("Ошибка ${response.status.value}: $message")
        }

        val parsed = response.body<WireResponse>()
        val text = parsed.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (text.isEmpty()) {
            throw IllegalStateException("Модель вернула пустой ответ (возможно, временный лимит). Смените модель или повторите.")
        }
        val usage = parsed.usage?.let { TokenUsage(it.prompt_tokens, it.completion_tokens, it.total_tokens) }
        return GatewayResponse(text = text, usage = usage)
    }

    fun close() = http.close()
}
