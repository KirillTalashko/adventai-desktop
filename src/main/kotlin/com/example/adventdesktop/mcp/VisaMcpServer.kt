package com.example.adventdesktop.mcp

import com.example.adventdesktop.data.LlmClient
import com.example.adventdesktop.data.LlmConfig
import com.example.adventdesktop.data.appHomeDir
import com.example.adventdesktop.mcp.scheduler.DigestCountry
import com.example.adventdesktop.mcp.scheduler.DigestScheduler
import com.example.adventdesktop.mcp.scheduler.DigestStore
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
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
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

fun main(): Unit = runBlocking {
    val httpClient = createHttpClient()
    val deepseekKey = System.getenv("DEEPSEEK_API_KEY")?.trim().orEmpty()
    val llm: LlmClient? = if (deepseekKey.isNotBlank()) {
        LlmClient(LlmConfig("https://api.deepseek.com/chat/completions", deepseekKey, "deepseek-chat"))
    } else null
    val tavilyKey = System.getenv("TAVILY_API_KEY")?.trim()?.ifBlank { null }
    val researchAgent = llm?.let { VisaResearchAgent(it, httpClient, tavilyKey) }

    // --- День 18: планировщик дайджеста (SQLite + фоновый периодический сбор) ---
    val dbPath = System.getenv("DIGEST_DB")?.trim()?.ifBlank { null }
        ?: File(File(appHomeDir(), "scheduler"), "visa-digest.db").absolutePath
    val intervalMin = System.getenv("DIGEST_INTERVAL_MINUTES")?.trim()?.toLongOrNull() ?: 360L
    val store = DigestStore(dbPath)
    // Сбор сводки по одной стране — тем же внутренним research-агентом, что и get_visa_requirements.
    val collect: suspend (DigestCountry) -> Pair<String, String>? = collect@{ c ->
        val agent = researchAgent ?: return@collect null
        val enName = toEnglish(c.destination)
        val portal = officialPortal(enName) ?: "(уточните официальный визовый портал страны)"
        val summary = runCatching {
            agent.research(c.destination, enName, c.citizenship, c.purpose, portal, LocalDate.now().toString())
        }.getOrNull()
        summary?.let { it to "" }
    }
    val scheduler = DigestScheduler(store, intervalMin, collect)
    val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    if (researchAgent != null) scheduler.start(schedulerScope)

    // Диагностика (в stderr — не мешает stdio-протоколу): ключи + где БД дайджеста и интервал.
    System.err.println(
        "visa-mcp ready: deepseek=${llm != null}, tavily=${tavilyKey != null}, " +
            "digestDb=$dbPath, intervalMin=$intervalMin",
    )

    // Сборка свежего Server со всеми тулзами — вызывается один раз для stdio и на каждую SSE-сессию.
    fun buildServer(): Server {
    val server = Server(
        Implementation(name = "visa-mcp", version = "0.4.0"),
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

    // Тул 3 — подписать страну на периодический дайджест (+ сразу первый сбор).
    server.addTool(
        name = "add_digest_country",
        description = "Подписать страну на ПЕРИОДИЧЕСКИЙ визовый дайджест: планировщик будет регулярно собирать " +
            "свежую сводку и копить историю снимков. Делает первый сбор сразу. " +
            "Вход: destination (страна), citizenship (по умолч. «Россия»), purpose (по умолч. «туризм»).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("destination") { put("type", "string"); put("description", "Страна, напр. «Германия»") }
                putJsonObject("citizenship") { put("type", "string"); put("description", "Гражданство, по умолч. «Россия»") }
                putJsonObject("purpose") { put("type", "string"); put("description", "Цель поездки, по умолч. «туризм»") }
            },
            required = listOf("destination"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = true),
    ) { request ->
        val args = request.arguments
        val destination = args?.get("destination")?.jsonPrimitive?.content?.trim().orEmpty()
        val citizenship = args?.get("citizenship")?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "Россия" }
        val purpose = args?.get("purpose")?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "туризм" }
        if (destination.isBlank()) {
            return@addTool CallToolResult(content = listOf(TextContent("Параметр «destination» обязателен.")))
        }
        val country = store.addCountry(destination, citizenship, purpose)
        runCatching { scheduler.collectOne(country) }   // первый снимок сразу (если есть LLM-агент)
        val count = store.snapshotCount(country.id)
        CallToolResult(content = listOf(TextContent(
            "Подписка добавлена: $destination ($citizenship, $purpose). Снимков в истории: $count. " +
                "Планировщик будет обновлять сводку раз в $intervalMin мин. Сводку смотри в get_visa_digest.",
        )))
    }

    // Тул 4 — список подписок дайджеста.
    server.addTool(
        name = "list_digest_countries",
        description = "Список стран, подписанных на периодический визовый дайджест (с числом собранных снимков).",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { _ ->
        val countries = store.listCountries()
        val text = if (countries.isEmpty()) {
            "Подписок нет. Добавьте страну через add_digest_country."
        } else {
            countries.joinToString("\n") { c ->
                val last = store.latestSnapshot(c.id)?.collectedAt ?: "ещё не собирали"
                "• ${c.destination} (${c.citizenship}, ${c.purpose}) — снимков: ${store.snapshotCount(c.id)}, последний: $last"
            }
        }
        CallToolResult(content = listOf(TextContent(text)))
    }

    // Тул 5 — отписать страну от дайджеста.
    server.addTool(
        name = "remove_digest_country",
        description = "Отписать страну от периодического дайджеста (удаляет подписку и её снимки). Вход: destination.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("destination") { put("type", "string"); put("description", "Страна, напр. «Германия»") }
            },
            required = listOf("destination"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = false, openWorldHint = false),
    ) { request ->
        val destination = request.arguments?.get("destination")?.jsonPrimitive?.content?.trim().orEmpty()
        if (destination.isBlank()) {
            return@addTool CallToolResult(content = listOf(TextContent("Параметр «destination» обязателен.")))
        }
        val removed = store.removeCountry(destination)
        CallToolResult(content = listOf(TextContent(
            if (removed > 0) "Отписано: $destination." else "Подписка на «$destination» не найдена.",
        )))
    }

    // Тул 6 — АГРЕГИРОВАННЫЙ дайджест: по каждой стране свежайшая сводка из накопленных снимков.
    server.addTool(
        name = "get_visa_digest",
        description = "Агрегированный визовый дайджест: по каждой подписанной стране — последняя собранная " +
            "планировщиком сводка (требования/сборы/сроки/источники), когда собрана и сколько снимков в истории. " +
            "Это результат фоновой работы агента 24/7.",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { _ ->
        val countries = store.listCountries()
        val text = if (countries.isEmpty()) {
            "Подписок нет. Добавьте страну через add_digest_country, и планировщик начнёт собирать дайджест."
        } else buildString {
            appendLine("📋 Визовый дайджест (сформирован: ${LocalDate.now()})")
            for (c in countries) {
                val snap = store.latestSnapshot(c.id)
                appendLine()
                appendLine("=== ${c.destination} (${c.citizenship}, ${c.purpose}) — снимков: ${store.snapshotCount(c.id)} ===")
                if (snap == null) {
                    appendLine("Сводка ещё не собрана (ожидается ближайший прогон планировщика).")
                } else {
                    appendLine("Собрано: ${snap.collectedAt}")
                    appendLine(snap.summary.take(1400).let { if (it.length < snap.summary.length) "$it\n…(сокращено)" else it })
                }
            }
        }
        CallToolResult(content = listOf(TextContent(text)))
    }

        return server
    }

    // --- Транспорт: stdio (локально, подпроцесс приложения) или SSE (remote — для VPS за Caddy) ---
    val transportMode = System.getenv("MCP_TRANSPORT")?.trim()?.lowercase()?.ifBlank { null } ?: "stdio"
    if (transportMode == "sse") {
        val port = System.getenv("MCP_PORT")?.trim()?.toIntOrNull() ?: 3001
        val token = System.getenv("MCP_AUTH_TOKEN")?.trim()?.ifBlank { null }
        System.err.println("visa-mcp transport=sse host=0.0.0.0 port=$port auth=${token != null}")
        embeddedServer(ServerCIO, host = "0.0.0.0", port = port) {
            install(SSE)
            install(Authentication) {
                bearer("mcp") {
                    authenticate { cred -> if (token != null && cred.token == token) UserIdPrincipal("client") else null }
                }
            }
            routing {
                get("/health") { call.respondText("ok") }   // без авторизации — для проверки/Caddy
                if (token != null) authenticate("mcp") { mcp { buildServer() } } else mcp { buildServer() }
            }
        }.start(wait = true)
    } else {
        val server = buildServer()
        val transport = StdioServerTransport(System.`in`.asInput(), System.out.asSink().buffered())
        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        done.join()
        schedulerScope.cancel()
        store.close()
    }
}
