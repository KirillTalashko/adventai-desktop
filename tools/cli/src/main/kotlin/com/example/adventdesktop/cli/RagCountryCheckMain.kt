package com.example.adventdesktop.cli

import com.example.adventdesktop.data.KnowledgeIndex
import com.example.adventdesktop.data.OllamaEmbedder
import com.example.adventdesktop.data.RagKnowledgeRetriever
import com.example.adventdesktop.data.appHomeDir
import com.example.adventdesktop.domain.rag.CountryScope
import com.example.adventdesktop.domain.rag.KnowledgeHit
import com.example.adventdesktop.domain.rag.KnowledgeScope
import com.example.adventdesktop.domain.rag.RagOptions
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Приёмка странового скоупа RAG: агент не должен отвечать по правилам ЧУЖОЙ страны.
 *
 * Проверяет два независимых уровня:
 *  - **A. Инварианты словаря** (детерминированно, без сети): покрытие манифеста корпуса и обратимость
 *    алиасов — падает, если в базу знаний добавили страновой документ и забыли запись в [CountryScope].
 *  - **B. Ретрив на боевом индексе** (нужна Ollama + построенный индекс): 6 фикстур, включая негативные —
 *    страны нет в базе, страна не названа — и контроль «не отрезали лишнего» (страна-агностичные доки).
 *
 * Каждая фикстура гоняется ДВАЖДЫ — без скоупа и со скоупом — чтобы разница была видна в цифрах.
 *
 * Запуск:  .\gradlew.bat runRagCountryCheck
 * Требует для части B: `ollama serve` + `ollama pull nomic-embed-text` и построенный индекс (панель RAG).
 */

/** Страна-агностичные документы корпуса: доступны при ЛЮБОЙ стране (осознанный список для проверки покрытия). */
private val AGNOSTIC = setOf(
    "general-process.md", "checklists.md", "faq.md", "insurance-photo.md", "biometrics-appeals.md",
    "transit-visa.md", "student-visa.md", "work-visa.md", "digital-nomad-residency.md",
)

private data class Case(
    val name: String,
    val destination: String,
    val purpose: String = "",
    val query: String,
    val mustNot: List<String> = emptyList(),
    val mustAny: List<String> = emptyList(),
    val onlyAgnostic: Boolean = false,
)

private val CASES = listOf(
    Case(
        name = "Испания/исполнение (кейс со скриншота)",
        destination = "Испания", purpose = "туризм",
        query = "Соберите базовый пакет документов на всю семью",
        mustNot = listOf("japan.md", "japan.pdf"),
        mustAny = listOf("spain.md", "schengen.md"),
    ),
    Case(
        name = "Испания/ассист (короткий вопрос)",
        destination = "Испания", purpose = "туризм",
        query = "а какие документы нужны?",
        mustNot = listOf("japan.md", "japan.pdf"),
        mustAny = listOf("spain.md", "schengen.md"),
    ),
    Case(
        name = "НЕГАТИВ: страны нет в базе (Албания)",
        destination = "Албания",
        query = "какие документы нужны на визу",
        onlyAgnostic = true,
    ),
    Case(
        name = "НЕГАТИВ: страна не названа (первый ход)",
        destination = "",
        query = "какие документы нужны на визу?",
        onlyAgnostic = true,
    ),
    Case(
        name = "Рамочный документ без страны (Шенген)",
        destination = "",
        query = "сколько дней можно быть в Шенгене за полгода?",
        mustAny = listOf("schengen.md", "faq.md"),
    ),
    Case(
        name = "КОНТРОЛЬ: не отрезали агностичные (Германия/работа)",
        destination = "Германия", purpose = "работа",
        query = "что нужно для рабочей визы",
        mustNot = listOf("japan.md", "japan.pdf", "usa.md"),
        mustAny = listOf("work-visa.md", "germany.md", "schengen.md"),
    ),
)

private var failures = 0

private fun check(ok: Boolean, label: String, detail: String = "") {
    if (!ok) failures++
    println("  ${if (ok) "PASS" else "FAIL"}  $label${if (detail.isBlank()) "" else " — $detail"}")
}

private fun manifest(): List<String> =
    object {}.javaClass.classLoader.getResourceAsStream("knowledge/_manifest.txt")
        ?.use { it.readBytes().decodeToString() }
        ?.lines()?.map { it.trim() }?.filter { it.isNotBlank() && !it.startsWith("#") }
        .orEmpty()

