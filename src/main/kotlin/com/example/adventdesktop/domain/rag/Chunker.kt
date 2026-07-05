package com.example.adventdesktop.domain.rag

/**
 * Стратегия нарезки документа на чанки (День 21). Две реализации сравниваются в задании:
 * [FixedSizeChunker] (по фиксированному размеру) и [StructuralChunker] (по структуре — заголовкам).
 */
interface Chunker {
    /** Идентификатор стратегии: `"fixed"` | `"structural"`. */
    val strategy: String
    fun chunk(doc: RagDocument): List<Chunk>
}

/** Слово с позицией в исходном тексте — режем по границам слов, чтобы не рвать их посередине. */
private data class Word(val start: Int, val end: Int)

private val WORD = Regex("\\S+")

private fun words(text: String): List<Word> =
    WORD.findAll(text).map { Word(it.range.first, it.range.last + 1) }.toList()

/** Грубая оценка токенов ≈ число слов (единый счёт для отчёта о размере чанков). */
internal fun approxTokens(text: String): Int = if (text.isBlank()) 0 else WORD.findAll(text).count()

/**
 * **Стратегия 1 — фиксированный размер.** Скользящее окно по [targetTokens] слов с перекрытием
 * [overlapTokens] (overlap «защищает границы» — см. конспект недели). Режет по границам СЛОВ, но
 * игнорирует границы предложений/разделов — отсюда классический минус: мысль может разорваться между
 * соседними чанками (`section` пустой, структура не сохраняется). Ровно это и сравниваем со structural.
 */
class FixedSizeChunker(
    private val targetTokens: Int = 200,
    private val overlapTokens: Int = 30,
) : Chunker {
    override val strategy = "fixed"

    override fun chunk(doc: RagDocument): List<Chunk> {
        val ws = words(doc.text)
        if (ws.isEmpty()) return emptyList()
        val step = (targetTokens - overlapTokens).coerceAtLeast(1)
        val out = ArrayList<Chunk>()
        var start = 0
        var ordinal = 0
        while (start < ws.size) {
            val end = (start + targetTokens).coerceAtMost(ws.size)
            val charStart = ws[start].start
            val charEnd = ws[end - 1].end
            val text = doc.text.substring(charStart, charEnd).trim()
            if (text.isNotEmpty()) {
                out += Chunk(
                    text,
                    ChunkMetadata(
                        source = doc.source, title = doc.title, section = "",
                        chunkId = "${doc.id}#fixed#$ordinal", strategy = "fixed", ordinal = ordinal,
                        charStart = charStart, charEnd = charEnd, approxTokens = end - start,
                    ),
                )
                ordinal++
            }
            if (end >= ws.size) break
            start += step
        }
        return out
    }
}

/**
 * **Стратегия 2 — по структуре.** Режем по markdown-заголовкам (`#`..`######`): раздел = чанк, а
 * `section` = хлебные крошки «H1 › H2 › H3» (сохраняем провенанс раздела). Каждый чанк — законченная мысль.
 * Слишком крупный раздел (> [maxTokens]) до-режем по абзацам (пустая строка), НЕ разрывая внутри абзаца.
 * Текст без заголовков (обычный txt/PDF) → один раздел на документ (fallback), `section` = заголовок доку.
 */
class StructuralChunker(
    private val maxTokens: Int = 320,
) : Chunker {
    override val strategy = "structural"

    private val heading = Regex("^(#{1,6})\\s+(.*)$")

    private data class Section(val breadcrumb: String, val start: Int, val end: Int)

    override fun chunk(doc: RagDocument): List<Chunk> {
        val sections = splitSections(doc)
        val out = ArrayList<Chunk>()
        var ordinal = 0
        for (sec in sections) {
            val body = doc.text.substring(sec.start, sec.end).trim()
            if (body.isEmpty()) continue
            val pieces = if (approxTokens(body) <= maxTokens) listOf(sec.start to sec.end)
            else packParagraphs(doc.text, sec.start, sec.end)
            for ((ps, pe) in pieces) {
                val text = doc.text.substring(ps, pe).trim()
                if (text.isEmpty()) continue
                out += Chunk(
                    text,
                    ChunkMetadata(
                        source = doc.source, title = doc.title, section = sec.breadcrumb,
                        chunkId = "${doc.id}#structural#$ordinal", strategy = "structural", ordinal = ordinal,
                        charStart = ps, charEnd = pe, approxTokens = approxTokens(text),
                    ),
                )
                ordinal++
            }
        }
        return out
    }

    /** Разбить документ на разделы по заголовкам; вернуть диапазоны ТЕЛА раздела (без строки заголовка). */
    private fun splitSections(doc: RagDocument): List<Section> {
        val stack = ArrayList<Pair<Int, String>>()   // (level, text)
        val sections = ArrayList<Section>()
        var offset = 0
        var bodyStart = 0
        var crumb = ""
        val lines = doc.text.split("\n")
        for ((i, line) in lines.withIndex()) {
            val lineStart = offset
            val m = heading.find(line.trimEnd('\r'))
            if (m != null) {
                // Закрыть текущий раздел (тело до начала этой строки-заголовка).
                if (lineStart > bodyStart) sections += Section(crumb, bodyStart, lineStart)
                val level = m.groupValues[1].length
                val title = m.groupValues[2].trim()
                while (stack.isNotEmpty() && stack.last().first >= level) stack.removeAt(stack.size - 1)
                stack += level to title
                crumb = stack.joinToString(" › ") { it.second }
                bodyStart = lineStart + line.length + 1   // тело раздела — после строки заголовка
            }
            offset += line.length + 1
            if (i == lines.size - 1 && offset > bodyStart) {
                sections += Section(crumb, bodyStart, doc.text.length)
            }
        }
        // Документ без заголовков → один раздел на весь текст (section = "" → покажем заголовок доку).
        if (sections.isEmpty()) sections += Section("", 0, doc.text.length)
        return sections
    }

    /** Жадно упаковать абзацы раздела в куски ≤ [maxTokens] слов, не разрывая абзац. */
    private fun packParagraphs(text: String, from: Int, to: Int): List<Pair<Int, Int>> {
        val paraSep = Regex("\\n[ \\t]*\\n")
        val out = ArrayList<Pair<Int, Int>>()
        var chunkStart = from
        var chunkTokens = 0
        var cursor = from
        val body = text.substring(from, to)
        val breaks = paraSep.findAll(body).map { from + it.range.first to from + it.range.last + 1 }.toList()
        val paras = ArrayList<Pair<Int, Int>>()
        for ((bs, be) in breaks) { paras += cursor to bs; cursor = be }
        paras += cursor to to
        for ((ps, pe) in paras) {
            val t = approxTokens(text.substring(ps, pe))
            if (chunkTokens > 0 && chunkTokens + t > maxTokens) {
                out += chunkStart to ps
                chunkStart = ps
                chunkTokens = 0
            }
            chunkTokens += t
        }
        out += chunkStart to to
        return out
    }
}

