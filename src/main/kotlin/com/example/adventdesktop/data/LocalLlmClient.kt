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
import io.ktor.client.request.get
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
private data class OllamaOptions(
    val temperature: Double? = null,
    val num_predict: Int? = null,   // max tokens (выход)
    val num_ctx: Int? = null,       // context window (День 29)
    val top_p: Double? = null,
    val repeat_penalty: Double? = null,
    val seed: Int? = null,
)

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
    val total_duration: Long = 0,   // нс — общее время (День 29)
    val eval_duration: Long = 0,    // нс — время генерации → throughput ток/с
    val done: Boolean = false,
)

@Serializable
private data class OllamaTag(val name: String = "")

@Serializable
private data class OllamaTagsResponse(val models: List<OllamaTag> = emptyList())

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
) : LlmGatewayClient {

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
        val body = postChat(model, wire, options)
        val text = stripThink(body.message?.content)
        if (text.isEmpty()) error("Локальная модель $model вернула пустой ответ.")
        val usage = TokenUsage(body.prompt_eval_count, body.eval_count, body.prompt_eval_count + body.eval_count)
        return GatewayResponse(text, usage)
    }

    /**
     * День 29 — прогон под тюнинг с богатыми метриками (throughput ток/с из `eval_duration`, wall-clock).
     * Модель (квант), system-промпт и все ручки (temperature/num_predict/num_ctx/top_p/…) берутся из [tuning] —
     * для A/B «до vs после оптимизации» и сравнения квантов.
     */
    suspend fun runTuned(user: String, tuning: LocalTuning): LocalRun {
        val wire = listOf(OllamaChatMessage("system", tuning.system), OllamaChatMessage("user", user))
        val options = OllamaOptions(
            temperature = tuning.temperature, num_predict = tuning.numPredict, num_ctx = tuning.numCtx,
            top_p = tuning.topP, repeat_penalty = tuning.repeatPenalty, seed = tuning.seed,
        )
        val start = System.currentTimeMillis()
        val body = postChat(tuning.model, wire, options)
        val ms = System.currentTimeMillis() - start
        val tokPerSec = if (body.eval_duration > 0) body.eval_count * 1_000_000_000.0 / body.eval_duration else 0.0
        return LocalRun(
            text = stripThink(body.message?.content).ifEmpty { "(пустой ответ)" },
            promptTokens = body.prompt_eval_count, evalTokens = body.eval_count,
            totalTokens = body.prompt_eval_count + body.eval_count, tokPerSec = tokPerSec, ms = ms,
        )
    }

    /** Один POST `/api/chat` (stream=false) с обработкой ошибок — общий для [complete] и [runTuned] (DRY). */
    private suspend fun postChat(model: String, wire: List<OllamaChatMessage>, options: OllamaOptions?): OllamaChatResponse {
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
        return resp.body()
    }

    // «Думающие» модели (Qwen3 и т.п.) оборачивают рассуждение в <think>…</think> — вырезаем, оставляем ответ.
    private fun stripThink(content: String?): String = content.orEmpty().replace(THINK_REGEX, "").trim()

    override fun close() = http.close()

    companion object {
        /** Дефолтная локальная модель Дня 26 (выбор пользователя). Переопределяется в CLI/панели. */
        const val DEFAULT_MODEL = "qwen2.5:7b"

        /** Блок рассуждений «думающих» моделей (Qwen3 и т.п.) — вырезаем из ответа. */
        private val THINK_REGEX = Regex("(?s)<think>.*?</think>")
    }
}

/**
 * Список установленных в Ollama моделей (`GET /api/tags`), без эмбеддеров — для выпадашки выбора в dev-панели.
 * При недоступности Ollama — пустой список (панель откатится на ручной ввод модели).
 */
suspend fun fetchOllamaModels(baseUrl: String = "http://localhost:11434"): List<String> {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 8_000; connectTimeoutMillis = 3_000 }
    }
    return try {
        val resp: OllamaTagsResponse = http.get("$baseUrl/api/tags").body()
        resp.models.map { it.name }.filterNot { it.contains("embed", ignoreCase = true) }.sorted()
    } catch (e: Exception) {
        emptyList()
    } finally {
        http.close()
    }
}

