package com.example.adventdesktop.mcp

import com.example.adventdesktop.data.LlmClient
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val MAX_ROUNDS = 3
private const val QUERIES_PER_ROUND = 3
private const val PAGES_PER_ROUND = 2
private const val PAGE_CHARS = 3000

/** Решение агента-критика: достаточно ли данных и какие новые запросы попробовать. */
@Serializable
private data class Decision(
    val done: Boolean = false,
    val missing: List<String> = emptyList(),
    val next_queries: List<String> = emptyList(),
)

/** Одно собранное доказательство (результат поиска + опционально полный текст страницы). */
private data class Evidence(
    val url: String,
    val title: String,
    val snippet: String,
    val official: Boolean,
    val fullText: String? = null,
)

private val CONTROLLER_PROMPT = """
Ты — придирчивый исследователь-критик внутри визового сервиса. Цель — собрать ПОЛНЫЕ и ОФИЦИАЛЬНЫЕ
данные по визе для заданной связки на указанную дату.

Тебе дают вопрос и СПИСОК уже собранных источников (заголовок | URL | сниппет; [офиц.] = офиц. домен).

Критически оцени, что подтверждено ОФИЦИАЛЬНЫМ источником, а что нет. Задай себе неудобные вопросы:
точно ли это для ЭТОГО гражданства? актуально на сегодня? есть ли изменения этого года? где офиц.
список документов, сборы, сроки и КУДА подавать (консульство/визовый центр)?

Предложи до 3 НОВЫХ англоязычных поисковых запросов с упором на официальные/gov/vfsglobal источники,
которые закроют пробелы (другие формулировки; не повторяй уже выданные запросы).

Ответь СТРОГО в JSON, без пояснений и текста вокруг:
{"done": false, "missing": ["чего не хватает"], "next_queries": ["query 1", "query 2"]}
Если в источниках НЕТ консульского сбора (стоимость) ИЛИ срока рассмотрения — это ПРОБЕЛ: ставь done=false
и ОБЯЗАТЕЛЬНО добавь ЦЕЛЕВОЙ запрос именно про них, напр. "<country> visa fee cost processing time for
<citizenship> citizens official site".
done=true только если ВСЁ ключевое (нужна ли виза, куда подавать, документы, СБОР, СРОКИ) подтверждено
официально ИЛИ это последний доступный раунд и данных явно больше не найти.
""".trim()

private val SYNTH_PROMPT = """
Ты — визовый аналитик внутри сервиса «Визовый специалист». Тебе дают запрос (гражданство → страна,
цель, дата) и СОБРАННЫЕ СЕГОДНЯ источники (заголовок, URL, сниппет и, где есть, текст страницы).

Собери краткую визовую сводку, используя ТОЛЬКО эти источники.

ССЫЛКИ (критично — это главная ценность ответа):
- Каждый факт подкрепляй ССЫЛКОЙ: копируй ПОЛНЫЙ URL из строки «URL:» ДОСЛОВНО, вместе с «https://».
  НИКОГДА не сокращай до голого домена — НЕ «blsspainrussia.ru/...», а «https://blsspainrussia.ru/...».
- ПРИОРИТЕТ — источники с пометкой [офиц.] (официальные: МИД/консульства/посольства, визовые центры
  vfsglobal/bls). Сторонние агрегаторы (блоги, *insurance*, evisa-card, embassyinformation и подобные)
  используй ТОЛЬКО когда по этому факту НЕТ официального источника, и помечай «(неофициальный источник)».
- Не выдумывай ни факты, ни URL вне источников.

Правила:
1. Статус визы (нужна / безвиз N дней / eVisa / Шенген) — из источников, с полной ссылкой.
2. Куда подавать — официальный портал/визовый центр из источников (полной ссылкой).
3. Документы/сборы/сроки — из источников; чего нет, помечай «(не нашли в источниках — уточните на офиц. сайте)».
   Не выдавай конкретные суммы/сроки как факт, если их нет в источниках.
4. Отвечай по-русски, кратко, по структуре ниже. Укажи дату актуальности и дисклеймер.

Структура (каждый пункт — с ПОЛНОЙ https://-ссылкой в конце):
• Нужна ли виза: …  — https://…
• Куда подавать: …  — https://…
• Документы: …  — https://…
• Сборы и сроки: …  — https://…
• Пробелы (уточнить на офиц. сайте): …
• По состоянию на: <дата>
• Источники: <полные https:// URL через запятую>
• ⚠️ Правила меняются — проверьте на официальном сайте перед подачей.
""".trim()

/**
 * Агент-исследователь внутри MCP: полный цикл «рассуждение → поиск → парсинг → самокритика → синтез».
 * Возвращает готовую сводку или null, если не нашёл ни одного источника (тогда вызывающий — в фоллбэк).
 */
