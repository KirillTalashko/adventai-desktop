package com.example.adventdesktop.domain

import com.example.adventdesktop.domain.rag.KnowledgeHit

/** Ответ ассистента разработчика: текст, файлы-источники, живая git-ветка и признак «ответ опирался на доки». */
data class DevHelpAnswer(
    val text: String,
    val sources: List<String>,
    val branch: String?,
    val usage: TokenUsage?,
    val grounded: Boolean,
)

/**
 * Ассистент разработчика (День 31) — отвечает на вопросы **об этом проекте**.
 *
 * Складывает три источника контекста:
 *  1. **RAG по докам проекта** ([retrieveDocs]) — README, markdown из `.claude`, схемы данных;
 *  2. **живой контекст проекта через MCP** ([gitContext]) — текущая git-ветка;
 *  3. **LLM** ([gateway]) — формулирует ответ строго по найденным фрагментам.
 *
 * Домен не знает ни про SQLite/эмбеддеры, ни про MCP SDK — только лямбды-порты (Clean Architecture).
 *
 * **Анти-галлюцинации:** если retrieval пуст, LLM не вызывается вовсе — возвращаем честное «в документации
 * проекта этого нет». Так ответ невозможно спутать с общими знаниями модели.
 */
class DevAssistant(
    private val gateway: LlmGateway,
    private val retrieveDocs: suspend (String) -> List<KnowledgeHit>,
    private val gitContext: suspend () -> String?,
) {

    suspend fun help(question: String): DevHelpAnswer {
        val q = question.trim()
        // Git-контекст — best-effort: MCP может быть недоступен, ассистент обязан продолжать работать.
        val branch = runCatchingCancellable { gitContext() }.getOrNull()?.trim()?.ifBlank { null }
        // Сбой ретривера (не поднята Ollama, битый индекс) НЕ глушим: иначе инфраструктурная авария
        // превращается в уверенное «в документации этого нет» — то есть в ложное утверждение о корпусе.
        // Пусть исключение дойдёт до UI и покажется как ошибка. Пустой результат = честно «не нашёл».
        val hits = retrieveDocs(q)

        if (hits.isEmpty()) {
            return DevHelpAnswer(
                text = NOT_IN_DOCS,
                sources = emptyList(),
                branch = branch,
                usage = null,
                grounded = false,
            )
        }

        val context = hits.mapIndexed { i, h ->
            val where = listOfNotNull(h.source, h.section.takeIf { it.isNotBlank() }).joinToString(" › ")
            "[${i + 1}] $where\n${h.text.trim()}"
        }.joinToString("\n\n")

        val system = buildString {
            appendLine(SYSTEM_PROMPT)
            if (branch != null) {
                appendLine()
                appendLine("Текущая git-ветка проекта (получена вживую через MCP): $branch")
                appendLine("Если спрашивают про ветку/состояние репозитория — назови её явно.")
            }
        }.trim()

        val user = buildString {
            appendLine("ФРАГМЕНТЫ ДОКУМЕНТАЦИИ ПРОЕКТА:")
            appendLine(context)
            appendLine()
            append("ВОПРОС РАЗРАБОТЧИКА: ").append(q)
        }

        val resp = gateway.complete(
            messages = listOf(Message(Role.System, system), Message(Role.User, user)),
            params = LlmParams(temperature = 0.2),
        )

        return DevHelpAnswer(
            text = resp.text.trim(),
            sources = hits.map { it.source }.distinct(),
            branch = branch,
            usage = resp.usage,
            grounded = true,
        )
    }

    private companion object {
        const val NOT_IN_DOCS =
            "В документации проекта я этого не нашёл — отвечать общими знаниями не буду, чтобы не выдумать.\n" +
                "Попробуйте переформулировать вопрос или спросите про то, что описано в README / `.claude/*.md` " +
                "(архитектура, память, контекст, MCP, RAG, инварианты, конвенции)."

        val SYSTEM_PROMPT = """
            Ты — ассистент разработчика КОНКРЕТНОГО проекта: desktop-приложение «Визовый специалист»
            (Kotlin/JVM + Compose for Desktop, Clean Architecture).

            Правила ответа:
            1. Отвечай ТОЛЬКО по приведённым фрагментам документации проекта. Не додумывай и не подменяй их
               общими знаниями о Kotlin/Compose — если во фрагментах ответа нет, так и скажи.
            2. Отвечай по-русски, кратко и по делу: разработчику нужен факт, а не пересказ.
            3. Где уместно — называй конкретные файлы, классы и пути из фрагментов (например `ui/ChatState.kt`,
               `~/.adventai/config.json`).
            4. Не выдумывай имена файлов, классов и настроек, которых нет во фрагментах.
            5. Не перечисляй источники в конце — их подставит приложение само.
        """.trimIndent()
    }
}