/**
 * Системный промпт для локальной модели. `qwen2.5` — китайская модель и на длинных ответах склонна «съезжать»
 * на китайский (code-switching); жёстко пиним русский. Общий для CLI и dev-панели (DRY).
 */
const val LOCAL_LLM_SYSTEM =
    "Ты — визовый ассистент. Отвечай только на русском языке и не переходи на другие языки в течение всего " +
        "ответа (особенно не используй китайский). Пиши понятно и по делу."

/**
 * День 29 — продуктовый промпт-шаблон под конкретный кейс (чек-лист документов на визу). ЖЁСТКИЙ ПИН РУССКОГО
 * (правило языка первым И последним + русские эквиваленты банковских терминов) — иначе `qwen2.5` на длинной
 * генерации съезжает на китайский (реально ловили: пункт про финансы приходил иероглифами). Плюс роль +
 * грунтовка против галлюцинаций + строгий формат с образцом. Тюнинговый («после») промпт для A/B против [LOCAL_LLM_SYSTEM].
 */
val OPT_TASK_PROMPT = """
    ЯЗЫК — ГЛАВНОЕ ПРАВИЛО: каждое слово пиши ТОЛЬКО по-русски (кириллицей). Ни одного иероглифа и иностранного слова — ни в названиях, ни в уточнениях, ни в скобках. Банковские/финансовые термины — по-русски: «справка из банка», «выписка со счёта за 3 месяца», «справка о доходах».

    Ты — практикующий визовый консультант. Задача — по описанию поездки собрать точный чек-лист документов на визу.

    Правила:
    • Только документы, реально нужные для указанной страны и типа визы. Не выдумывай экзотику, сроки и суммы, если не уверен.
    • Если страна/цель не указаны — базовый пакет + пометка, что часть требований зависит от страны и консульства.
    • Без гарантий одобрения; финальные требования — на официальном сайте консульства.

    Формат — строго:
    • Нумерованный список 4–6 пунктов (важные — первыми). Без markdown, эмодзи и заголовков.
    • Строка = «N. Документ — уточнение в 3–7 словах».
    • Без вступления и заключения. Начни сразу с «1.».

    Образец (структуру, не текст):
    1. Загранпаспорт — действителен ещё 3+ месяца после поездки.
    2. Справка из банка — выписка со счёта за последние 3 месяца.

    Ещё раз: ВЕСЬ ответ — только на русском, кириллицей, без единого иероглифа.
""".trimIndent()

/**
 * Тюнинг локальной генерации (День 29): модель (квант), system-промпт и ручки Ollama options.
 * `null`-поля → дефолт модели. Для A/B «до vs после оптимизации».
 */
data class LocalTuning(
    val model: String = LocalLlmClient.DEFAULT_MODEL,
    val system: String = LOCAL_LLM_SYSTEM,
    val temperature: Double? = null,
    val numPredict: Int? = null,     // max tokens (выход)
    val numCtx: Int? = null,         // context window
    val topP: Double? = null,
    val repeatPenalty: Double? = null,
    val seed: Int? = null,
)

/** Результат прогона с метриками (День 29): текст, токены, throughput (ток/с), wall-clock (мс). */
data class LocalRun(
    val text: String,
    val promptTokens: Int,
    val evalTokens: Int,
    val totalTokens: Int,
    val tokPerSec: Double,
    val ms: Long,
)

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
        "Гражданин РФ едет в Испанию на 10 дней в июле с двумя детьми. Перечисли ровно 5 ключевых документов " +
            "на шенгенскую туристическую визу — коротким нумерованным списком, по одному пункту в строке, без пояснений.",
    ),
    LlmSample(
        "структурный вывод (JSON)",
        "Верни только JSON с полями country, visa_type и documents (массив строк) для туристической " +
            "поездки в Японию. Без пояснений вокруг JSON.",
    ),
)
