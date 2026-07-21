package com.example.adventdesktop.data

import com.example.adventdesktop.domain.LlmGateway
import com.example.adventdesktop.domain.runCatchingCancellable
import com.example.adventdesktop.domain.rag.CitationCheck
import com.example.adventdesktop.domain.rag.ContextualChunker
import com.example.adventdesktop.domain.rag.DocumentIndexer
import com.example.adventdesktop.domain.rag.Embedder
import com.example.adventdesktop.domain.rag.FixedSizeChunker
import com.example.adventdesktop.domain.rag.GoldAnswer
import com.example.adventdesktop.domain.rag.GoldQuestion
import com.example.adventdesktop.domain.rag.GoldRetrieval
import com.example.adventdesktop.domain.rag.HeuristicReranker
import com.example.adventdesktop.domain.rag.IndexStats
import com.example.adventdesktop.domain.rag.LlmReranker
import com.example.adventdesktop.domain.rag.QueryRewriter
import com.example.adventdesktop.domain.rag.RagAnswer
import com.example.adventdesktop.domain.rag.RagAnswerer
import com.example.adventdesktop.domain.rag.RagDocument
import com.example.adventdesktop.domain.rag.RagFaithfulnessJudge
import com.example.adventdesktop.domain.rag.RagOptions
import com.example.adventdesktop.domain.rag.RagSearch
import com.example.adventdesktop.domain.rag.RelevanceFilter
import com.example.adventdesktop.domain.rag.RerankMode
import com.example.adventdesktop.domain.rag.RetrievalTrace
import com.example.adventdesktop.domain.rag.RewriteOutcome
import com.example.adventdesktop.domain.rag.Scored
import com.example.adventdesktop.domain.rag.StructuralChunker
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.io.File

/**
 * Координатор RAG-индекса (День 21), слой data. Держит папку знаний и SQLite-хранилище, при первом запуске
 * засевает корпус из ресурсов (`resources/knowledge/` по манифесту), строит обе стратегии chunking и
 * умеет искать. Общий для приложения: `~/.adventai/rag/` (визовая база знаний не персональна).
 *
 * Эмбеддер передаётся в операции ([rebuild]/[search]), а не в конструктор, — чтобы можно было строить и
 * настоящей [OllamaEmbedder], и офлайн-[HashingEmbedder] без пересоздания хранилища.
 */
class KnowledgeIndex(ragDir: File) {

    val knowledgeDir: File = File(ragDir, "knowledge").apply { mkdirs() }
    private val store = SqliteIndexStore(File(ragDir, "index.db").path)

    /**
     * Докопировать НЕДОСТАЮЩИЕ файлы корпуса из ресурсов (по манифесту) и сгенерировать PDF, если его нет.
     * Идемпотентно: существующие файлы (в т.ч. добавленные/отредактированные пользователем) НЕ трогаем, но
     * новые встроенные доки «доливаются» при обновлении приложения (иначе старая папка застревает на старом
     * наборе — файлы копировались только в пустую папку).
     */
    fun seedMissing() {
        readResource("knowledge/_manifest.txt")?.decodeToString()
            ?.lines()?.map { it.trim() }?.filter { it.isNotBlank() && !it.startsWith("#") }
            ?.forEach { name ->
                val target = File(knowledgeDir, name)
                if (target.exists()) return@forEach
                val bytes = readResource("knowledge/$name") ?: return@forEach
                runCatching { target.writeBytes(bytes) }
            }
        // Образец PDF генерируем (не тащим бинарник в репозиторий) — демонстрирует ветку pdf→текст.
        val pdf = File(knowledgeDir, "japan.pdf")
        if (!pdf.exists()) runCatching { SamplePdf.writeJapanMemo(pdf) }
    }

    /** Документы из папки знаний (README/статьи/код/PDF → текст). */
    fun documents(): List<RagDocument> = DocumentLoader.loadDir(knowledgeDir)

    /** Событие построения индекса (Flow): прогресс эмбеддинга по стратегиям и финальное сравнение. */
    sealed interface RebuildEvent {
        data class Progress(val strategy: String, val done: Int, val total: Int) : RebuildEvent
        data class Completed(val comparison: Comparison) : RebuildEvent
    }

