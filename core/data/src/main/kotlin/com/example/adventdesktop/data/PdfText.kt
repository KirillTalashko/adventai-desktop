package com.example.adventdesktop.data

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * Извлечение текста из PDF (День 20) — для навыка `docs check`: чтобы агент мог сверить, на ОДНОГО ли
 * заявителя оформлены документы (ФИО/даты), а не просто принять любой приложенный файл. Берём первые
 * страницы (там обычно ФИО). Скан без текстового слоя → честно сообщаем, что извлечь не удалось.
 */
object PdfText {
    fun extract(file: File, maxChars: Int = 800): String {
        if (!file.name.endsWith(".pdf", ignoreCase = true)) return "(не PDF — содержимое не читаю)"
        return runCatching {
            PDDocument.load(file).use { doc ->
                val stripper = PDFTextStripper().apply { startPage = 1; endPage = 2 }
                stripper.getText(doc).replace(Regex("\\s+"), " ").trim().take(maxChars)
            }.ifBlank { "(текст не извлечён — вероятно скан/изображение, проверьте вручную)" }
        }.getOrElse { "(ошибка чтения PDF: ${it.message})" }
    }

    /**
     * Полный текст PDF (День 21, RAG-индексация): все страницы, без усечения. Абзацы сохраняем (не
     * схлопываем все пробелы, как в [extract]) — это важно для structural-chunking по абзацам. Скан без
     * текстового слоя → пустая строка (такой файл в индекс не попадёт).
     */
    fun extractAll(file: File): String = runCatching {
        PDDocument.load(file).use { doc ->
            PDFTextStripper().getText(doc)
                .replace("\r\n", "\n")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }
    }.getOrElse { "" }
}
