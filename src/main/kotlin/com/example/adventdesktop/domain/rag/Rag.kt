package com.example.adventdesktop.domain.rag

/**
 * Модель RAG-индексации (День 21) — чистый домен, без HTTP/файлов/БД.
 *
 * Пайплайн: [RagDocument] → [Chunker] (2 стратегии) → [Chunk] (+ [ChunkMetadata]) →
 * [Embedder] (вектор) → [IndexedChunk] → сохранение через порт [IndexStore]. Поиск — [RagSearch]
 * (косинус через [VectorMath]). Всё, что «как» (Ollama, SQLite, чтение PDF) — в слое `data`.
 */

/** Документ для индексации: сырьё (markdown/txt/pdf → уже извлечённый текст). */
data class RagDocument(
    /** Стабильный id (обычно имя файла без расширения) — основа `chunkId`. */
    val id: String,
    /** Человекочитаемый заголовок (первый `#` markdown или имя файла). */
    val title: String,
    /** Провенанс: относительный путь/имя файла источника. */
    val source: String,
    /** Полный извлечённый текст документа. */
    val text: String,
)

/**
 * Метаданные чанка — «усиление» задания Дня 21: провенанс + позиция + стратегия. Кладутся в индекс, чтобы
 * ответ агента (Дни 22–23) мог сослаться на ИСТОЧНИК (файл + раздел), а не выдавать факт без ссылки.
 */
data class ChunkMetadata(
    /** Файл-источник (см. [RagDocument.source]). */
    val source: String,
    /** Заголовок документа-источника. */
    val title: String,
    /** Раздел/заголовок (для `structural` — хлебные крошки «H1 › H2»; для `fixed` — пусто). */
    val section: String,
    /** Уникальный id чанка: `"<docId>#<strategy>#<ordinal>"`. */
    val chunkId: String,
    /** Стратегия нарезки: `"fixed"` | `"structural"`. */
    val strategy: String,
    /** Порядковый номер чанка в пределах документа (0-based). */
    val ordinal: Int,
    /** Позиция чанка в исходном тексте документа (провенанс/отладка). */
    val charStart: Int,
    val charEnd: Int,
    /** Грубая оценка числа токенов (≈ по словам) — для отчёта о размере чанков. */
    val approxTokens: Int,
)

/** Кусок текста + его метаданные. */
data class Chunk(val text: String, val meta: ChunkMetadata)

/**
 * Чанк с посчитанным эмбеддингом. `equals/hashCode` по-умолчанию (identity [FloatArray]) нам не нужны —
 * храним и сравниваем через [VectorMath], в коллекциях по значению не ищем.
 */
data class IndexedChunk(val chunk: Chunk, val embedding: FloatArray)

/** Результат поиска: чанк + косинус-схожесть с запросом (1.0 — максимально близко). */
data class Scored(val chunk: Chunk, val score: Float)

/**
 * Порт эмбеддера — домен не знает, локальная это Ollama или облако. `id` фиксируется в индексе, чтобы не
 * смешивать вектора разных моделей; `dimension` — размерность вектора (напр., 768 у `nomic-embed-text`).
 */
interface Embedder {
    val id: String
    val dimension: Int
    suspend fun embed(text: String): FloatArray

    /**
     * Векторизация с РОЛЬЮ (asymmetric retrieval): документ индекса vs поисковый запрос. Многие модели
     * (напр. `nomic-embed-text`) обучены с task-префиксами `search_document:`/`search_query:` — без них
     * близости хуже различаются. По умолчанию роль игнорируется (симметрично) — реализация переопределяет.
     */
    suspend fun embedDocument(text: String): FloatArray = embed(text)
    suspend fun embedQuery(text: String): FloatArray = embed(text)
}

/** Статистика построенного индекса одной стратегии — основа сравнения 2 стратегий chunking. */
data class IndexStats(
    val strategy: String,
    val embedderId: String,
    val dimension: Int,
    val docCount: Int,
    val chunkCount: Int,
    val avgChars: Int,
    val minChars: Int,
    val maxChars: Int,
    val avgTokens: Int,
    /** Число непустых уникальных разделов (для `structural` показывает «сохранность структуры»). */
    val sectionCount: Int,
    val builtAt: String,
    val buildMs: Long,
)

/**
 * Порт хранилища индекса — домен не знает про SQLite/JSON. Индекс адресуется по имени стратегии
 * (`"fixed"`/`"structural"`) — обе стратегии живут рядом, их можно сравнивать и переключать.
 */
interface IndexStore {
    /** Перезаписать индекс стратегии: метаданные ([stats]) + все [chunks] с векторами. */
    fun save(stats: IndexStats, chunks: List<IndexedChunk>)

    /** Загрузить все чанки стратегии (пусто, если не построена). */
    fun load(strategy: String): List<IndexedChunk>

    /** Статистика стратегии или null, если не построена. */
    fun stats(strategy: String): IndexStats?

    /** Список построенных стратегий. */
    fun strategies(): List<String>

    /** Удалить индекс стратегии. */
    fun clear(strategy: String)
}
