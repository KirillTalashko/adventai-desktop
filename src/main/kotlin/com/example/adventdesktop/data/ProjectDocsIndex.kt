package com.example.adventdesktop.data

import com.example.adventdesktop.domain.LlmGateway
import com.example.adventdesktop.domain.rag.ContextualChunker
import com.example.adventdesktop.domain.rag.DocumentIndexer
import com.example.adventdesktop.domain.rag.Embedder
import com.example.adventdesktop.domain.rag.HeuristicReranker
import com.example.adventdesktop.domain.rag.LlmReranker
import com.example.adventdesktop.domain.rag.IndexStats
import com.example.adventdesktop.domain.rag.RagDocument
import com.example.adventdesktop.domain.rag.RagOptions
import com.example.adventdesktop.domain.rag.RagSearch
import com.example.adventdesktop.domain.rag.RerankMode
import com.example.adventdesktop.domain.rag.Scored
import java.io.File

/**
 * RAG по документации САМОГО проекта (День 31) — «ассистент разработчика».
 *
 * Отдельный индекс, не смешивается с визовой базой знаний ([KnowledgeIndex]): своя SQLite-база
 * (`~/.adventai/devdocs/`), свой корпус. Корпус читается ЖИВЬЁМ из репозитория (не копируется), поэтому
 * после правки доков достаточно переиндексировать — содержимое всегда актуальное.
 *
 * Что берём (по заданию дня: README + папка доков + схемы данных):
 *  - `README.md` и остальные `*.md` в корне (CLAUDE.md, RAG_*.md, LOCAL_LLM.md …);
 *  - markdown с ВЕРХНЕГО уровня каталога `.claude` (архитектура, память, инварианты, MCP …), без рекурсии;
 *  - несколько файлов-схем ([SCHEMA_FILES]) — доменные модели и DTO как «описание данных».
 */
class ProjectDocsIndex(dir: File, private val projectRoot: File) {

    private val store = SqliteIndexStore(File(dir.apply { mkdirs() }, "devdocs.db").path)
    private val strategy = ContextualChunker().strategy

    /** Документы корпуса — читаются из репозитория на каждый вызов (актуальность без копий). */
    fun documents(): List<RagDocument> {
        val files = buildList {
            projectRoot.listFiles { f -> f.isFile && f.extension.equals("md", ignoreCase = true) }
                ?.let { addAll(it) }
            // ТОЛЬКО верхний уровень `.claude`, без рекурсии. Внутрь инструменты кладут постороннее:
            // установленные скиллы (`skills/`) и git-worktree с ПОЛНОЙ копией репозитория (`worktrees/`).
            // Рекурсивный обход затянул бы дубликаты всех доков и испортил поиск, поэтому обходимся без него.
            File(projectRoot, ".claude")
                .listFiles { f -> f.isFile && f.extension.equals("md", ignoreCase = true) }
                ?.let { addAll(it) }
            SCHEMA_FILES.map { File(projectRoot, it) }.filter { it.isFile }.forEach { add(it) }
        }
        return files.distinctBy { it.absolutePath }
            .sortedBy { it.path }
            .mapNotNull { DocumentLoader.load(it, projectRoot) }
    }

    /** Построить индекс по докам проекта и сохранить. Возвращает статистику (док/чанков/время). */
    suspend fun rebuild(embedder: Embedder, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): IndexStats {
        val docs = documents()
        val built = DocumentIndexer(embedder).build(docs, ContextualChunker(), onProgress)
        store.save(built.stats, built.chunks)
        return built.stats
    }

    /**
     * Поиск: bi-encoder top-N → реранк (LLM или эвристика) → отсечка → top-K.
     * Пусто = «в доках нет» (ассистент честно откажет).
     *
     * Две ступени делят обязанности: **порог** отвечает «есть ли в доках хоть что-то по теме» — только он
     * умеет вернуть пусто, и именно он даёт честный отказ. **LLM-реранк** ([gateway]) выбирает из
     * оставшегося то, что действительно отвечает: порогом это не решить, косинус несопоставим между
     * формулировками (один чанк давал 0.46 на русский пересказ и 0.79 на точные термины).
     *
     * Важно: [LlmReranker] намеренно **fail-open** — при сбое LLM или пустом ответе он возвращает
     * кандидатов без изменений. Поэтому на отказ он не влияет: гарантия целиком на первой ступени.
     */
    suspend fun search(
        embedder: Embedder,
        query: String,
        options: RagOptions = DEV_OPTIONS,
        gateway: LlmGateway? = null,
    ): List<Scored> {
        val index = store.load(strategy)
        if (index.isEmpty() || query.isBlank()) return emptyList()
        val pool = RagSearch(embedder).search(index, query, options.retrieveN)

        // Ступень 1 — АБСОЛЮТНАЯ близость: «есть ли в доках вообще что-то по теме?». Только этот этап
        // умеет ответить «ничего», поэтому именно он даёт честный отказ на посторонний вопрос.
        val plausible = HeuristicReranker().rerank(query, pool).filter { it.score >= options.floor }
        if (plausible.isEmpty()) return emptyList()

        // Ступень 2 — ОТБОР моделью: «что из найденного действительно отвечает?». Порог здесь бесполезен
        // (оценки несопоставимы между формулировками), а модель справляется. Невыбранные получают 0.
        val reranked = if (options.rerank == RerankMode.LLM && gateway != null) {
            LlmReranker(gateway).rerank(query, plausible)
        } else {
            plausible
        }
        return reranked.filter { it.score > 0f }.take(options.topK)
    }

    /** Статистика построенного индекса (null — ещё не строили). */
    fun stats(): IndexStats? = store.stats(strategy)

    fun close() = store.close()

    companion object {
        /** Файлы-схемы: доменные модели и DTO — «схемы данных» из задания дня. */
        private val SCHEMA_FILES = listOf(
            "src/main/kotlin/com/example/adventdesktop/domain/Model.kt",
            "src/main/kotlin/com/example/adventdesktop/domain/CaseFile.kt",
            "src/main/kotlin/com/example/adventdesktop/data/Dto.kt",
        )

        /**
         * Настройки под техническую документацию: широкий пул (20 из ~380 чанков) + **двухступенчатый отбор**.
         *
         * Порог [RagOptions.floor] здесь работает как грубый фильтр «есть ли что-то по теме», а не как
         * мера релевантности: замер на этом корпусе дал 0.62 у лучшего чанка по вопросу из доков против
         * 0.36 по заведомо постороннему — разделение уверенное. Тонкий отбор делает модель: одного порога
         * мало (оценки несопоставимы между формулировками), а одной модели мало (маленькая локальная LLM
         * всегда что-нибудь выбирает и не умеет сказать «ничего не подходит»).
         */
        val DEV_OPTIONS = RagOptions(retrieveN = 20, topK = 5, floor = 0.45f, rerank = RerankMode.LLM)
    }
}
