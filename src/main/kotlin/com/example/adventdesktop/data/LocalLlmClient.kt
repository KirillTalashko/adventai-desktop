package com.example.adventdesktop.data

import com.example.adventdesktop.domain.GatewayResponse
import com.example.adventdesktop.domain.LlmGateway
import com.example.adventdesktop.domain.LlmParams
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.TokenUsage
import com.example.adventdesktop.domain.Tool
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
private data class OllamaChatMessage(val role: String, val content: String = "")

/** Параметры генерации Ollama (подмножество). `null`-поля не сериализуются → берётся дефолт модели. */
@Serializable
private data class OllamaOptions(val temperature: Double? = null, val num_predict: Int? = null)

@Serializable
private data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaChatMessage>,
    val stream: Boolean = false,
    val options: OllamaOptions? = null,
)

@Serializable
private data class OllamaChatResponse(
    val message: OllamaChatMessage? = null,
    val prompt_eval_count: Int = 0, // токены промпта (вход)
    val eval_count: Int = 0,        // токены генерации (выход)
    val done: Boolean = false,
)

/**
 * Локальная генеративная LLM через **Ollama** (Неделя 6, День 26) — путь курса «Local LLM». Обычный HTTP-клиент
 * на `localhost:11434/api/chat` (нативный chat-эндпойнт Ollama, `stream=false`). Реализует доменный порт
 * [LlmGateway], поэтому подставляется вместо облачного [LlmClient] без изменений в домене.
 *
 * Почему отдельный клиент, а не [LlmClient] с другим baseUrl: тот всегда ходит через [HttpProxy] (туннель для
 * облака), а к `localhost` прокси нужно ОБХОДИТЬ — как это делает [OllamaEmbedder]. Локальный адрес → без прокси
 * и без проблем DNS. Бесплатно, приватно (данные не покидают машину). Требует запущенной `ollama serve` и
 * `ollama pull <model>` — иначе внятная ошибка.
 *
 * **День 26 (запуск + 3 запроса):** tool-loop НЕ ведём — [tools]/[executeTool] игнорируются (обычный одиночный
 * запрос). MCP-инструменты для локальной модели (Ollama умеет `tools` в `/api/chat`) — отдельный день недели.
 */
class LocalLlmClient(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = DEFAULT_MODEL,
) : LlmGateway {

    // encodeDefaults=true → обязательно шлём `stream:false` (иначе Ollama стримит NDJSON и парсинг ломается).
    // explicitNulls=false → не слать null-поля options (temperature/num_predict).
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; encodeDefaults = true }
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            // Генерация 7B на CPU медленная — даём большой запас; коннект к localhost — быстрый.
            requestTimeoutMillis = 300_000
            connectTimeoutMillis = 5_000
        }
    }

    override suspend fun complete(
        messages: List<Message>,
        tools: List<Tool>,
        params: LlmParams,
        executeTool: (suspend (String, String) -> String)?,
    ): GatewayResponse {
        val wire = messages.map { OllamaChatMessage(it.role.wire, it.text) }
        val options = if (params.temperature != null || params.maxTokens != null) {
            OllamaOptions(temperature = params.temperature, num_predict = params.maxTokens)
        } else null

        val resp: HttpResponse = runCatching {
            http.post("$baseUrl/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(OllamaChatRequest(model, wire, stream = false, options = options))
            }
        }.getOrElse { e ->
            error("Локальная LLM недоступна ($baseUrl): ${e.message}. Запусти `ollama serve` и `ollama pull $model`.")
        }
        if (!resp.status.isSuccess()) {
            error("Ollama ${resp.status}: ${resp.bodyAsText().take(300)}. Проверь, что модель есть: `ollama pull $model`.")
        }

        val body: OllamaChatResponse = resp.body()
        val text = body.message?.content?.trim().orEmpty()
        if (text.isEmpty()) error("Локальная модель $model вернула пустой ответ.")
        val usage = TokenUsage(body.prompt_eval_count, body.eval_count, body.prompt_eval_count + body.eval_count)
        return GatewayResponse(text, usage)
    }

    fun close() = http.close()

    companion object {
        /** Дефолтная локальная модель Дня 26 (выбор пользователя). Переопределяется в CLI/панели. */
        const val DEFAULT_MODEL = "qwen2.5:7b"
    }
}

/** Демо-запрос для проверки локальной LLM (День 26): метка сложности + текст. */
data class LlmSample(val level: String, val prompt: String)

/** 3 контрольных запроса РАЗНОЙ сложности — общие для CLI `runLocalLlm` и dev-панели (DRY). */
val LOCAL_LLM_SAMPLES: List<LlmSample> = listOf(
    LlmSample(
        "простой факт",
        "В каком городе находится штаб-квартира Европейского союза? Ответь одним предложением.",
    ),
    LlmSample(
        "рассуждение",
        "Гражданин РФ едет в Испанию на 10 дней в июле с двумя детьми. Назови 5 ключевых документов " +
            "на шенгенскую туристическую визу.",
    ),
    LlmSample(
        "структурный вывод (JSON)",
        "Верни только JSON с полями country, visa_type и documents (массив строк) для туристической " +
            "поездки в Японию. Без пояснений вокруг JSON.",
    ),
)
