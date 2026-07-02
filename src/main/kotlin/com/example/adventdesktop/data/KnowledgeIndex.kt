package com.example.adventdesktop.data

import com.example.adventdesktop.domain.rag.Chunker
import com.example.adventdesktop.domain.rag.DocumentIndexer
import com.example.adventdesktop.domain.rag.Embedder
import com.example.adventdesktop.domain.rag.FixedSizeChunker
import com.example.adventdesktop.domain.rag.IndexStats
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

    fun close() = store.close()

    /** Сводка сравнения двух стратегий chunking (для dev-панели/отчёта). */
    data class Comparison(val docCount: Int, val fixed: IndexStats, val structural: IndexStats)

    private fun readResource(path: String): ByteArray? =
        javaClass.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
}
