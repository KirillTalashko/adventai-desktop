package com.example.adventdesktop.data

import com.example.adventdesktop.domain.rag.Embedder
import com.example.adventdesktop.domain.rag.KnowledgeHit
import com.example.adventdesktop.domain.rag.KnowledgeRetriever
import com.example.adventdesktop.domain.rag.RagOptions

/**
 * Реализация порта [KnowledgeRetriever] (День 25): поиск во внутренней базе знаний через существующий
 * двухэтапный пайплайн [KnowledgeIndex.retrieve] (Дни 23–24) — contextual chunking + эвристический реранк +
 * фильтр по порогу. Детерминированно, БЕЗ LLM (gateway = null, rewrite/LLM-реранк выключены): нужен только
 * эмбеддинг (локальная Ollama работает и headless) — поэтому пригодно для каждого хода агента и для тестов.
 *
 * Эмбеддер создаётся и закрывается НА ВЫЗОВ (как в dev-панели), чтобы не держать HTTP-клиент открытым между
 * ходами. Слабый/пустой контекст → пустой список: RAG «молчит», агент опирается на MCP/[СПРАВКА] (аддитивность).
 */
class RagKnowledgeRetriever(
    private val index: KnowledgeIndex,
    private val embedderFactory: () -> Embedder,
    private val options: RagOptions = RagOptions(),
) : KnowledgeRetriever {

    override suspend fun retrieve(query: String): List<KnowledgeHit> {
        if (query.isBlank()) return emptyList()
        val embedder = embedderFactory()
        return try {
            index.retrieve(gateway = null, embedder = embedder, options = options, question = query).after
                .map { s ->
                    KnowledgeHit(
                        source = s.chunk.meta.source,
                        section = s.chunk.meta.section,
                        chunkId = s.chunk.meta.chunkId,
                        score = s.score,
                        text = s.chunk.text.trim(),
                    )
                }
        } catch (_: Exception) {
            emptyList()   // при сбое (нет Ollama/индекса) RAG молчит — агент работает без базы, не падает
        } finally {
            (embedder as? OllamaEmbedder)?.close()
        }
    }
}
