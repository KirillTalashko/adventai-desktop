package com.example.adventdesktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.adventdesktop.data.DesktopConfig
import com.example.adventdesktop.data.HashingEmbedder
import com.example.adventdesktop.data.KnowledgeIndex
import com.example.adventdesktop.data.LlmClient
import com.example.adventdesktop.data.LlmGatewayClient
import com.example.adventdesktop.data.LocalLlmClient
import com.example.adventdesktop.data.Models
import com.example.adventdesktop.data.OllamaEmbedder
import com.example.adventdesktop.data.fetchOllamaModels
import com.example.adventdesktop.data.resolveLlmConfig
import com.example.adventdesktop.domain.rag.CitationCheck
import com.example.adventdesktop.domain.rag.Embedder
import com.example.adventdesktop.domain.rag.GoldAnswer
import com.example.adventdesktop.domain.rag.GoldRetrieval
import com.example.adventdesktop.domain.rag.IndexStats
import com.example.adventdesktop.domain.rag.RagAnswer
import com.example.adventdesktop.domain.rag.RagOptions
import com.example.adventdesktop.domain.rag.RerankMode
import com.example.adventdesktop.domain.rag.RetrievalTrace
import com.example.adventdesktop.domain.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Держатель состояния dev-панели RAG (расшивка god-object [ChatState] по SRP «актёр = панель RAG»).
 *
 * Владеет ТОЛЬКО состоянием и логикой панели (индексация, сравнение ответов С/БЕЗ RAG, локаль-vs-облако,
 * прогон «золотого» набора, проверка цитат). Агентское ядро RAG остаётся в [ChatState]:
 * `knowledge()` / `newEmbedder()` / `ragQueryEmbedder()` / `ragUseOllama` / `AGENT_RAG_OPTIONS` питают
 * `rebuildAgent()` — их поведение НЕ меняется (доказывается пустым diff по этим символам).
 *
 * Общие с агентом аксессоры инжектируются лямбдами, чтобы стрелка зависимости шла UI→ядро, а не наоборот:
 *  - [knowledge]/[newEmbedder] — как функции (вызовы `knowledge()`/`newEmbedder()` дословны);
 *  - [clientProvider]/[configProvider] — читаются через getter-свойства [client]/[config] (тела дословны),
 *    т.к. в [ChatState] это мутабельные `var`, пересоздаваемые в `rebuildAgent()`;
 *  - [localLlm] — панель RAG переиспользует её список установленных Ollama-моделей.
 */
