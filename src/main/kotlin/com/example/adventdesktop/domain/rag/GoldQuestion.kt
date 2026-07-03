package com.example.adventdesktop.domain.rag

/**
 * Контрольный вопрос для оценки RAG (День 22, «усиление»): вопрос + ожидание (что должно быть в ответе) +
 * какие источники должны использоваться. `sources` пустой → **негативный контроль** (ответа в базе нет,
 * агент обязан честно это сказать, а не выдумать).
 */
data class GoldQuestion(
    val id: Int,
    val question: String,
    val expect: String,
    val sources: List<String> = emptyList(),
) {
    val isNegative: Boolean get() = sources.isEmpty()
}

/**
 * Ответы на контрольный вопрос в обоих режимах + авто-оценка (День 22, Вариант B). Судим простыми
 * признаками, без отдельной модели-судьи:
 * - [ragOnTarget] (позитивный): опёрся ли ответ С RAG на ОЖИДАЕМЫЙ источник;
 * - [ragRefused]/[plainRefused] (для вопроса-ловушки): честно ли отказался режим или выдумал.
 * Это и есть «сравнение качества»: с RAG — ответ со ссылками и отказ на ловушке; без RAG — без ссылок и выдумка.
 */
data class GoldAnswer(
    val q: GoldQuestion,
    val rag: RagAnswer,
    val plain: RagAnswer,
) {
    val ragHasSources: Boolean get() = rag.sources.isNotEmpty()

    /** Позитивный вопрос: среди использованных RAG-источников есть хотя бы один ожидаемый. null — ловушка. */
    val ragOnTarget: Boolean? get() =
        if (q.isNegative) null
        else q.sources.any { exp -> rag.sources.any { it.source.equals(exp, ignoreCase = true) } }

    val ragRefused: Boolean get() = ragLooksLikeRefusal(rag.text)
    val plainRefused: Boolean get() = ragLooksLikeRefusal(plain.text)
}
