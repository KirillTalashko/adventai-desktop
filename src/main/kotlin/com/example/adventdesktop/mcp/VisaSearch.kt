package com.example.adventdesktop.mcp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Один результат поиска. [official] = домен похож на официальный/правительственный; [raw] = полный текст, если есть. */
data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val official: Boolean,
    val raw: String? = null,
)

/** Подстроки доменов, которые считаем «официальными/авторитетными» для виз. */
private val OFFICIAL_HINTS = listOf(
    ".gov", ".gouv", "gov.uk", "go.jp", "canada.ca", "europa.eu", "admin.ch",
    "mfa", "esteri", "auswaertiges-amt", "exteriores", "embassy", "consul",
    "evisa", "state.gov", "vfsglobal", "tlscontact", "schengenvisainfo",
)

private fun isOfficial(url: String): Boolean {
    val host = runCatching { URI(url).host ?: "" }.getOrDefault("").lowercase()
    return OFFICIAL_HINTS.any { host.contains(it) }
}

private fun stripHtml(s: String): String =
    s.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()

private val RESULT_A = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
private val RESULT_SNIPPET = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
private val UDDG = Regex("""uddg=([^&]+)""")

/** DDG отдаёт ссылки через редирект //duckduckgo.com/l/?uddg=<реальный URL> — раскручиваем. */
private fun realUrl(href: String): String {
    UDDG.find(href)?.let { m ->
        return runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrDefault(href)
    }
    return if (href.startsWith("//")) "https:$href" else href
}

/**
 * Поиск по DuckDuckGo без ключа (парсинг HTML-выдачи `html.duckduckgo.com`).
 * Официальные домены идут первыми. Бросает/возвращает пусто при блокировке — вызывающий уходит в фоллбэк.
 */
suspend fun HttpClient.duckSearch(query: String, limit: Int = 6): List<SearchHit> {
    val q = URLEncoder.encode(query, "UTF-8")
    val html = get("https://html.duckduckgo.com/html/?q=$q") {
        header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        )
    }.bodyAsText()

    val snippets = RESULT_SNIPPET.findAll(html).map { stripHtml(it.groupValues[1]) }.toList()
    val hits = RESULT_A.findAll(html).toList().mapIndexed { i, m ->
        val url = realUrl(m.groupValues[1])
        SearchHit(
            title = stripHtml(m.groupValues[2]),
            url = url,
            snippet = snippets.getOrElse(i) { "" },
            official = isOfficial(url),
        )
    }.filter { it.url.startsWith("http") }

    // официальные — вперёд, затем остальные.
    return (hits.filter { it.official } + hits.filterNot { it.official }).take(limit)
}

private val SCRIPT_STYLE = Regex(
    "<(script|style|noscript)[^>]*>.*?</\\1>",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)

/**
 * Дотянуть читаемый текст страницы (HTML → текст). Возвращает null, если страница недоступна,
 * пуста, под Cloudflare/CAPTCHA или похожа на JS-оболочку — тогда вызывающий просто её пропустит.
 */
suspend fun HttpClient.fetchReadable(url: String, maxChars: Int = 4000): String? {
    val html = runCatching {
        get(url) {
            header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            )
        }.bodyAsText()
    }.getOrNull() ?: return null

    val text = stripHtml(SCRIPT_STYLE.replace(html, " "))
    if (text.length < 200) return null
    val lower = text.lowercase()
    if (lower.contains("captcha") || (lower.contains("cloudflare") && text.length < 1500)) return null
    return text.take(maxChars)
}

// ----- Tavily (основной поиск, если задан ключ): отдаёт чистый контент, без скрейпинга/CAPTCHA -----

@Serializable
private data class TavilyReq(
    val query: String,
    val search_depth: String = "advanced",
    val max_results: Int = 6,
    val include_raw_content: Boolean = true,
)

@Serializable
private data class TavilyResult(
    val title: String = "",
    val url: String = "",
    val content: String = "",
    val raw_content: String? = null,
)

@Serializable
private data class TavilyResp(val results: List<TavilyResult> = emptyList())

/** Поиск через Tavily (RAG-поиск). Официальные домены — вперёд; [raw] заполняется готовым контентом. */
suspend fun HttpClient.tavilySearch(apiKey: String, query: String, limit: Int = 6): List<SearchHit> {
    val resp: TavilyResp = post("https://api.tavily.com/search") {
        header("Authorization", "Bearer $apiKey")
        contentType(ContentType.Application.Json)
        setBody(TavilyReq(query = query, max_results = limit))
    }.body()
    val hits = resp.results
        .filter { it.url.startsWith("http") }
        .map {
            SearchHit(
                title = it.title,
                url = it.url,
                snippet = it.content.take(300),
                official = isOfficial(it.url),
                raw = (it.raw_content ?: it.content).takeIf { t -> t.isNotBlank() }?.take(4000),
            )
        }
    return hits.filter { it.official } + hits.filterNot { it.official }
}

/** Единая точка поиска: Tavily (если есть ключ; при пустом ответе — DDG), иначе DuckDuckGo. */
suspend fun HttpClient.searchVisa(query: String, tavilyKey: String?, limit: Int = 6): List<SearchHit> {
    if (!tavilyKey.isNullOrBlank()) {
        val t = runCatching { tavilySearch(tavilyKey, query, limit) }.getOrDefault(emptyList())
        if (t.isNotEmpty()) return t
    }
    return runCatching { duckSearch(query, limit) }.getOrDefault(emptyList())
}

// ----- Аварийный источник: Wikipedia REST (как SearchHit, official=false) -----

@Serializable private data class WikiSum(
    val type: String? = null,
    val title: String? = null,
    val extract: String? = null,
    val content_urls: WikiCU? = null,
)

@Serializable private data class WikiCU(val desktop: WikiDesk? = null)
@Serializable private data class WikiDesk(val page: String? = null)

/** Сводка визовой политики страны из Wikipedia REST — последний фоллбэк, когда поиск пуст. */
suspend fun HttpClient.wikipediaPolicy(enName: String): SearchHit? {
    val title = "Visa policy of $enName".replace(" ", "_")
    val resp = get("https://en.wikipedia.org/api/rest_v1/page/summary/$title") {
        header("User-Agent", "AdventAI-VisaMCP/0.3 (AI Advent Challenge; local)")
    }
    if (!resp.status.isSuccess()) return null
    val s: WikiSum = resp.body()
    if (s.type == "disambiguation" || s.extract.isNullOrBlank()) return null
    val link = s.content_urls?.desktop?.page ?: "https://en.wikipedia.org/wiki/$title"
    return SearchHit(
        title = s.title ?: "Visa policy of $enName",
        url = link,
        snippet = s.extract.take(300),
        official = false,
        raw = s.extract,
    )
}
