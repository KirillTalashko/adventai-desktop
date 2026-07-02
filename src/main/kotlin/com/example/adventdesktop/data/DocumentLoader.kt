package com.example.adventdesktop.data

import com.example.adventdesktop.domain.rag.RagDocument
import java.io.File

/**
 * Загрузчик корпуса для RAG (День 21): читает файлы папки знаний в [RagDocument] (README, статьи, код, pdf
 * → текст). Поддержка: `.md`, `.txt`, `.markdown`, `.kt`, `.pdf` (через [PdfText.extractAll]). Заголовок
 * документа — первый markdown-`#` или имя файла; `id` — имя файла без расширения; `source` — имя файла.
 */
object DocumentLoader {

    private val textExt = setOf("md", "markdown", "txt", "kt", "kts", "java", "py")

    /** Прочитать все поддерживаемые файлы каталога (рекурсивно) в документы. Пустые/нечитаемые пропускаем. */
    fun loadDir(dir: File): List<RagDocument> {
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.path }
            .mapNotNull { load(it, dir) }
            .toList()
    }

    fun load(file: File, base: File? = null): RagDocument? {
        val ext = file.extension.lowercase()
        val text = when {
            ext == "pdf" -> PdfText.extractAll(file)
            ext in textExt -> runCatching { file.readText() }.getOrDefault("")
            else -> return null
        }.trim()
        if (text.isBlank()) return null
        val source = base?.let { file.relativeTo(it).path.replace('\\', '/') } ?: file.name
        return RagDocument(id = file.nameWithoutExtension, title = titleOf(file, text), source = source, text = text)
    }

    /** Заголовок = первый markdown-заголовок первого уровня, иначе имя файла. */
    private fun titleOf(file: File, text: String): String {
        val h1 = Regex("^#\\s+(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
        return h1?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
    }
}
