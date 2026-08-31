package com.example.adventdesktop.data

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import java.io.File

/**
 * Генератор образца **PDF** для корпуса RAG (День 21): демонстрирует ветку `pdf → текст` (через
 * [PdfText.extractAll]) на реальном PDF, не таща бинарник в репозиторий. Создаётся один раз при сидинге
 * корпуса. Для кириллицы встраивает системный TrueType-шрифт (Arial/Segoe/DejaVu); если ни один не найден
 * — падать нельзя, поэтому пишем латиницей стандартным шрифтом Helvetica (демо ветки всё равно работает).
 */
object SamplePdf {

    private val systemFonts = listOf(
        "C:/Windows/Fonts/arial.ttf",
        "C:/Windows/Fonts/segoeui.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/Library/Fonts/Arial.ttf",
    )

    fun writeJapanMemo(target: File) {
        val cyrillic = mutableListOf(
            H to "Виза в Японию — краткая памятка",
            P to "Справочный материал для консультаций. Требования и сборы меняются — итоговые условия сверяйте на сайте посольства Японии и у аккредитованного визового центра на дату подачи.",
            H to "Типы виз",
            P to "Для краткосрочных поездок оформляется однократная туристическая виза (обычно пребывание до 15, 30 или 90 дней) либо многократная виза для частых поездок. Долгосрочные визы (работа, учёба) оформляются отдельно и требуют сертификата соответствия (COE).",
            H to "Базовый пакет документов",
            P to "Заграничный паспорт, действительный на весь срок поездки; визовая анкета с фотографией; подтверждение маршрута (даты въезда и выезда); бронь проживания на все ночи; подтверждение финансовой состоятельности (выписка со счёта); справка с работы с указанием должности и оклада.",
            P to "Для туристической визы часто требуется программа поездки по дням (itinerary) и подтверждение авиабилетов туда-обратно. При спонсорстве принимающей стороной прикладывается гарантийное письмо и документы приглашающего.",
            H to "Финансовые гарантии",
            P to "Консульство оценивает, реалистична ли поездка для дохода заявителя. Показывают выписку со счёта за последние месяцы с движением средств и справку о доходах. Резкое пополнение счёта перед подачей без объяснения вызывает подозрение.",
            H to "Сроки и сборы",
            P to "Стандартный срок рассмотрения — около 5–7 рабочих дней после подачи полного пакета, в высокий сезон дольше. Консульский сбор зависит от кратности визы (однократная/многократная); добавляется сервисный сбор визового центра.",
            H to "Частые причины отказа",
            P to "Недостаточные или неубедительные финансовые гарантии; противоречия между маршрутом, бронями и билетами; сомнения в намерении вернуться на родину; неполный или неверно оформленный пакет документов. При отказе можно подать повторно, устранив причину.",
            H to "Намерение вернуться",
            P to "Как и для других стран, важны «якоря» возврата: стабильная работа или бизнес, семья, имущество, продолжающееся обучение. Чем сильнее связи с родиной, тем ниже иммиграционные риски в глазах офицера.",
        )
        runCatching { render(target, cyrillic) }.onFailure {
            // Фолбэк: латиница стандартным шрифтом (если системный TTF не найден/не читается).
            render(target, latinFallback(), forceStandard = true)
        }
    }

    private const val H = true   // heading
    private const val P = false  // paragraph

    private fun render(target: File, blocks: List<Pair<Boolean, String>>, forceStandard: Boolean = false) {
        PDDocument().use { doc ->
            val font: PDFont = if (forceStandard) PDType1Font.HELVETICA
            else systemFonts.firstNotNullOfOrNull { p ->
                val f = File(p)
                if (f.exists()) runCatching { PDType0Font.load(doc, f) }.getOrNull() else null
            } ?: PDType1Font.HELVETICA

            val margin = 56f
            val page0 = PDPage(PDRectangle.A4)
            doc.addPage(page0)
            val width = PDRectangle.A4.width - 2 * margin
            var stream = PDPageContentStream(doc, page0)
            var y = PDRectangle.A4.height - margin

            fun newPage() {
                stream.close()
                val p = PDPage(PDRectangle.A4)
                doc.addPage(p)
                stream = PDPageContentStream(doc, p)
                y = PDRectangle.A4.height - margin
            }

            for ((isHeading, text) in blocks) {
                val size = if (isHeading) 15f else 11f
                val leading = if (isHeading) 22f else 16f
                if (isHeading) y -= 8f
                for (line in wrap(text, font, size, width)) {
                    if (y < margin + leading) newPage()
                    stream.beginText()
                    stream.setFont(font, size)
                    stream.newLineAtOffset(margin, y)
                    stream.showText(line)
                    stream.endText()
                    y -= leading
                }
                y -= 6f
            }
            stream.close()
            target.parentFile?.mkdirs()
            doc.save(target)
        }
    }

    /** Разбить абзац на строки по ширине страницы (по границам слов). */
    private fun wrap(text: String, font: PDFont, size: Float, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = ArrayList<String>()
        var line = StringBuilder()
        for (w in words) {
            val candidate = if (line.isEmpty()) w else "$line $w"
            val width = font.getStringWidth(candidate) / 1000f * size
            if (width > maxWidth && line.isNotEmpty()) {
                lines += line.toString()
                line = StringBuilder(w)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    private fun latinFallback(): List<Pair<Boolean, String>> = listOf(
        H to "Japan Visa - Quick Guide",
        P to "Reference material for consultations. Requirements and fees change - verify with the Embassy of Japan and an accredited visa center before applying.",
        H to "Visa types",
        P to "For short trips a single-entry tourist visa is issued (typically 15, 30 or 90 days of stay) or a multiple-entry visa for frequent travel. Long-term visas (work, study) require a Certificate of Eligibility.",
        H to "Core documents",
        P to "Valid passport; application form with photo; travel itinerary with entry and exit dates; accommodation booking; proof of funds (bank statement); employment letter with position and salary.",
        H to "Processing and fees",
        P to "Standard processing takes about 5-7 business days after a complete package is submitted. The consular fee depends on entries; a service fee is added by the visa center.",
        H to "Common refusal reasons",
        P to "Insufficient funds; contradictions between itinerary, bookings and tickets; doubts about intent to return; incomplete documents. After a refusal you may reapply once the cause is fixed.",
    )
}
