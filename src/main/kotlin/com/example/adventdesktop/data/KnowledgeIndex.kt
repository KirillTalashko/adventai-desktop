package com.example.adventdesktop.data

import com.example.adventdesktop.domain.LlmGateway
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
import com.example.adventdesktop.domain.rag.RagOptions
import com.example.adventdesktop.domain.rag.RagSearch
import com.example.adventdesktop.domain.rag.RelevanceFilter
import com.example.adventdesktop.domain.rag.RerankMode
import com.example.adventdesktop.domain.rag.RetrievalTrace
import com.example.adventdesktop.domain.rag.RewriteOutcome
import com.example.adventdesktop.domain.rag.Scored
import com.example.adventdesktop.domain.rag.StructuralChunker
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

    /** Построить индекс ВСЕХ трёх стратегий одним эмбеддером и сохранить. Вернуть их статистику для сравнения. */
    suspend fun rebuild(embedder: Embedder, onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }): Comparison {
        val docs = documents()
        val indexer = DocumentIndexer(embedder)
        val fixed = indexer.build(docs, FixedSizeChunker()) { d, t -> onProgress("fixed", d, t) }
        store.save(fixed.stats, fixed.chunks)
        val structural = indexer.build(docs, StructuralChunker()) { d, t -> onProgress("structural", d, t) }
        store.save(structural.stats, structural.chunks)
        val contextual = indexer.build(docs, ContextualChunker()) { d, t -> onProgress("contextual", d, t) }
        store.save(contextual.stats, contextual.chunks)
        return Comparison(docs.size, fixed.stats, structural.stats, contextual.stats)
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
            !options.rewrite || gateway == null -> RewriteOutcome.OFF
            rw!!.failed -> RewriteOutcome.FAILED
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
        return answerer.withContext(question, retrieve(gateway, embedder, options, question).after)
    }

    /** Ответ С RAG вместе с трейсом поиска (для показа top-K до/после в панели). */
    suspend fun answerWithTrace(gateway: LlmGateway, embedder: Embedder, question: String, options: RagOptions): Pair<RagAnswer, RetrievalTrace> {
        val trace = retrieve(gateway, embedder, options, question)
        return RagAnswerer(gateway).withContext(question, trace.after) to trace
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
            onProgress(i + 1, qs.size)
            val rag = runCatching { answer(gateway, embedder, q.question, useRag = true, options) }
                .getOrElse { RagAnswer("С RAG", "(ошибка: ${it.message})", emptyList(), null, 0) }
            val plain = runCatching { answer(gateway, embedder, q.question, useRag = false, options) }
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