/** A. Инварианты словаря — без сети и без индекса. */
private fun structuralInvariants() {
    println("\n=== A. Инварианты словаря CountryScope ===")

    val files = manifest() + "japan.pdf"     // PDF генерируется при сидинге и в манифест не входит
    val known = CountryScope.knownDocs()
    val unclassified = files.filter { it.lowercase() !in known && it !in AGNOSTIC }
    check(
        unclassified.isEmpty(),
        "покрытие манифеста (${files.size} файлов)",
        if (unclassified.isEmpty()) "" else "не классифицированы: ${unclassified.joinToString()} — добавь запись в CountryScope",
    )

    // Обратимость: русское название страны → тот же канонический ключ (с падежом «в …»).
    val samples = mapOf(
        "spain" to "Испания", "france" to "Франция", "germany" to "Германия", "japan" to "Япония",
        "uk" to "Великобритания", "usa" to "США", "uae" to "ОАЭ", "south-korea" to "Южная Корея",
        "indonesia" to "Бали", "tanzania" to "Занзибар", "sri-lanka" to "Шри-Ланка", "turkey" to "Турция",
        "dominican-republic" to "Доминикана", "new-zealand" to "Новая Зеландия", "portugal" to "Португалия",
    )
    val badAlias = samples.filter { (key, name) -> CountryScope.scopeFor(name).country != key }
    check(badAlias.isEmpty(), "обратимость алиасов (${samples.size} проб)", badAlias.entries.joinToString { "${it.value}→${CountryScope.scopeFor(it.value).country.ifBlank { "?" }} (ждали ${it.key})" })

    val cases = listOf("в Испанию" to "spain", "Испании" to "spain", "поездка в Японию" to "japan")
    val badForms = cases.filter { (text, key) -> CountryScope.scopeFor(text).country != key }
    check(badForms.isEmpty(), "падежные формы", badForms.joinToString { it.first })

    // Ловушка на омонимию: «Северная Корея» не должна схлопнуться в south-korea.
    check(CountryScope.scopeFor("Северная Корея").country == "north-korea", "омонимия «Северная Корея»", CountryScope.scopeFor("Северная Корея").country)

    // Fail-safe: две страны в тексте → не гадаем.
    val ambiguous = CountryScope.scopeFor("", "был в Японии, теперь хочу в Испанию")
    check(!ambiguous.isKnown, "неоднозначность (2 страны) → страна не определена", ambiguous.country)

    // Страна распознана, но документа нет → предупреждение обязательно.
    val pt = CountryScope.scopeFor("Португалия")
    check(pt.isKnown && !pt.hasCountryDoc, "Португалия: распознана, документа нет → нужно предупреждение")
    check(pt.allows("schengen.md"), "Португалия видит schengen.md (член Шенгена)")
    check(!pt.allows("spain.md"), "Португалия НЕ видит spain.md")

    // Агностичные доступны при любой стране.
    val es = CountryScope.scopeFor("Испания")
    check(AGNOSTIC.all { es.allows(it) }, "агностичные доступны при известной стране")
    check(es.allows("spain.md") && !es.allows("japan.md"), "Испания: своё видит, чужое нет")
}

private fun report(tag: String, hits: List<KnowledgeHit>) {
    if (hits.isEmpty()) { println("    $tag: (пусто)"); return }
    println("    $tag: " + hits.joinToString { "${it.source}(${"%.3f".format(it.score)})" })
}

/** B. Ретрив на боевом индексе: без скоупа vs со скоупом. */
private suspend fun retrievalFixtures(): Boolean {
    println("\n=== B. Ретрив на боевом индексе ===")
    val ragDir = File(appHomeDir(), "rag")
    val db = File(ragDir, "index.db")
    if (!db.exists()) {
        println("  ПРОПУЩЕНО: индекса нет (${db.path}). Построй его в панели «Индексация знаний (RAG)».")
        return false
    }
    val index = KnowledgeIndex(ragDir)
    val stats = index.stats("contextual")
    if (stats == null) {
        println("  ПРОПУЩЕНО: в индексе нет стратегии contextual — перестрой индекс.")
        return false
    }
    println("  Индекс: ${stats.chunkCount} чанков, эмбеддер ${stats.embedderId}")
    val retriever = RagKnowledgeRetriever(index, { OllamaEmbedder() })
    // Диагностика: тот же пайплайн без отсечки по порогу — видно, что именно съел floor.
    val noFloor = RagKnowledgeRetriever(index, { OllamaEmbedder() }, RagOptions(floor = 0f))

    for (c in CASES) {
        val scope: KnowledgeScope = CountryScope.scopeFor(c.destination, c.query)
        val hint = listOf(c.destination, c.purpose).filter { it.isNotBlank() }.joinToString(" ")
        val query = if (hint.isBlank()) c.query else "$hint ${c.query}"
        println("\n  • ${c.name}")
        println("    запрос: «$query» · скоуп: ${scope.country.ifBlank { "—" }}${if (scope.isKnown && !scope.hasCountryDoc) " (документа нет)" else ""}")

        val before = retriever.retrieve(query, null)
        val after = retriever.retrieve(query, scope).filter { scope.allows(it.source) }
        report("до фикса ", before)
        report("со скоупом", after)
        val dropped = noFloor.retrieve(query, scope).filter { scope.allows(it.source) && it !in after }
        if (dropped.isNotEmpty()) report("срезал порог", dropped)
        if (scope.hasCountryDoc) {
            val own = noFloor.retrieve(query, KnowledgeScope(scope.country, true, true))
                .filter { CountryScope.countryOfSource(it.source) == scope.country }
            report("док страны ", own)
        }

        val srcs = after.map { it.source }
        c.mustNot.forEach { bad -> check(bad !in srcs, "нет $bad", if (bad in srcs) "найден!" else "") }
        if (c.mustAny.isNotEmpty()) {
            check(srcs.any { it in c.mustAny }, "есть один из ${c.mustAny}", if (srcs.isEmpty()) "выдача пуста" else srcs.joinToString())
        }
        if (c.onlyAgnostic) {
            val foreign = srcs.filter { CountryScope.countryOfSource(it).isNotBlank() }
            check(foreign.isEmpty(), "ни одного странового документа", foreign.joinToString())
        }
    }
    return true
}

fun main() = runBlocking {
    println("Приёмка странового скоупа RAG (защита от ответа по правилам чужой страны)")
    structuralInvariants()
    val ran = runCatching { retrievalFixtures() }.getOrElse {
        println("  ПРОПУЩЕНО: ${it.message} (нужна запущенная Ollama с nomic-embed-text)")
        false
    }

    println("\n=== ИТОГ ===")
    if (!ran) println("  Часть B не выполнена — проверены только инварианты словаря.")
    if (failures == 0) {
        println("  Все проверки пройдены.")
    } else {
        println("  ПРОВАЛОВ: $failures")
        exitProcess(1)
    }
}
