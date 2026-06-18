package com.example.adventdesktop.domain

/** Роль реплики в OpenAI-совместимом формате. */
enum class Role(val wire: String) {
    System("system"),
    User("user"),
    Assistant("assistant")
}

/** Одна реплика диалога. У ответов агента есть расход токенов [usage]. */
data class Message(
    val role: Role,
    val text: String,
    val createdAtMs: Long = 0L,
    val usage: TokenUsage? = null
)

/** Производная (сжатая) память диалога: резюме и key-value факты со счётчиками покрытых сообщений. */
data class Derived(
    val summary: String = "",
    val summarizedCount: Int = 0,
    val facts: String = "",
    val factsCount: Int = 0
)

/** Диалог целиком (краткосрочная память + производная + метаданные). */
data class Conversation(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val messages: List<Message>,
    val derived: Derived = Derived()
) {
    fun withMessage(message: Message): Conversation = copy(messages = messages + message)
}

/** Лёгкое описание диалога для списка (без сообщений). */
data class ConversationMeta(
    val id: String,
    val title: String,
    val updatedAtMs: Long
)

/** Рабочая память: данные текущей задачи (per-диалог). */
data class WorkingMemory(
    val goal: String = "",
    val constraints: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = goal.isBlank() && constraints.isEmpty()
}

/** Долговременная память: профиль и решения (общие для агента, переживают сессии). */
data class LongTermMemory(
    val profile: String = "",
    val decisions: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = profile.isBlank() && decisions.isEmpty()
}

/** Порция новых фактов от фонового агента памяти: что добавить в рабочую/долговременную память. */
data class MemoryUpdate(
    val goal: String? = null,            // рабочая: цель задачи (если прояснилась)
    val constraints: List<String> = emptyList(),  // рабочая: ограничения
    val profile: List<String> = emptyList(),       // долговременная: факты о пользователе
    val decisions: List<String> = emptyList()      // долговременная: решения/договорённости
) {
    val isEmpty: Boolean
        get() = goal.isNullOrBlank() && constraints.isEmpty() && profile.isEmpty() && decisions.isEmpty()
}