    /**
     * Построить индекс ВСЕХ трёх стратегий одним эмбеддером, сохранить и **стримить прогресс** холодным
     * [Flow]: серия [RebuildEvent.Progress] по мере эмбеддинга, в конце — [RebuildEvent.Completed] со
     * сравнением. Холодный: работа стартует только на `collect`; отмена коллектора отменяет индексацию
     * (структурная конкурентность). `trySend` для прогресса — потерять тик прогресса не страшно.
     */
    fun rebuild(embedder: Embedder): Flow<RebuildEvent> = channelFlow {
        val docs = documents()
        val indexer = DocumentIndexer(embedder)
        val fixed = indexer.build(docs, FixedSizeChunker()) { done, total -> trySend(RebuildEvent.Progress("fixed", done, total)) }
        store.save(fixed.stats, fixed.chunks)
        val structural = indexer.build(docs, StructuralChunker()) { done, total -> trySend(RebuildEvent.Progress("structural", done, total)) }
        store.save(structural.stats, structural.chunks)
        val contextual = indexer.build(docs, ContextualChunker()) { done, total -> trySend(RebuildEvent.Progress("contextual", done, total)) }
        store.save(contextual.stats, contextual.chunks)
        send(RebuildEvent.Completed(Comparison(docs.size, fixed.stats, structural.stats, contextual.stats)))
    }

    fun stats(strategy: String): IndexStats? = store.stats(strategy)
    fun strategies(): List<String> = store.strategies()

    suspend fun search(embedder: Embedder, strategy: String, query: String, k: Int = 3): List<Scored> =
        RagSearch(embedder).search(store.load(strategy), query, k)

    // --- День 22–23: двухэтапный RAG-пайплайн (retrieve → rerank → filter) + ответ + контрольный набор ---

    /**
     * Второй этап поиска (День 23): [rewrite] запроса → bi-encoder top-N → реранк ([RagOptions.rerank]) →
     * фильтр по порогу → top-K. Возвращает трейс с «до/после» для наглядного сравнения.
     */
    suspend fun retrieve(gateway: LlmGateway?, embedder: Embedder, options: RagOptions, question: String): RetrievalTrace {
        val rw = if (options.rewrite && gateway != null) QueryRewriter(gateway).rewrite(question) else null
        val used = rw?.query ?: question
        val outcome = when {
            rw == null -> RewriteOutcome.OFF            // rw == null ⇔ (!options.rewrite || gateway == null)
            rw.failed -> RewriteOutcome.FAILED
            used != question -> RewriteOutcome.REWRITTEN
            else -> RewriteOutcome.UNCHANGED
        }
        val pool = search(embedder, options.strategy, used, options.retrieveN)   // широкий пул bi-encoder
        val before = pool.take(options.topK)                                     // baseline: сырой top-K по cosine
        val reranked = when (options.rerank) {
            RerankMode.OFF -> pool
            RerankMode.HEURISTIC -> HeuristicReranker().rerank(used, pool)
            RerankMode.LLM -> if (gateway != null) LlmReranker(gateway).rerank(used, pool) else HeuristicReranker().rerank(used, pool)
        }
        val survived = reranked.filter { it.score >= options.floor }
        val after = survived.take(options.topK)
        return RetrievalTrace(question, used, outcome, before, after, pool.size, survived.size, pool.size - survived.size)
    }

    /**
     * Ответ в одном из режимов. **С RAG**: улучшенный пайплайн [retrieve] → контекст → LLM; **без RAG**: тот
     * же вопрос без контекста. Если после фильтра пусто (вопрос не из базы) — модель честно откажет.
     */
    suspend fun answer(gateway: LlmGateway, embedder: Embedder, question: String, useRag: Boolean, options: RagOptions = RagOptions()): RagAnswer {
        val answerer = RagAnswerer(gateway)
        if (!useRag) return answerer.plain(question)
        val after = retrieve(gateway, embedder, options, question).after
        // День 24: релевантность ниже порога (фильтр оставил пусто) → режим «не знаю», без обращения к LLM.
        return if (after.isEmpty()) answerer.abstain(question) else answerer.withContext(question, after)
    }

    /** Ответ С RAG вместе с трейсом поиска (для показа top-K до/после в панели). Пусто после фильтра → «не знаю». */
    suspend fun answerWithTrace(gateway: LlmGateway, embedder: Embedder, question: String, options: RagOptions): Pair<RagAnswer, RetrievalTrace> {
        val trace = retrieve(gateway, embedder, options, question)
        val answerer = RagAnswerer(gateway)
        val ans = if (trace.after.isEmpty()) answerer.abstain(question) else answerer.withContext(question, trace.after)
        return ans to trace
    }

    // --- День 28: один ЛОКАЛЬНЫЙ retrieval + генерация произвольным шлюзом (сравнение local vs cloud) ---

    /** Локальный retrieval (эмбеддер), БЕЗ LLM (`gateway=null` → без rewrite/LLM-реранка) — детерминированно и локально. */
    suspend fun retrieveLocal(embedder: Embedder, options: RagOptions, question: String): List<Scored> =
        retrieve(gateway = null, embedder = embedder, options = options, question = question).after

