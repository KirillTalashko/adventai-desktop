package com.example.adventdesktop.data

import com.example.adventdesktop.domain.Conversation
import com.example.adventdesktop.domain.ConversationMeta
import com.example.adventdesktop.domain.Derived
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import com.example.adventdesktop.domain.TokenUsage
import com.example.adventdesktop.domain.WorkingMemory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Общий JSON-инстанс (DRY). */
internal val appJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun nowMs(): Long = System.currentTimeMillis()

@Serializable
internal data class UsageDto(val prompt: Int = 0, val completion: Int = 0, val total: Int = 0)

@Serializable
internal data class MessageDto(
    val role: String,
    val text: String,
    val createdAtMs: Long = 0L,
    val usage: UsageDto? = null
)

@Serializable
internal data class DerivedDto(
    val summary: String = "",
    val summarizedCount: Int = 0,
    val facts: String = "",
    val factsCount: Int = 0
)

@Serializable
internal data class ConversationDto(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val messages: List<MessageDto> = emptyList(),
    val derived: DerivedDto = DerivedDto()
)

@Serializable
internal data class ConversationMetaDto(val id: String, val title: String, val updatedAtMs: Long)

@Serializable
internal data class WorkingMemoryDto(val goal: String = "", val constraints: List<String> = emptyList())

@Serializable
internal data class AppConfigDto(
    val openrouterKey: String = "",
    val deepseekKey: String = "",
    val modelId: String = ""
)

// --- мапперы DTO <-> domain ---

internal fun UsageDto.toDomain() = TokenUsage(prompt, completion, total)
internal fun TokenUsage.toDto() = UsageDto(prompt, completion, total)

internal fun MessageDto.toDomain(): Message =
    Message(
        role = Role.entries.firstOrNull { it.wire == role } ?: Role.User,
        text = text,
        createdAtMs = createdAtMs,
        usage = usage?.toDomain()
    )

internal fun Message.toDto(): MessageDto = MessageDto(role.wire, text, createdAtMs, usage?.toDto())

internal fun DerivedDto.toDomain() = Derived(summary, summarizedCount, facts, factsCount)
internal fun Derived.toDto() = DerivedDto(summary, summarizedCount, facts, factsCount)

internal fun ConversationDto.toDomain(): Conversation =
    Conversation(id, title, createdAtMs, messages.map { it.toDomain() }, derived.toDomain())

internal fun Conversation.toDto(): ConversationDto =
    ConversationDto(id, title, createdAtMs, messages.map { it.toDto() }, derived.toDto())

internal fun ConversationMetaDto.toDomain() = ConversationMeta(id, title, updatedAtMs)

internal fun WorkingMemoryDto.toDomain() = WorkingMemory(goal, constraints)
internal fun WorkingMemory.toDto() = WorkingMemoryDto(goal, constraints)
