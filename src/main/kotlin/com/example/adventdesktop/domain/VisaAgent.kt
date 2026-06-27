package com.example.adventdesktop.domain

/** Системный промпт визового специалиста (единый источник — DRY). Совпадает с агентом в Android-проекте. */
const val VISA_SYSTEM_PROMPT: String =
    "Ты визовый специалист. Помогай пользователю разобраться с требованиями, документами, сроками, " +
        "рисками отказа и подготовкой к подаче. Не выдавай юридические гарантии, отмечай, когда нужно " +
        "проверить правила конкретной страны или обратиться к официальному источнику.\n\n" +
        "Когда перечисляешь пакет документов, оформляй его отдельным блоком ровно в таком формате " +
        "(одна строка — один документ, статус через точку с запятой, статусы только: нужен / загружен / проверен):\n" +
        "[checklist]\n- Загранпаспорт; нужен\n- Фото 35×45; нужен\n[/checklist]\n" +
        "ВАЖНО про статус: ты ВСЕГДА пиши «нужен», даже если пользователь говорит, что документ у него уже есть " +
        "(«есть» ≠ «загружен в приложение»). Статусы «загружен»/«проверен» проставляет только СИСТЕМА по факту " +
        "реально приложенного файла — он будет в [STATE] в разделе «Документы (приложены пользователем)». " +
        "Никогда не помечай документ загруженным со слов пользователя.\n" +
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
    private val guard: InvariantGuard? = null,
    systemPrompt: String = VISA_SYSTEM_PROMPT,
    windowSize: Int = 12
) {
    private val assembler = ContextAssembler(gateway, systemPrompt, windowSize)

    suspend fun ask(
        conversation: Conversation,
        working: WorkingMemory,
        longTerm: LongTermMemory,
        profile: UserProfile?,
        invariants: List<Invariant>,
        contextFill: Float
    ): Result<AgentTurn> = runCatching {
        val assembled = assembler.assemble(conversation, working, longTerm, profile, invariants, contextFill)
        var response = gateway.complete(assembled.messages)
        // Двойная защита (День 14): пост-проверка стража; при нарушении — перегенерация в обоснованный отказ.
        val violation = guard?.check(response.text, invariants)
        if (violation != null) {
            val fix = assembled.messages +
                Message(Role.Assistant, response.text) +
                Message(Role.User, "СТОП: твой предыдущий ответ нарушает инвариант — $violation. Перепиши ответ так, " +
                    "чтобы НЕ нарушать инвариант: корректно откажись, назови нарушаемый инвариант и кратко объясни " +
                    "причину; при возможности предложи допустимую законную альтернативу.")
            response = gateway.complete(fix)
        }
        AgentTurn(AgentReply(response.text, response.usage), assembled.derived)
    }
}