class VisaResearchAgent(
    private val llm: LlmClient,
    private val http: HttpClient,
    private val tavilyKey: String?,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun research(
        destination: String,
        enName: String,
        citizenship: String,
        purpose: String,
        portal: String,
        today: String,
    ): String? {
        val question = "гражданин «$citizenship» → «$destination» ($enName), цель «$purpose», дата $today"
        val evidence = LinkedHashMap<String, Evidence>()
        var queries = initialQueries(enName, citizenship, purpose)

        for (round in 0 until MAX_ROUNDS) {
            runRound(queries.take(QUERIES_PER_ROUND), evidence)
            if (evidence.isEmpty()) break
            if (round == MAX_ROUNDS - 1) break
            val decision = critique(question, today, evidence.values.toList())
            if (decision.done) break
            val next = decision.next_queries.map { it.trim() }.filter { it.isNotEmpty() }
            if (next.isEmpty()) break
            queries = next
        }

        // Поиск пуст (Tavily/DDG недоступны) → последний фоллбэк: Wikipedia, но всё равно синтезируем.
        if (evidence.isEmpty()) {
            runCatching { http.wikipediaPolicy(enName) }.getOrNull()?.let {
                evidence[it.url] = Evidence(it.url, it.title, it.snippet, it.official, it.raw)
            }
        }
        if (evidence.isEmpty()) return null
        return synthesize(question, portal, today, evidence.values.toList())
    }

    private fun initialQueries(enName: String, citizenship: String, purpose: String): List<String> {
        val adj = citizenshipAdj(citizenship)
        val p = purposeEn(purpose)
        return listOf(
            "$enName $p visa requirements for $adj citizens official site",
            "$enName visa $adj citizens required documents fee processing time",
            "$enName visa application $adj citizens vfsglobal OR embassy OR consulate 2026",
        )
    }

    /** Один раунд: поиск по запросам + дотягивание текста топ-официальных страниц (dedup по URL). */
    private suspend fun runRound(queries: List<String>, evidence: MutableMap<String, Evidence>) {
        for (q in queries) {
            val hits = runCatching { http.searchVisa(q, tavilyKey) }.getOrDefault(emptyList())
            for (h in hits) evidence.putIfAbsent(h.url, Evidence(h.url, h.title, h.snippet, h.official, h.raw))
        }
        val toFetch = evidence.values.filter { it.official && it.fullText == null }.take(PAGES_PER_ROUND)
        for (e in toFetch) {
            val text = runCatching { http.fetchReadable(e.url, PAGE_CHARS) }.getOrNull()
            if (text != null) evidence[e.url] = e.copy(fullText = text)
        }
    }

    /** Агент-критик: по компактной карте источников решает, чего не хватает и что искать дальше. */
    private suspend fun critique(question: String, today: String, ev: List<Evidence>): Decision {
        val map = ev.joinToString("\n") {
            "- ${if (it.official) "[офиц.] " else ""}${it.title} | ${it.url} | ${it.snippet.take(160)}"
        }
        val user = "Вопрос: $question\nСегодня: $today\n\nСОБРАНО:\n$map"
        val raw = runCatching {
            llm.complete(listOf(Message(Role.System, CONTROLLER_PROMPT), Message(Role.User, user))).text
        }.getOrNull() ?: return Decision(done = true)
        return parseDecision(raw)
    }

    private fun parseDecision(raw: String): Decision {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return Decision(done = true)
        return runCatching { json.decodeFromString<Decision>(raw.substring(start, end + 1)) }
            .getOrElse { Decision(done = true) }
    }

    /** Синтез финальной сводки из всех собранных источников (сниппеты + тексты страниц). */
    private suspend fun synthesize(question: String, portal: String, today: String, ev: List<Evidence>): String {
        // Официальные источники — вперёд: модель видит их первыми и цитирует в приоритете (стабильная сортировка).
        val body = ev.sortedByDescending { it.official }.joinToString("\n\n---\n\n") {
            buildString {
                append("${if (it.official) "[офиц.] " else ""}${it.title}\nURL: ${it.url}\n${it.snippet}")
                it.fullText?.let { t -> append("\nТЕКСТ СТРАНИЦЫ:\n$t") }
            }
        }
        val user = "Запрос: $question\nСегодня: $today\nОфиц. портал (реестр): $portal\n\nСОБРАННЫЕ ИСТОЧНИКИ:\n$body"
        return runCatching {
            llm.complete(listOf(Message(Role.System, SYNTH_PROMPT), Message(Role.User, user))).text
        }.getOrElse { e ->
            "Не удалось собрать сводку (LLM): ${e.message}\n\nИсточники:\n$body\n" +
                "Куда подавать: $portal · по состоянию на $today"
        }
    }
}
