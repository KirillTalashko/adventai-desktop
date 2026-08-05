package com.example.adventdesktop.domain.rag

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** Размер пачки для батч-векторизации: одна пачка ≈ по времени как один одиночный запрос. */
private const val EMBED_BATCH = 32

/** Сколько ПАЧЕК считаем одновременно: локальная модель обслуживает их последовательно, шквал не нужен. */
private const val EMBED_CONCURRENCY = 3

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

    /**
     * Эмбеддинг чанков идёт **параллельно** (структурная конкурентность): это сетевые вызовы к Ollama, и
     * последовательный цикл упирается в задержку — сотни чанков × ~0.5 c складываются в минуты. [awaitAll]
     * сохраняет ПОРЯДОК, поэтому `vectors[i]` по-прежнему соответствует `chunks[i]`; [Semaphore] держит
     * число одновременных запросов в разумных рамках, чтобы не завалить локальную модель.
     */
    suspend fun build(
        docs: List<RagDocument>,
        chunker: Chunker,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Built = coroutineScope {
        val t0 = System.currentTimeMillis()
        val chunks = docs.flatMap { chunker.chunk(it) }
        val done = AtomicInteger(0)
        val limit = Semaphore(EMBED_CONCURRENCY)
        val vectors = chunks.chunked(EMBED_BATCH).map { batch ->
            async {
                limit.withPermit {
                    // роль «документ» (нужен верный префикс для nomic); пачкой — один вызов вместо N
                    embedder.embedDocuments(batch.map { it.text })
                        .also { onProgress(done.addAndGet(batch.size), chunks.size) }
                }
            }
        }.awaitAll().flatten()
        val indexed = chunks.mapIndexed { i, c -> IndexedChunk(c, vectors[i]) }
        val buildMs = System.currentTimeMillis() - t0
        Built(stats(chunker.strategy, docs.size, chunks, buildMs), indexed)
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