class RagPanelState(
    private val scope: CoroutineScope,
    private val knowledge: () -> KnowledgeIndex,
    private val newEmbedder: () -> Embedder,
    private val clientProvider: () -> LlmGatewayClient?,
    private val configProvider: () -> DesktopConfig,
    private val localLlm: LocalLlmPanelState,
) {
    private val client: LlmGatewayClient? get() = clientProvider()
    private val config: DesktopConfig get() = configProvider()

    // --- День 21: индексация базы знаний (RAG) — пайплайн chunking → эмбеддинги → SQLite-индекс ---

    var ragOpen by mutableStateOf(false)
        private set
    var ragBuilding by mutableStateOf(false)
        private set
    var ragProgress by mutableStateOf("")
        private set
    var ragNote by mutableStateOf<String?>(null)
        private set
    var ragComparison by mutableStateOf<RagComparisonView?>(null)
        private set
    var ragDocCount by mutableStateOf(0)
        private set
    var ragQuery by mutableStateOf("Сколько дней можно находиться в Шенгене?")

    fun openRag() {
        ragOpen = true
        ragNote = null
        val k = knowledge()
        ragDocCount = k.documents().size
        val f = k.stats("fixed")
        val s = k.stats("structural")
        val c = k.stats("contextual")
        ragComparison = if (f != null && s != null && c != null) RagComparisonView(ragDocCount, f.toView(), s.toView(), c.toView()) else null
        // День 28 — установленные Ollama-модели для выбора локальной модели в сравнении (эмбеддеры отфильтрованы).
        scope.launch {
            val m = fetchOllamaModels()
            localLlm.setAvailableModels(m)   // RAG переиспользует список моделей панели «Локальная LLM»
            if (ragLocalModel !in m && m.isNotEmpty()) ragLocalModel = m.first()
        }
    }

    fun closeRag() { ragOpen = false }

    /** Построить индекс ОБЕИХ стратегий выбранным эмбеддером и обновить сравнение. */
    fun buildIndex() {
        if (ragBuilding) return
        ragBuilding = true
        ragNote = null
        ragProgress = "Подготовка…"
        scope.launch {
            val k = knowledge()
            ragDocCount = k.documents().size
            val emb = newEmbedder()
            try {
                k.rebuild(emb).collect { event ->
                    when (event) {
                        is KnowledgeIndex.RebuildEvent.Progress ->
                            ragProgress = "${event.strategy}: ${event.done}/${event.total} чанков"
                        is KnowledgeIndex.RebuildEvent.Completed -> {
                            val c = event.comparison
                            ragComparison = RagComparisonView(c.docCount, c.fixed.toView(), c.structural.toView(), c.contextual.toView())
                            ragProgress = ""
                            ragNote = "Индекс построен (эмбеддер ${emb.id}) для $ragDocCount документов."
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e   // не глотаем отмену — пробрасываем для корректного завершения scope
            } catch (e: Exception) {
                ragProgress = ""
                ragNote = "Ошибка: ${e.message}" +
                    if (emb is OllamaEmbedder) "\nСовет: запусти Ollama (`ollama serve` + `ollama pull nomic-embed-text`) или выключи Ollama выше — сработает офлайн-фолбэк." else ""
            } finally {
                (emb as? OllamaEmbedder)?.close()
                ragBuilding = false
            }
        }
    }

    private fun IndexStats.toView() = RagStrategyView(
        strategy, chunkCount, avgChars, minChars, maxChars, avgTokens, sectionCount, buildMs, embedderId,
    )

    /**
     * Общий каркас async-прогона RAG-панели: `launch` → [newEmbedder] → [block] → закрыть эмбеддер → [onDone].
     * Сводит 5 копий одного скелета (ragCompare / ragCompareLocalVsCloud / runGoldAnswers / runGoldRetrieval /
     * runCitationEval). Guard (`if (running) return`), взвод флага и сброс полей — в самом методе (различны);
     * [block] сам решает onSuccess/onFailure. Закрытие эмбеддера теперь в ОДНОМ месте (было 5 копий — там же
     * чинилась «утечка при исключении»).
     */
    private fun ragJob(onDone: () -> Unit, block: suspend (Embedder) -> Unit) {
        scope.launch {
            val emb = newEmbedder()
            block(emb)
            (emb as? OllamaEmbedder)?.close()
            onDone()
        }
    }

    // --- День 22: RAG-ответ (два режима: с RAG / без) + контрольный набор из 10 вопросов ---

    var ragAnswering by mutableStateOf(false)
        private set
    var ragAnswerRag by mutableStateOf<RagAnswer?>(null)
        private set
    var ragAnswerPlain by mutableStateOf<RagAnswer?>(null)
        private set
    var goldRunning by mutableStateOf(false)
        private set
    var goldProgress by mutableStateOf("")
        private set
    var goldAnswers by mutableStateOf<List<GoldAnswer>>(emptyList())
        private set

    // --- День 23: улучшенный поиск (стратегия contextual + реранк + порог + query rewrite) ---
    // Опции пайплайна — публично-изменяемые из панели (без setX, чтобы не конфликтовать со сгенерированными сеттерами).
    var ragStrategy by mutableStateOf("contextual")
    var ragRerank by mutableStateOf(RerankMode.HEURISTIC)
    var ragRewrite by mutableStateOf(false)
    var ragFloor by mutableStateOf(0.50f)
    var ragTrace by mutableStateOf<RetrievalTrace?>(null)
        private set
    var goldRetrievalRunning by mutableStateOf(false)
        private set
    var goldRetrievalProgress by mutableStateOf("")
        private set
    var goldRetrieval by mutableStateOf<List<GoldRetrieval>>(emptyList())
        private set

    /** Текущие настройки пайплайна из состояния панели. */
    private fun ragOptions() = RagOptions(strategy = ragStrategy, rerank = ragRerank, rewrite = ragRewrite, floor = ragFloor)

    /** Сравнить ответ агента С RAG и БЕЗ RAG на текущем вопросе ([ragQuery]). С RAG — по [ragOptions]. */
    fun ragCompare() {
        val q = ragQuery.trim()
        val gw = client
        if (q.isEmpty() || ragAnswering) return
        if (gw == null) { ragNote = "Нет ключа LLM — задайте его в «Настройках»."; return }
        ragAnswering = true
        ragNote = null
        ragAnswerRag = null
        ragAnswerPlain = null
        ragTrace = null
        ragJob({ ragAnswering = false }) { emb ->
            runCatchingCancellable {
                val k = knowledge()
                ragAnswerPlain = k.answer(gw, emb, q, useRag = false)          // без RAG — из общих знаний модели
                val (ans, trace) = k.answerWithTrace(gw, emb, q, ragOptions())  // с RAG — улучшенный пайплайн
                ragAnswerRag = ans
                ragTrace = trace
            }.onFailure { ragNote = "Ошибка ответа: ${it.message}" }
        }
    }

    // --- День 28: RAG локально vs облако (один локальный retrieval → генерация двумя моделями) ---

    /** Результат генерации одной моделью поверх общего retrieval: метка, доступность, ответ, задержка, ошибка. */
    data class RagVsResult(
        val label: String,
        val available: Boolean,
        val answer: RagAnswer?,
        val ms: Long,
        val error: String?,
    )

    var ragVsRunning by mutableStateOf(false)
        private set
    var ragVsLocal by mutableStateOf<RagVsResult?>(null)
        private set
    var ragVsCloud by mutableStateOf<RagVsResult?>(null)
        private set
    /** Локальная модель Ollama для колонки «локаль» (список установленных подтягивается в [openRag]). */
    var ragLocalModel by mutableStateOf(LocalLlmClient.DEFAULT_MODEL)

    /**
     * День 28 — один ЛОКАЛЬНЫЙ retrieval (эмбеддер) → ответ генерируют ЛОКАЛЬНАЯ (Ollama) и ОБЛАЧНАЯ модели
     * поверх ОДНОГО набора чанков. Сравнение честное: контекст идентичен, отличается только генератор.
     * Локальная колонка = RAG полностью без облака. Замеряем задержку и токены.
     */
    fun ragCompareLocalVsCloud() {
        val q = ragQuery.trim()
        if (q.isEmpty() || ragVsRunning) return
        ragVsRunning = true; ragNote = null; ragVsLocal = null; ragVsCloud = null
        ragJob({ ragVsRunning = false }) { emb ->
            val localGw = LocalLlmClient(model = ragLocalModel)
            val cloudGw = resolveLlmConfig(Models.byId("deepseek-chat"), config)?.let { LlmClient(it) }
            runCatchingCancellable {
                val k = knowledge()
                val after = k.retrieveLocal(emb, ragOptions(), q)   // локальный retrieval один раз, без облака
                ragVsLocal = timeRagVs("Локальная · $ragLocalModel", true) { k.generate(localGw, after, q) }
                ragVsCloud = if (cloudGw != null) timeRagVs("Облачная · deepseek-chat", true) { k.generate(cloudGw, after, q) }
                    else RagVsResult("Облачная", false, null, 0, "Нет облачного ключа — RAG работает и без него, чисто локально.")
            }.onFailure { ragNote = "Ошибка сравнения: ${it.message}" }
            runCatchingCancellable { localGw.close() }
            runCatchingCancellable { cloudGw?.close() }
        }
    }

    private suspend fun timeRagVs(label: String, available: Boolean, block: suspend () -> RagAnswer): RagVsResult {
        val start = System.currentTimeMillis()
        return runCatchingCancellable { block() }.fold(
            onSuccess = { RagVsResult(label, available, it, System.currentTimeMillis() - start, null) },
            onFailure = { RagVsResult(label, available, null, System.currentTimeMillis() - start, it.message) },
        )
    }

    /** Подставить вопрос-ловушку (нет в базе) и сравнить — наглядно: без RAG выдумает, с RAG честно откажет. */
    fun askNegativeExample() {
        ragQuery = knowledge().goldQuestions().firstOrNull { it.isNegative }?.question
            ?: "Как оформить визу для экспедиции на Северный полюс?"
        ragCompare()
    }

    /** Прогнать весь набор (Вариант B): по каждому вопросу — ответ С RAG и без RAG, для сравнения качества. */
    fun runGoldAnswers() {
        val gw = client
        if (goldRunning) return
        if (gw == null) { ragNote = "Нет ключа LLM — задайте его в «Настройках»."; return }
        goldRunning = true
        ragNote = null
        goldAnswers = emptyList()
        goldProgress = "Подготовка…"
        ragJob({ goldRunning = false }) { emb ->
            runCatchingCancellable {
                knowledge().goldAnswers(gw, emb, ragOptions()) { i, n -> goldProgress = "вопрос $i/$n" }
            }.onSuccess { goldAnswers = it; goldProgress = "" }
                .onFailure { ragNote = "Ошибка прогона набора: ${it.message}"; goldProgress = "" }
        }
    }

    /**
     * День 23: сравнить КАЧЕСТВО ПОИСКА по набору «без фильтра vs с фильтром» — детерминированно (без LLM,
     * если query rewrite выкл). Показывает, что реранк+фильтр поднимают recall и отсекают мусор на ловушке.
     */
    fun runGoldRetrieval() {
        if (goldRetrievalRunning) return
        goldRetrievalRunning = true
        ragNote = null
        goldRetrieval = emptyList()
        goldRetrievalProgress = "Подготовка…"
        ragJob({ goldRetrievalRunning = false }) { emb ->
            runCatchingCancellable {
                knowledge().goldRetrieval(client, emb, ragOptions()) { i, n -> goldRetrievalProgress = "вопрос $i/$n" }
            }.onSuccess { goldRetrieval = it; goldRetrievalProgress = "" }
                .onFailure { ragNote = "Ошибка сравнения поиска: ${it.message}"; goldRetrievalProgress = "" }
        }
    }

    // --- День 24: проверка цитат/источников/faithfulness по набору (нужен LLM: генерация + судья) ---
    var citationEvalRunning by mutableStateOf(false)
        private set
    var citationEvalProgress by mutableStateOf("")
        private set
    var citationChecks by mutableStateOf<List<CitationCheck>>(emptyList())
        private set

    /** Прогнать 10 вопросов и проверить: есть источники / есть цитаты / смысл ответа совпал с цитатами. */
    fun runCitationEval() {
        val gw = client
        if (citationEvalRunning) return
        if (gw == null) { ragNote = "Нет ключа LLM — задайте его в «Настройках»."; return }
        citationEvalRunning = true
        ragNote = null
        citationChecks = emptyList()
        citationEvalProgress = "Подготовка…"
        ragJob({ citationEvalRunning = false }) { emb ->
            runCatchingCancellable {
                knowledge().citationEval(gw, emb, ragOptions()) { i, n -> citationEvalProgress = "вопрос $i/$n" }
            }.onSuccess { citationChecks = it; citationEvalProgress = "" }
                .onFailure { ragNote = "Ошибка проверки цитат: ${it.message}"; citationEvalProgress = "" }
        }
    }
}
