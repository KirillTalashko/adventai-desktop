package com.example.adventdesktop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/** Одна одобренная персонализация роли (День 20, prompt-tune): какая роль, что добавить, на основании чего. */
@Serializable
data class PromptOverride(
    val id: String,
    val role: String,
    val add: String,
    val reason: String = "",
    val appliedAtMs: Long = 0,
)

@Serializable
private data class PromptOverridesDto(val overrides: List<PromptOverride> = emptyList())

/**
 * Пер-аккаунт хранилище персонализации ролей (День 20). Перила: только АДДИТИВНЫЕ добавки (перило A),
 * пишутся лишь по явному действию пользователя (перило B), и весь набор можно очистить (перило D, «сбросить»).
 * Файл: `accounts/<id>/prompt-overrides.json`.
 */
class PromptOverrideStore(root: FileStore) {
    private val file = File(root.root, "prompt-overrides.json")

    fun load(): List<PromptOverride> = runCatching {
        if (file.exists()) appJson.decodeFromString<PromptOverridesDto>(file.readText()).overrides else emptyList()
    }.getOrDefault(emptyList())

    fun add(role: String, add: String, reason: String): PromptOverride {
        val entry = PromptOverride(
            id = "ov-${System.currentTimeMillis()}",
            role = role,
            add = add.trim(),
            reason = reason.trim(),
            appliedAtMs = System.currentTimeMillis(),
        )
        write(load() + entry)
        return entry
    }

    fun remove(id: String) = write(load().filterNot { it.id == id })

    fun clear() = write(emptyList())

    /** roleId → список добавок — для инъекции в [com.example.adventdesktop.domain.TaskOrchestrator]. */
    fun asMap(): Map<String, List<String>> = load().groupBy { it.role }.mapValues { (_, v) -> v.map { it.add } }

    private fun write(list: List<PromptOverride>) {
        file.parentFile?.mkdirs()
        file.writeText(appJson.encodeToString(PromptOverridesDto(list)))
    }
}
