package com.example.adventdesktop.data

import com.example.adventdesktop.domain.rag.Embedder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class OllamaEmbedRequest(val model: String, val prompt: String)

@Serializable
private data class OllamaEmbedResponse(val embedding: List<Double> = emptyList())

/**
 * Эмбеддер на **локальной Ollama** (День 21) — путь курса недели RAG. Обычный HTTP-клиент на
 * `localhost:11434/api/embeddings` (модель по умолчанию `nomic-embed-text`, 768). Локальный адрес → БЕЗ
 * прокси (в отличие от [LlmClient]/[McpClient]; см. [HttpProxy]) и без проблем DNS. Бесплатно, данные не
 * покидают машину. Требует запущенной `ollama serve` и `ollama pull <model>` — иначе внятная ошибка.
 */
class OllamaEmbedder(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "nomic-embed-text",
    override val dimension: Int = 768,
) : Embedder {

    override val id: String = "ollama:$model"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 5_000
        }
    }

    override suspend fun embed(text: String): FloatArray {
        val resp: HttpResponse = runCatching {
            http.post("$baseUrl/api/embeddings") {
                contentType(ContentType.Application.Json)
                setBody(OllamaEmbedRequest(model, text.ifBlank { " " }))
            }
        }.getOrElse { e ->
            error("Ollama недоступна ($baseUrl): ${e.message}. Запусти `ollama serve` и `ollama pull $model`.")
        }
        if (!resp.status.isSuccess()) {
            error("Ollama ${resp.status}: ${resp.bodyAsText().take(300)}")
        }
        val body: OllamaEmbedResponse = resp.body()
        if (body.embedding.isEmpty()) error("Ollama вернула пустой эмбеддинг для модели $model")
        return FloatArray(body.embedding.size) { body.embedding[it].toFloat() }
    }

    // nomic-embed-text обучен с task-префиксами: документы индекса и запросы кодируются по-разному
    // (asymmetric retrieval) — это заметно улучшает различимость близостей. Для не-nomic моделей префиксы
    // не добавляем (могут навредить). См. https://huggingface.co/nomic-ai/nomic-embed-text-v1.5
    private val usesPrefix = model.contains("nomic", ignoreCase = true)

    override suspend fun embedDocument(text: String): FloatArray =
        embed(if (usesPrefix) "search_document: $text" else text)

    override suspend fun embedQuery(text: String): FloatArray =
        embed(if (usesPrefix) "search_query: $text" else text)

    fun close() = http.close()
}

/**
 * Детерминированный фолбэк-эмбеддер (без сети) — чтобы весь пайплайн индексации, метаданные и сравнение
 * стратегий работали и демонстрировались БЕЗ установленной Ollama. Хэширует слова в [dimension] корзин
 * (bag-of-words) + L2-нормализация: даёт грубое ЛЕКСИЧЕСКОЕ сходство (общие слова → выше косинус), но НЕ
 * семантику. Для настоящего RAG нужна [OllamaEmbedder]; этот — для тестов и офлайн-демо.
 */
class HashingEmbedder(override val dimension: Int = 768) : Embedder {
    override val id: String = "hashing-$dimension"

    private val token = Regex("[\\p{L}\\p{Nd}]+")

    override suspend fun embed(text: String): FloatArray {
        val v = FloatArray(dimension)
        for (m in token.findAll(text.lowercase())) {
            val w = m.value
            val bucket = ((w.hashCode() % dimension) + dimension) % dimension
            v[bucket] += 1f
        }
        var norm = 0.0
        for (x in v) norm += x * x
        norm = kotlin.math.sqrt(norm)
        if (norm > 0) for (i in v.indices) v[i] = (v[i] / norm).toFloat()
        return v
    }
}
