package com.example.adventdesktop.data

import com.example.adventdesktop.domain.LlmGateway
import com.example.adventdesktop.domain.rag.Chunker
import com.example.adventdesktop.domain.rag.DocumentIndexer
import com.example.adventdesktop.domain.rag.Embedder
import com.example.adventdesktop.domain.rag.FixedSizeChunker
import com.example.adventdesktop.domain.rag.GoldAnswer
import com.example.adventdesktop.domain.rag.GoldQuestion
import com.example.adventdesktop.domain.rag.IndexStats
import com.example.adventdesktop.domain.rag.RagAnswer
import com.example.adventdesktop.domain.rag.RagAnswerer
import com.example.adventdesktop.domain.rag.RagDocument
import com.example.adventdesktop.domain.rag.RagSearch
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

    /** Построить индекс ОБЕИХ стратегий одним эмбеддером и сохранить. Вернуть их статистику для сравнения. */
    suspend fun rebuild(embedder: Embedder, onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }): Comparison {
        val docs = documents()
        val indexer = DocumentIndexer(embedder)
        val fixed = indexer.build(docs, FixedSizeChunker()) { d, t -> onProgress("fixed", d, t) }
        store.save(fixed.stats, fixed.chunks)
        val structural = indexer.build(docs, StructuralChunker()) { d, t -> onProgress("structural", d, t) }
        store.save(structural.stats, structural.chunks)
        return Comparison(docs.size, fixed.stats, structural.stats)
    }

    fun stats(strategy: String): IndexStats? = store.stats(strategy)
    fun strategies(): List<String> = store.strategies()

    suspend fun search(embedder: Embedder, strategy: String, query: String, k: Int = 3): List<Scored> =
        RagSearch(embedder).search(store.load(strategy), query, k)

    // --- День 22: RAG-ответ (два режима) + контрольный набор ---

    /**
     * Ответ на вопрос в одном из режимов. **С RAG**: ищем top-[k] чанков и передаём их LLM как контекст;
     * **без RAG**: тот же вопрос без контекста. Эмбеддер нужен только для поиска (режим с RAG).
     */
    suspend fun answer(
        gateway: LlmGateway,
        embedder: Embedder,
        question: String,
        useRag: Boolean,
        strategy: String = "structural",
        k: Int = 6,
    ): RagAnswer {
        val answerer = RagAnswerer(gateway)
        return if (useRag) answerer.withContext(question, search(embedder, strategy, question, k))
        else answerer.plain(question)
    }

    fun goldQuestions(): List<GoldQuestion> = GoldQuestions.load()

    /**
     * Прогнать весь контрольный набор в ОБОИХ режимах (День 22, Вариант B): для каждого вопроса — ответ
     * С RAG и без RAG. Это сравнение КАЧЕСТВА ответов (ссылки, отказ на ловушке), а не только поиска.
     * Каждый вызов обёрнут — сетевой сбой на одном вопросе не рушит весь прогон.
     */
    suspend fun goldAnswers(
        gateway: LlmGateway,
        embedder: Embedder,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<GoldAnswer> {
        val qs = goldQuestions()
        return qs.mapIndexed { i, q ->
            onProgress(i + 1, qs.size)
            val rag = runCatching { answer(gateway, embedder, q.question, useRag = true) }
                .getOrElse { RagAnswer("С RAG", "(ошибка: ${it.message})", emptyList(), null, 0) }
            val plain = runCatching { answer(gateway, embedder, q.question, useRag = false) }
                .getOrElse { RagAnswer("Без RAG", "(ошибка: ${it.message})", emptyList(), null, 0) }
            GoldAnswer(q, rag, plain)
        }
    }

    fun close() = store.close()

    /** Сводка сравнения двух стратегий chunking (для dev-панели/отчёта). */
    data class Comparison(val docCount: Int, val fixed: IndexStats, val structural: IndexStats)

    private fun readResource(path: String): ByteArray? =
        javaClass.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
}
