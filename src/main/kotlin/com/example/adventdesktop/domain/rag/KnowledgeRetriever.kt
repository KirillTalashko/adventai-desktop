package com.example.adventdesktop.domain.rag

/**
 * Одна найденная выдержка из внутренней базы знаний для агента (День 25): провенанс (файл › раздел ·
 * chunk_id), близость и текст чанка. То же, что источники Дня 24, но как доменная модель для оркестратора
 * (без UI/HTTP): агент цитирует их как [S1], [S2]…, а UI показывает список источников в ответе.
 */
data class KnowledgeHit(
    val source: String,
    val section: String,
    val chunkId: String,
    val score: Float,
    val text: String,
)

/**
 * Порт поиска по внутренней базе знаний (День 25) — домен не знает про эмбеддер/SQLite/Ollama.
 * Реализация (`data/RagKnowledgeRetriever`) переиспользует двухэтапный пайплайн Дней 23–24 (contextual +
 * реранк + фильтр по порогу). Пусто = релевантного контекста нет → агент опирается на MCP/[СПРАВКА]
 * (аддитивность RAG + MCP). Детерминированно, без вызова LLM (только эмбеддинг + эвристика).
 */
interface KnowledgeRetriever {
    suspend fun retrieve(query: String): List<KnowledgeHit>
}
