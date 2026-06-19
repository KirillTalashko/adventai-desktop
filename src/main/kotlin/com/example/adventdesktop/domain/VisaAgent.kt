package com.example.adventdesktop.domain

/** Системный промпт визового специалиста (единый источник — DRY). Совпадает с агентом в Android-проекте. */
const val VISA_SYSTEM_PROMPT: String =
    "Ты визовый специалист. Помогай пользователю разобраться с требованиями, документами, сроками, " +
        "рисками отказа и подготовкой к подаче. Не выдавай юридические гарантии, отмечай, когда нужно " +
        "проверить правила конкретной страны или обратиться к официальному источнику.\n\n" +
        "Когда перечисляешь пакет документов, оформляй его отдельным блоком ровно в таком формате " +
        "(одна строка — один документ, статус через точку с запятой, статусы только: нужен / загружен / проверен):\n" +
        "[checklist]\n- Загранпаспорт; нужен\n- Фото 35×45; нужен\n[/checklist]\n" +
        "Остальной текст пиши обычным образом до или после блока."

/** Ответ агента для presentation-слоя. */
data class AgentReply(val text: String, val usage: TokenUsage?)

/** Результат хода: ответ + обновлённая производная память (резюме) для сохранения. */
data class AgentTurn(val reply: AgentReply, val derived: Derived)

/**
 * Доменная сущность агента: единый под-капотом конвейер памяти ([ContextAssembler]) + обращение к
 * [LlmGateway]. Управление контекстом автоматическое (без режимов): слои памяти + авто-резюме при
 * заполнении окна. Не знает ни про UI, ни про HTTP, ни про файлы (Clean Architecture).
 */
class VisaAgent(
    private val gateway: LlmGateway,
    systemPrompt: String = VISA_SYSTEM_PROMPT,
    windowSize: Int = 12
) {
    private val assembler = ContextAssembler(gateway, systemPrompt, windowSize)

    suspend fun ask(
        conversation: Conversation,
        working: WorkingMemory,
        longTerm: LongTermMemory,
        profile: UserProfile?,
        contextFill: Float
    ): Result<AgentTurn> = runCatching {
        val assembled = assembler.assemble(conversation, working, longTerm, profile, contextFill)
        val response = gateway.complete(assembled.messages)
        AgentTurn(AgentReply(response.text, response.usage), assembled.derived)
    }
}
