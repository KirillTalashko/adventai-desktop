# RAG Indexing — индексация документов (День 21)

Фундамент RAG для агента «Визовый специалист»: локальный **пайплайн индексации** базы знаний —
документы → чанки → эмбеддинги → индекс с метаданными. Это **левая половина** RAG (построение «библиотеки»);
ответы агента через индекс — следующие дни.

> Полный конспект теории RAG — `.claude/WEEK_5_RAG.md`. Секция дня в корневом README — «День 21».

## Пайплайн

```
Документы (md/txt/pdf/kt)
   → Chunker (2 стратегии: fixed | structural)
   → Embedder (Ollama nomic-embed-text, 768; либо офлайн-фолбэк)
   → IndexStore (SQLite: вектор + метаданные)
   → RagSearch (косинусная близость, top-k)
```

## Архитектура (Clean Architecture)

**Домен `domain/rag/`** (чистый Kotlin, без HTTP/БД):
- `Rag.kt` — модели `RagDocument`, `Chunk`, `ChunkMetadata`, `IndexedChunk`, `Scored`, `IndexStats`;
  порты `Embedder`, `IndexStore`.
- `Chunker.kt` — интерфейс `Chunker` + `FixedSizeChunker` + `StructuralChunker`.
- `DocumentIndexer.kt` — сервис индексации, `VectorMath.cosine`, `RagSearch`.

**Данные `data/`**:
- `Embedders.kt` — `OllamaEmbedder` (`localhost:11434/api/embeddings`) + `HashingEmbedder` (офлайн-фолбэк).
- `SqliteIndexStore.kt` — реализация `IndexStore` на SQLite (вектор в BLOB).
- `DocumentLoader.kt` — чтение `.md/.txt/.pdf/.kt` → `RagDocument` (PDF через `PdfText.extractAll`).
- `KnowledgeIndex.kt` — координатор: сидинг корпуса, построение обеих стратегий, поиск.
- `SamplePdf.kt` — генерация образца PDF (памятка по Японии) для демонстрации ветки `pdf → текст`.

**UI `ui/`**: dev-панель «Индексация знаний (RAG)» (иконка-БД в композере, режим разработчика) — построить
индекс, таблица сравнения 2 стратегий, поиск top-3 по каждой.

## Корпус (визовая база знаний)

`src/main/resources/knowledge/` — 13 markdown-документов (~20 страниц, ≈37 тыс. символов) + образец PDF:
Шенген, США (B1/B2), Великобритания, ОАЭ, Китай, Канада, рабочие визы, студенческие визы, общий процесс,
биометрия/апелляции, страховка/фото, FAQ, чек-листы. Список — в `_manifest.txt`.

При первом запуске корпус засевается в `~/.adventai/rag/knowledge/` (метод `seedMissing` докладывает
недостающие файлы, не трогая добавленные пользователем). Туда можно класть свои `.md/.txt/.pdf/.kt`.

## Метаданные каждого чанка

`source` (файл) · `title` (документ) · `section` (хлебные крошки «H1 › H2») · `chunk_id`
(`<doc>#<strategy>#<ordinal>`) · плюс `strategy`, `ordinal`, `char_start/end`, `approx_tokens`.
Благодаря `section`/`source` ответ агента сможет **сослаться на источник**.

## Две стратегии chunking (и сравнение)

| | `fixed` | `structural` |
|---|---|---|
| Принцип | окно ~200 слов + overlap 30 | по markdown-заголовкам (раздел = чанк) |
| Границы | режет по словам, игнорит структуру | сохраняет разделы, до-режет по абзацам |
| `section` | пусто | заполнено (breadcrumbs) |
| Неструктурный PDF | равномерно | fallback: 1 чанк на документ |

**Замер (14 документов):** `fixed` → 34 чанка, ср. 1219 симв., **0 разделов**; `structural` → 102 чанка,
ср. 333 симв., **101 раздел**. Для визового агента побеждает `structural` — критично показывать источник.

## Хранилище (SQLite)

`~/.adventai/rag/index.db`:
- `rag_meta` — статистика на стратегию (embedder_id, dimension, counts, времена).
- `rag_chunk` — `chunk_id, source, title, section, ordinal, char_start/end, approx_tokens, text, embedding(BLOB)`.

Вектор хранится как сырые float32 little-endian. Поиск — brute-force косинус (для сотен чанков достаточно;
FAISS избыточен).

## Эмбеддер

- **Ollama `nomic-embed-text`** (768) — путь курса, локально/бесплатно. Требует `ollama serve` +
  `ollama pull nomic-embed-text`. Для nomic добавлены task-префиксы `search_document:` / `search_query:`
  (asymmetric retrieval — заметно улучшает различимость близостей).
- **`HashingEmbedder`** — офлайн-фолбэк (bag-of-words hashing), чтобы пайплайн/сравнение работали без сети.

## Как посмотреть (демо)

1. `.\gradlew.bat run` → Настройки → включить **режим разработчика**.
2. В композере — иконка-БД → панель «Индексация знаний (RAG)».
3. Тумблер эмбеддера (Ollama / фолбэк) → **«Построить индекс»** (прогресс по чанкам).
4. Смотрим **таблицу сравнения** 2 стратегий и **поиск**: top-3 по каждой стратегии с источником-разделом.

## Дальше (Дни 22+)

Агент берёт вопрос → ищет в индексе → подмешивает найденные чанки в промпт → отвечает **со ссылкой на
источник**. Плюс **reranking** (cross-encoder) для точности и метрики качества.
