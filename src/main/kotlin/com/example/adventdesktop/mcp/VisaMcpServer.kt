package com.example.adventdesktop.mcp

import com.example.adventdesktop.data.LlmClient
import com.example.adventdesktop.data.LlmConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.headers
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate

/**
 * MCP-сервер «Визовый специалист» (День 17) — самодостаточный умный сервис.
 *
 * Инструмент `get_visa_requirements(destination, citizenship, purpose)`:
 *  1) тянет ЖИВЬЁМ авторитетную сводку визовой политики страны (Wikipedia REST — реально доступен);
 *  2) подставляет ссылку на ОФИЦИАЛЬНЫЙ портал подачи из реестра (france-visas.gouv.fr и т.п. —
 *     их не скрейпим, они под Cloudflare/CAPTCHA, но цитируем как «куда подавать»);
 *  3) внутренним LLM-агентом (DeepSeek) собирает структуру с датой актуальности и дисклеймером.
 *
 * Транспорт — stdio: в stdout идёт только JSON-RPC (логи заглушены slf4j-nop). Ключ DeepSeek берём из
 * env `DEEPSEEK_API_KEY` (наследуется от приложения; McpClient дублирует его явно).
 */

// --- DTO Wikipedia REST summary ---
@Serializable private data class WikiSummary(
    val type: String? = null,
    val title: String? = null,
    val extract: String? = null,
    val content_urls: WikiUrls? = null,
)

@Serializable private data class WikiUrls(val desktop: WikiDesktop? = null)
@Serializable private data class WikiDesktop(val page: String? = null)

/** Русское имя → английское (для заголовка Wikipedia «Visa policy of …» и реестра порталов). */
private val RU_TO_EN = mapOf(
    "испания" to "Spain", "германия" to "Germany", "франция" to "France", "италия" to "Italy",
    "сша" to "the United States", "америка" to "the United States",
    "великобритания" to "the United Kingdom", "англия" to "the United Kingdom",
    "канада" to "Canada", "япония" to "Japan", "турция" to "Turkey",
    "оаэ" to "the United Arab Emirates", "эмираты" to "the United Arab Emirates",
    "таиланд" to "Thailand", "греция" to "Greece", "нидерланды" to "the Netherlands",
    "португалия" to "Portugal", "чехия" to "the Czech Republic",
)

/** Английское имя (lowercase) → официальный портал подачи (цитируем, не скрейпим). */
private val OFFICIAL_PORTAL = mapOf(
    "spain" to "https://www.exteriores.gob.es/en/ServiciosAlCiudadano/Paginas/Visados.aspx",
    "france" to "https://france-visas.gouv.fr/",
    "germany" to "https://www.auswaertiges-amt.de/en/visa-service",
    "italy" to "https://vistoperitalia.esteri.it/",
    "the united states" to "https://travel.state.gov/content/travel/en/us-visas.html",
    "the united kingdom" to "https://www.gov.uk/browse/visas-immigration",
    "canada" to "https://www.canada.ca/en/immigration-refugees-citizenship/services/visit-canada.html",
    "japan" to "https://www.mofa.go.jp/j_info/visit/visa/",
    "turkey" to "https://www.evisa.gov.tr/",
    "the united arab emirates" to "https://www.icp.gov.ae/en/",
    "thailand" to "https://www.thaievisa.go.th/",
    "greece" to "https://www.mfa.gr/en/visas/",
    "the netherlands" to "https://www.netherlandsworldwide.nl/visa-the-netherlands",
    "portugal" to "https://vistos.mne.gov.pt/en/",
    "the czech republic" to "https://www.mzv.gov.cz/jnp/en/information_for_aliens/index.html",
)

/** Гражданство → английское прилагательное (для поискового запроса). */
private val CITIZENSHIP_ADJ = mapOf(
    "россия" to "Russian", "российская федерация" to "Russian", "рф" to "Russian",
    "беларусь" to "Belarusian", "казахстан" to "Kazakhstani", "украина" to "Ukrainian",
    "армения" to "Armenian", "грузия" to "Georgian", "узбекистан" to "Uzbekistani",
)
internal fun citizenshipAdj(c: String) = CITIZENSHIP_ADJ[c.trim().lowercase()] ?: c.trim()

/** Цель поездки → английский термин (для запроса). */
private val PURPOSE_EN = mapOf(
    "туризм" to "tourist", "отдых" to "tourist", "работа" to "work",
    "учеба" to "study", "учёба" to "study", "бизнес" to "business", "деловая" to "business",
)
internal fun purposeEn(p: String) = PURPOSE_EN[p.trim().lowercase()] ?: p.trim()

private fun toEnglish(input: String): String {
    val key = input.trim().lowercase()
    RU_TO_EN[key]?.let { return it }
    return input.trim().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}

private fun officialPortal(enName: String): String? = OFFICIAL_PORTAL[enName.lowercase()]

