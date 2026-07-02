package com.example.adventdesktop.domain.rag

import java.time.Instant
import kotlin.math.sqrt

/** Векторная математика (чистая). Индекс маленький (сотни чанков) → brute-force косинус, без ANN/FAISS. */
object VectorMath {
    /** Косинусная схожесть двух векторов: 1.0 — сонаправлены, 0 — ортогональны, -1 — противоположны. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }
}

/**
 * Сервис индексации (День 21): документы → чанки выбранной стратегией → эмбеддинги → [IndexedChunk] +
 * [IndexStats]. Само сохранение — через порт [IndexStore] в вызывающем коде (Clean Architecture).
 */
class DocumentIndexer(private val embedder: Embedder) {

    /** Результат построения одной стратегии: статистика + готовые к сохранению чанки. */
    data class Built(val stats: IndexStats, val chunks: List<IndexedChunk>)

    suspend fun build(
        docs: List<RagDocument>,
        chunker: Chunker,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Built {
        val t0 = System.currentTimeMillis()
        val chunks = docs.flatMap { chunker.chunk(it) }
        val vectors = ArrayList<FloatArray>(chunks.size)
        for ((i, c) in chunks.withIndex()) {
            vectors += embedder.embedDocument(c.text)   // роль «документ» (нужен верный префикс для nomic)
            onProgress(i + 1, chunks.size)
        }
        val indexed = chunks.mapIndexed { i, c -> IndexedChunk(c, vectors[i]) }
        val buildMs = System.currentTimeMillis() - t0
        return Built(stats(chunker.strategy, docs.size, chunks, buildMs), indexed)
    }

    private fun stats(strategy: String, docCount: Int, chunks: List<Chunk>, buildMs: Long): IndexStats {
        val sizes = chunks.map { it.text.length }
        val tokens = chunks.map { it.meta.approxTokens }
        val sections = chunks.map { it.meta.section }.filter { it.isNotBlank() }.toSet()
        return IndexStats(
            strategy = strategy,
            embedderId = embedder.id,
            dimension = embedder.dimension,
            docCount = docCount,
            chunkCount = chunks.size,
            avgChars = sizes.averageOrZero(),
            minChars = sizes.minOrNull() ?: 0,
            maxChars = sizes.maxOrNull() ?: 0,
            avgTokens = tokens.averageOrZero(),
            sectionCount = sections.size,
            builtAt = Instant.now().toString(),
            buildMs = buildMs,
        )
    }

    private fun List<Int>.averageOrZero(): Int = if (isEmpty()) 0 else (sum() / size)
}

/** Поиск по индексу: эмбеддим запрос и берём top-[k] по косинусу (brute-force). */
class RagSearch(private val embedder: Embedder) {
    suspend fun search(index: List<IndexedChunk>, query: String, k: Int = 5): List<Scored> {
        if (index.isEmpty()) return emptyList()
        val q = embedder.embedQuery(query)   // роль «запрос» (asymmetric retrieval)
        return index.map { Scored(it.chunk, VectorMath.cosine(q, it.embedding)) }
            .sortedByDescending { it.score }
            .take(k)
    }
}