    /** Генерация ответа заданным шлюзом поверх УЖЕ найденных чанков (пусто → честное «не знаю», без вызова LLM). */
    suspend fun generate(gateway: LlmGateway, retrieved: List<Scored>, question: String): RagAnswer =
        if (retrieved.isEmpty()) RagAnswerer(gateway).abstain(question) else RagAnswerer(gateway).withContext(question, retrieved)

    /**
     * Проверка Дня 24 по набору: для каждого вопроса — ответ С RAG, затем критерии «есть источники / есть
     * цитаты / смысл ответа совпадает с цитатами» ([RagFaithfulnessJudge]). На негативном вопросе правильно —
     * режим «не знаю». Нужен живой LLM (генерация ответа + судья).
     */
    suspend fun citationEval(
        gateway: LlmGateway,
        embedder: Embedder,
        options: RagOptions,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<CitationCheck> {
        val judge = RagFaithfulnessJudge(gateway)
        val qs = goldQuestions()
        return qs.mapIndexed { i, q ->
            currentCoroutineContext().ensureActive()   // длинный прогон — уважаем отмену между вопросами
            onProgress(i + 1, qs.size)
            val ans = runCatchingCancellable { answer(gateway, embedder, q.question, useRag = true, options) }
                .getOrElse { RagAnswer("С RAG", "(ошибка: ${it.message})", emptyList(), null, 0) }
            // Faithfulness сверяем против ИСТОЧНИКОВ ответа (полные чанки), а не одной цитаты — иначе занижаем.
            val faithful = if (ans.abstained || ans.sources.isEmpty()) null
            else runCatchingCancellable { judge.faithful(ans.text, ans.sources.map { it.text }) }.getOrNull()
            CitationCheck(q, ans, faithful)
        }
    }

    fun goldQuestions(): List<GoldQuestion> = GoldQuestions.load()

    /** Прогнать набор в ОБОИХ режимах (Вариант B): для каждого вопроса ответ С RAG (по [options]) и без RAG. */
    suspend fun goldAnswers(
        gateway: LlmGateway,
        embedder: Embedder,
        options: RagOptions,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<GoldAnswer> {
        val qs = goldQuestions()
        return qs.mapIndexed { i, q ->
            currentCoroutineContext().ensureActive()   // длинный прогон — уважаем отмену между вопросами
            onProgress(i + 1, qs.size)
            val rag = runCatchingCancellable { answer(gateway, embedder, q.question, useRag = true, options) }
                .getOrElse { RagAnswer("С RAG", "(ошибка: ${it.message})", emptyList(), null, 0) }
            val plain = runCatchingCancellable { answer(gateway, embedder, q.question, useRag = false, options) }
                .getOrElse { RagAnswer("Без RAG", "(ошибка: ${it.message})", emptyList(), null, 0) }
            GoldAnswer(q, rag, plain)
        }
    }

    /**
     * Сравнение КАЧЕСТВА ПОИСКА «без фильтра vs с фильтром» по набору (День 23) — детерминированно (без LLM,
     * если [RagOptions.rewrite] выкл): baseline = сырой top-K по cosine; improved = полный пайплайн retrieve.
     */
    suspend fun goldRetrieval(
        gateway: LlmGateway?,
        embedder: Embedder,
        options: RagOptions,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<GoldRetrieval> {
        val qs = goldQuestions()
        return qs.mapIndexed { i, q ->
            currentCoroutineContext().ensureActive()   // длинный прогон — уважаем отмену между вопросами
            onProgress(i + 1, qs.size)
            // baseline = «как в Дне 22»: структурная стратегия, сырой top-K по cosine, без реранка/фильтра.
            val base = search(embedder, BASELINE_STRATEGY, q.question, options.topK).map { it.chunk.meta.source }.distinct()
            // improved = День 23: контекстная стратегия + реранк + фильтр по порогу.
            val improved = retrieve(gateway, embedder, options, q.question).after.map { it.chunk.meta.source }.distinct()
            GoldRetrieval(q, base, improved)
        }
    }

    fun close() = store.close()

    /** Сводка сравнения трёх стратегий chunking (для dev-панели/отчёта). */
    data class Comparison(val docCount: Int, val fixed: IndexStats, val structural: IndexStats, val contextual: IndexStats)

    private fun readResource(path: String): ByteArray? =
        javaClass.classLoader.getResourceAsStream(path)?.use { it.readBytes() }

    private companion object {
        /** База сравнения Дня 23 = поведение Дня 22 (структурная стратегия без реранка/фильтра). */
        const val BASELINE_STRATEGY = "structural"
    }
}
