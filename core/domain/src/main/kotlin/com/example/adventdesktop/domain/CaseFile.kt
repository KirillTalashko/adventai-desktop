package com.example.adventdesktop.domain

/**
 * Досье кейса (День 18, «оркестр») — единый СТРУКТУРНЫЙ источник фактов задачи, от которого играют все
 * стадии. Заполняется интервьюером ИЗ СЛОВ ПОЛЬЗОВАТЕЛЯ (не из профиля/фона), рендерится в [STATE] и
 * читается планировщиком/исполнителем/валидатором/ассистентом. Пустое поле = факт ещё не выяснен.
 *
 * Готовность к плану решает КОД ([missingEssentials]) — адаптивно по цели, а не флака́-тег LLM. Это убирает
 * плавающий порог «спросить vs план» и подмешивание фона.
 */
data class CaseFile(
    val destination: String = "",   // страна назначения
    val citizenship: String = "",   // гражданство
    val purpose: String = "",       // цель: туризм / работа / учёба / …
    val timeframe: String = "",     // даты или ориентир: «5–15 авг 2026», «осень 2026», «гибкие»
    val travelers: String = "",     // кто едет: «1», «я + жена + 2 детей (5, 12)»
    val employment: String = "",    // занятость/доход (фин. гарантии)
    val history: String = "",       // прошлые отказы / визовая история
    val city: String = "",          // город проживания (консульский округ / визовый центр)
) {
    val isEmpty: Boolean
        get() = listOf(destination, citizenship, purpose, timeframe, travelers, employment, history, city).none { has(it) }

    /** Долгосрочная виза (работа/учёба/ВНЖ) — нужен расширенный минимум фактов. */
    private val isLongStay: Boolean
        get() = purpose.lowercase().let { p -> LONG_STAY.any { p.contains(it) } }

    /** Чего не хватает для КАЧЕСТВЕННОГО плана (адаптивно по цели). Пусто = можно строить план. */
    fun missingEssentials(): List<String> = buildList {
        if (!has(destination)) add("страна назначения")
        if (!has(citizenship)) add("гражданство")
        if (!has(purpose)) add("цель поездки")
        if (!has(timeframe)) add("ориентир по датам поездки")
        if (isLongStay && !has(employment)) add("занятость/квалификация (оффер, диплом)")
    }

    val isReadyForPlan: Boolean get() = missingEssentials().isEmpty()

    /** Сменились ли страна ИЛИ цель относительно [old] — признак РАЗВОРОТА (нормализованное сравнение). */
    fun isPivotFrom(old: CaseFile): Boolean {
        fun n(s: String) = s.trim().lowercase()
        val destChanged = has(destination) && has(old.destination) && n(destination) != n(old.destination)
        val purpChanged = has(purpose) && has(old.purpose) && n(purpose) != n(old.purpose)
        return destChanged || purpChanged
    }

    /** Слить новое поверх старого: непустое новое перекрывает; пустое/«не указано» НЕ затирает известное. */
    fun merge(other: CaseFile): CaseFile = CaseFile(
        destination = pick(destination, other.destination),
        citizenship = pick(citizenship, other.citizenship),
        purpose = pick(purpose, other.purpose),
        timeframe = pick(timeframe, other.timeframe),
        travelers = pick(travelers, other.travelers),
        employment = pick(employment, other.employment),
        history = pick(history, other.history),
        city = pick(city, other.city),
    )

    /** Блок досье для инжекта в [STATE] — только заполненные поля. Пусто, если ничего не выяснено. */
    fun renderBlock(): String {
        val rows = buildList {
            row("Страна", destination)?.let { add(it) }
            row("Гражданство", citizenship)?.let { add(it) }
            row("Цель", purpose)?.let { add(it) }
            row("Даты/срок", timeframe)?.let { add(it) }
            row("Кто едет", travelers)?.let { add(it) }
            row("Занятость/доход", employment)?.let { add(it) }
            row("Прошлые отказы/история", history)?.let { add(it) }
            row("Город проживания", city)?.let { add(it) }
        }
        if (rows.isEmpty()) return ""
        return "[ДОСЬЕ КЕЙСА — подтверждённые факты от пользователя; ЕДИНСТВЕННЫЙ источник фактов для всех стадий]\n" +
            rows.joinToString("\n") + "\n[/ДОСЬЕ]"
    }

    private fun row(label: String, value: String): String? =
        value.trim().takeIf { has(it) }?.let { "- $label: $it" }

    private companion object {
        val LONG_STAY = listOf("работ", "учеб", "учёб", "студен", "внж", "пмж", "blue card", "голуб", "воссоедин", "national", "тип d", "(d)")

        /** Значение «есть и осмысленно» (не пусто, не «не указано/—/плейсхолдер»). */
        fun has(s: String): Boolean {
            val t = s.trim().lowercase()
            if (t.isEmpty() || t == "—" || t == "-" || t == "…" || t == "..." || t == "нет" || t.startsWith("<")) return false
            return !(t.startsWith("не указан") || t.startsWith("не уточн") || t.startsWith("неизвест") || t.startsWith("не выяс"))
        }

        fun pick(old: String, new: String): String = if (has(new)) new.trim() else old
    }
}
