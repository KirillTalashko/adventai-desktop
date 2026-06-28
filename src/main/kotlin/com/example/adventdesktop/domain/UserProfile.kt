package com.example.adventdesktop.domain

/** Локальный аккаунт (профиль-идентичность). Данные изолированы по [id]. */
data class Account(val id: String, val name: String)

/** Длина/детализация ответа. */
enum class ResponseLength(val title: String, val hint: String) {
    Concise("Кратко", "Отвечай кратко и по делу, без лишних слов."),
    Balanced("Сбалансированно", "Отвечай умеренно подробно."),
    Detailed("Подробно", "Отвечай развёрнуто, с пояснениями.")
}

/** Тон общения. */
enum class Tone(val title: String, val hint: String) {
    Friendly("Дружелюбный", "Тон дружелюбный и поддерживающий."),
    Formal("Деловой", "Тон деловой и нейтральный.")
}

/** Предпочтения по формату ответа. */
enum class FormatPref(val title: String, val hint: String) {
    Checklists("Чек-листы", "Пакет документов оформляй чек-листом."),
    StepByStep("Пошагово", "Давай инструкции по шагам."),
    NoFluff("Без воды", "Без вводных и воды — сразу суть."),
    Examples("С примерами", "Добавляй конкретные примеры.")
}

/**
 * Профиль пользователя (Day 12) — явные предпочтения: КАК отвечать. В отличие от памяти (ЧТО известно),
 * задаётся пользователем в онбординге и подмешивается в каждый запрос блоком `[ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ]`.
 */
data class UserProfile(
    val name: String = "",
    val about: String = "",
    val length: ResponseLength = ResponseLength.Balanced,
    val tone: Tone = Tone.Friendly,
    val formats: Set<FormatPref> = emptySet(),
    val constraints: String = "",
    val language: String = "Русский"
) {
    /** Текст для system prompt — инструкция, как отвечать этому пользователю. */
    fun toPromptBlock(): String {
        // В локальные переменные, т.к. внутри buildString `length` затеняется StringBuilder.length.
        val styleHint = "${length.hint} ${tone.hint}"
        val formatHints = formats.joinToString(" ") { it.hint }
        return buildString {
            if (name.isNotBlank()) append("Имя: ").append(name).append('\n')
            if (about.isNotBlank()) append("О пользователе: ").append(about).append('\n')
            append("Стиль: ").append(styleHint).append('\n')
            if (formats.isNotEmpty()) append("Формат: ").append(formatHints).append('\n')
            if (constraints.isNotBlank()) append("Учитывай: ").append(constraints).append('\n')
            append("Язык ответов: ").append(language)
        }.trim()
    }

    /**
     * Гражданство из свободного [about] — допустимый засев в [CaseFile.citizenship] на INTAKE, когда
     * пользователь не назвал его в самом диалоге. Гражданство — УСТОЙЧИВЫЙ признак личности (в отличие
     * от страны/дат ПОЕЗДКИ, для которых анти-бленд профиля сохраняется), поэтому профиль здесь —
     * легитимный источник. Распознаёт «гражданин/гражданство РФ/России», «российское гражданство»,
     * «гражданин <Страна>», «citizen of <Country>». Пусто — не нашли (тогда спросит интервьюер).
     */
    fun citizenship(): String {
        if (about.isBlank()) return ""
        val low = about.lowercase()
        // Доминирующая форма РФ (склонения учтены через \p{L}*, т.к. \w в Java по умолчанию ASCII-only).
        if (Regex("гражд\\p{L}*\\s+(?:рф|росси\\p{L}*)|росси\\p{L}*\\s+гражд\\p{L}*").containsMatchIn(low)) return "РФ"
        // Обобщённо: «гражданин/гражданка/гражданство <Страна>» (берём как назвал пользователь).
        Regex("(?:гражданин|гражданка|гражданство)\\s+([\\p{L}-]+)", RegexOption.IGNORE_CASE).find(about)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 && it.lowercase() !in NON_COUNTRY }?.let { return it }
        Regex("citizen(?:ship)?\\s+of\\s+([\\p{L}-]+)", RegexOption.IGNORE_CASE).find(about)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 }?.let { return it }
        return ""
    }

    /**
     * Факты о пользователе из профиля (ЧТО известно: имя, описание) — для разового засева в
     * долговременную память при онбординге. Стиль (длина/тон/формат/язык) сюда НЕ входит: это «КАК
     * отвечать» и живёт только в [toPromptBlock]. Каждый факт — одна строка (склеиваем многострочный
     * [about]), чтобы построчная дедупликация в памяти работала корректно.
     */
    fun factLines(): List<String> = buildList {
        if (name.isNotBlank()) add("Имя: ${name.trim()}")
        val aboutLine = about.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        if (aboutLine.isNotEmpty()) add(aboutLine)
    }

    private companion object {
        /** Слова после «гражданство…», которые НЕ являются страной (защита обобщённого матча от мусора). */
        val NON_COUNTRY = setOf("есть", "нету", "нет", "оформлено", "оформляю", "имеется", "второе", "двойное")
    }
}
