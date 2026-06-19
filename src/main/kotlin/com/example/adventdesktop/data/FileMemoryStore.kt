package com.example.adventdesktop.data

import com.example.adventdesktop.domain.LongTermMemory
import com.example.adventdesktop.domain.MemoryStore
import com.example.adventdesktop.domain.WorkingMemory
import java.io.File

/**
 * Память в файлах:
 *  - долговременная (per-агент): `memory/profile.md` + `memory/decisions.md` (человекочитаемый markdown);
 *  - рабочая (per-диалог): `working/<conversationId>.json` (структурно).
 * Добавление дедуплицируется — фоновый агент памяти может слать уже известные факты.
 */
internal class FileMemoryStore(private val store: FileStore) : MemoryStore {
    private val memoryDir = store.dir("memory")
    private val profileFile = File(memoryDir, "profile.md")
    private val decisionsFile = File(memoryDir, "decisions.md")
    private val workingDir = store.dir("working")

    override fun loadLongTerm(): LongTermMemory =
        LongTermMemory(profile = store.readText(profileFile).trim(), decisions = loadDecisions())

    override fun appendProfile(line: String) {
        val clean = line.trim().removePrefix("-").trim()
        if (clean.isEmpty()) return
        val current = store.readText(profileFile).trim()
        val existing = current.lines().map { it.trim().removePrefix("-").trim().lowercase() }
        if (clean.lowercase() in existing) return
        val updated = if (current.isEmpty()) "# Профиль\n- $clean" else "$current\n- $clean"
        store.writeText(profileFile, updated)
    }

    override fun removeProfile(line: String) {
        val clean = line.trim().removePrefix("-").trim()
        if (clean.isEmpty()) return
        val current = store.readText(profileFile).trim()
        if (current.isEmpty()) return
        val kept = current.lines().filterNot { it.trim().removePrefix("-").trim().equals(clean, ignoreCase = true) }
        // Если фактов не осталось (только заголовок) — сбросить файл в «пустое» состояние.
        if (kept.any { it.trim().startsWith("-") }) store.writeText(profileFile, kept.joinToString("\n").trim())
        else store.delete(profileFile)
    }

    override fun addDecision(decision: String) {
        val clean = decision.trim().removePrefix("-").trim()
        if (clean.isEmpty()) return
        val list = loadDecisions()
        if (list.any { it.equals(clean, ignoreCase = true) }) return
        store.writeText(decisionsFile, "# Решения\n" + (list + clean).joinToString("\n") { "- $it" })
    }

    override fun clearLongTerm() {
        store.delete(profileFile)
        store.delete(decisionsFile)
    }

    override fun loadWorking(conversationId: String): WorkingMemory = runCatching {
        store.readText(workingFile(conversationId)).takeIf { it.isNotBlank() }
            ?.let { appJson.decodeFromString<WorkingMemoryDto>(it).toDomain() }
    }.getOrNull() ?: WorkingMemory()

    override fun setGoal(conversationId: String, goal: String) {
        if (goal.isBlank()) return
        saveWorking(conversationId, loadWorking(conversationId).copy(goal = goal.trim()))
    }

    override fun addConstraint(conversationId: String, constraint: String) {
        val clean = constraint.trim().removePrefix("-").trim()
        if (clean.isEmpty()) return
        val working = loadWorking(conversationId)
        if (working.constraints.any { it.equals(clean, ignoreCase = true) }) return
        saveWorking(conversationId, working.copy(constraints = working.constraints + clean))
    }

    override fun clearWorking(conversationId: String) = store.delete(workingFile(conversationId))

    private fun saveWorking(conversationId: String, memory: WorkingMemory) =
        store.writeText(workingFile(conversationId), appJson.encodeToString(memory.toDto()))

    private fun workingFile(conversationId: String) = File(workingDir, "$conversationId.json")

    private fun loadDecisions(): List<String> = store.readText(decisionsFile).lines()
        .mapNotNull { line ->
            val t = line.trim()
            if (t.startsWith("-")) t.removePrefix("-").trim().takeIf { it.isNotEmpty() } else null
        }
}