private fun createHttpClient(): HttpClient = HttpClient(CIO) {
    defaultRequest {
        headers { append("User-Agent", "AdventAI-VisaMCP/0.2 (AI Advent Challenge; local)") }
    }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

/** Живой запрос к Wikipedia REST → (текст сводки, ссылка) или null, если страницы нет. */
private suspend fun HttpClient.fetchPolicyText(enName: String): Pair<String, String>? {
    val title = "Visa policy of $enName".replace(" ", "_")
    val resp: HttpResponse = get("https://en.wikipedia.org/api/rest_v1/page/summary/$title")
    if (!resp.status.isSuccess()) return null
    val s: WikiSummary = resp.body()
    if (s.type == "disambiguation" || s.extract.isNullOrBlank()) return null
    val link = s.content_urls?.desktop?.page ?: "https://en.wikipedia.org/wiki/$title"
    return s.extract to link
}

fun main() = runBlocking {
    val httpClient = createHttpClient()
    val deepseekKey = System.getenv("DEEPSEEK_API_KEY")?.trim().orEmpty()
    val llm: LlmClient? = if (deepseekKey.isNotBlank()) {
        LlmClient(LlmConfig("https://api.deepseek.com/chat/completions", deepseekKey, "deepseek-chat"))
    } else null
    val tavilyKey = System.getenv("TAVILY_API_KEY")?.trim()?.ifBlank { null }
    val researchAgent = llm?.let { VisaResearchAgent(it, httpClient, tavilyKey) }
    // Диагностика (в stderr — не мешает stdio-протоколу): видны ли серверу ключи.
    System.err.println("visa-mcp ready: deepseek=${llm != null}, tavily=${tavilyKey != null}")

    val server = Server(
        Implementation(name = "visa-mcp", version = "0.3.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
    )

    // Тул 1 — проверка связи.
    server.addTool(
        name = "ping",
        description = "Проверка связи: возвращает «pong».",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { _ ->
        CallToolResult(content = listOf(TextContent("pong")))
    }

    // Тул 2 — умный визовый сервис вокруг реального источника + LLM-агент внутри MCP.
    server.addTool(
        name = "get_visa_requirements",
        description = "Актуальная визовая сводка для связки «гражданство → страна назначения». " +
            "Ищет официальные источники в вебе (поиск), даёт ссылку на офиц. портал подачи и " +
            "структурирует ответ внутренним агентом с датой актуальности и цитатами. " +
            "Вход: destination (страна), citizenship (гражданство, по умолч. «Россия»), purpose (цель, по умолч. «туризм»).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("destination") { put("type", "string"); put("description", "Страна назначения, напр. «Испания»") }
                putJsonObject("citizenship") { put("type", "string"); put("description", "Гражданство заявителя, напр. «Россия»") }
                putJsonObject("purpose") { put("type", "string"); put("description", "Цель поездки, напр. «туризм»") }
            },
            required = listOf("destination"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    ) { request ->
        val args = request.arguments
        val destination = args?.get("destination")?.jsonPrimitive?.content?.trim().orEmpty()
        val citizenship = args?.get("citizenship")?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "Россия" }
        val purpose = args?.get("purpose")?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "туризм" }
        if (destination.isBlank()) {
            return@addTool CallToolResult(content = listOf(TextContent("Параметр «destination» обязателен.")))
        }

        val enName = toEnglish(destination)
        val today = LocalDate.now().toString()
        val portal = officialPortal(enName) ?: "(уточните официальный визовый портал страны)"

        // Полный цикл (поиск → парсинг → самокритика → дозапросы → синтез) — только с LLM.
        if (researchAgent != null) {
            val researched = runCatching {
                researchAgent.research(destination, enName, citizenship, purpose, portal, today)
            }.getOrNull()
            if (researched != null) {
                return@addTool CallToolResult(content = listOf(TextContent(researched)))
            }
        }

        // Фоллбэк (без ключа LLM или если исследование пусто): один поиск → сырой вывод; иначе Wikipedia.
        val query = "$enName visa requirements for ${citizenshipAdj(citizenship)} citizens ${purposeEn(purpose)} official site"
        val used = runCatching { httpClient.searchVisa(query, tavilyKey) }.getOrDefault(emptyList()).take(5)
        val grounding: String = if (used.isNotEmpty()) {
            used.joinToString("\n\n") {
                "• ${it.title}${if (it.official) " [офиц.]" else ""}\n  ${it.snippet}\n  URL: ${it.url}"
            }
        } else {
            val wiki = runCatching { httpClient.fetchPolicyText(enName) }.getOrNull()
                ?: return@addTool CallToolResult(content = listOf(TextContent(
                    "Не удалось найти источники по «$destination» на сегодня. Официальный портал: $portal\n" +
                        "⚠️ Проверьте требования на официальном сайте."
                )))
            "Визовая политика ($enName) [Wikipedia, аварийный фоллбэк]: ${wiki.first}\n  URL: ${wiki.second}"
        }
        CallToolResult(content = listOf(TextContent(
            buildString {
                appendLine("Найденные источники по визе ($enName, для «$citizenship»):")
                appendLine(grounding)
                appendLine()
                appendLine("Куда подавать (офиц. портал): $portal")
                appendLine("По состоянию на: $today")
                append("⚠️ Правила меняются — проверьте на официальном сайте перед подачей.")
            }
        )))
    }

    val transport = StdioServerTransport(System.`in`.asInput(), System.out.asSink().buffered())
    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}