private val HEADING_RE = Regex("^(#{1,6})\\s+(.*)$")

/** Разбить документ на разделы по markdown-заголовкам: (хлебные крошки, начало тела, конец тела). */
internal fun headingSections(doc: RagDocument): List<Triple<String, Int, Int>> {
    val stack = ArrayList<Pair<Int, String>>()
    val out = ArrayList<Triple<String, Int, Int>>()
    var offset = 0
    var bodyStart = 0
    var crumb = ""
    val lines = doc.text.split("\n")
    for ((i, line) in lines.withIndex()) {
        val lineStart = offset
        val m = HEADING_RE.find(line.trimEnd('\r'))
        if (m != null) {
            if (lineStart > bodyStart) out += Triple(crumb, bodyStart, lineStart)
            val level = m.groupValues[1].length
            val title = m.groupValues[2].trim()
            while (stack.isNotEmpty() && stack.last().first >= level) stack.removeAt(stack.size - 1)
            stack += level to title
            crumb = stack.joinToString(" › ") { it.second }
            bodyStart = lineStart + line.length + 1
        }
        offset += line.length + 1
        if (i == lines.size - 1 && offset > bodyStart) out += Triple(crumb, bodyStart, doc.text.length)
    }
    if (out.isEmpty()) out += Triple("", 0, doc.text.length)
    return out
}

/**
 * **Стратегия 3 — `contextual` (боевая, по умолчанию для ответов, День 23).** Лучшее из structural+fixed +
 * «contextual retrieval»:
 * - границы по заголовкам (никогда не режем поперёк раздела) — как structural;
 * - внутри крупного раздела — окно [targetTokens] слов с перекрытием [overlapTokens] (бэлансируем размер);
 * - **в ТЕКСТ каждого чанка добавляем хлебные крошки «документ › раздел»** — чанк эмбеддится ВМЕСТЕ с темой,
 *   поэтому поиск понимает, о чём кусок (вытаскивает нужный документ даже на разговорном запросе). Метаданные
 *   `section` — те же крошки для показа источника.
 */
class ContextualChunker(
    private val targetTokens: Int = 180,
    private val overlapTokens: Int = 40,
) : Chunker {
    override val strategy = "contextual"

    override fun chunk(doc: RagDocument): List<Chunk> {
        val out = ArrayList<Chunk>()
        var ordinal = 0
        for ((crumb, s, e) in headingSections(doc)) {
            val body = doc.text.substring(s, e)
            if (body.isBlank()) continue
            val header = crumb.ifBlank { doc.title }
            for ((cs, ce) in windows(body)) {
                val raw = body.substring(cs, ce).trim()
                if (raw.isEmpty()) continue
                out += Chunk(
                    "$header\n$raw",   // контекстный префикс: тема раздела эмбеддится вместе с текстом
                    ChunkMetadata(
                        source = doc.source, title = doc.title, section = crumb,
                        chunkId = "${doc.id}#contextual#$ordinal", strategy = "contextual", ordinal = ordinal,
                        charStart = s + cs, charEnd = s + ce, approxTokens = approxTokens(raw),
                    ),
                )
                ordinal++
            }
        }
        return out
    }

    /** Окна по словам с перекрытием в пределах ОДНОГО раздела (не пересекая заголовки). Смещения — в теле. */
    private fun windows(body: String): List<Pair<Int, Int>> {
        val ws = words(body)
        if (ws.isEmpty()) return emptyList()
        val step = (targetTokens - overlapTokens).coerceAtLeast(1)
        val out = ArrayList<Pair<Int, Int>>()
        var start = 0
        while (start < ws.size) {
            val end = (start + targetTokens).coerceAtMost(ws.size)
            out += ws[start].start to ws[end - 1].end
            if (end >= ws.size) break
            start += step
        }
        return out
    }
}
