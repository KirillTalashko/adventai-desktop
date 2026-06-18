package com.example.adventdesktop.data

import com.example.adventdesktop.domain.Conversation
import com.example.adventdesktop.domain.ConversationMeta
import com.example.adventdesktop.domain.ConversationRepository
import java.io.File
import kotlin.random.Random

/** Диалоги в файлах: `conversations/<id>.json` + `conversations/index.json` (персист между сессиями). */
internal class FileConversationRepository(private val store: FileStore) : ConversationRepository {
    private val dir = store.dir("conversations")
    private val indexFile = File(dir, "index.json")

    override fun listMetas(): List<ConversationMeta> =
        loadIndex().map { it.toDomain() }.sortedByDescending { it.updatedAtMs }

    override fun load(id: String): Conversation? {
        val file = File(dir, "$id.json")
        if (!store.exists(file)) return null
        return runCatching { appJson.decodeFromString<ConversationDto>(store.readText(file)).toDomain() }.getOrNull()
    }

    override fun latest(): Conversation? = listMetas().firstOrNull()?.let { load(it.id) }

    override fun create(title: String): Conversation {
        val now = nowMs()
        val id = "conv-${now / 1000}-${Random.nextInt(1000, 9999)}"
        val conversation = Conversation(id = id, title = title, createdAtMs = now, messages = emptyList())
        save(conversation)
        return conversation
    }

    override fun save(conversation: Conversation) {
        store.writeText(File(dir, "${conversation.id}.json"), appJson.encodeToString(conversation.toDto()))
        upsertIndex(ConversationMetaDto(conversation.id, conversation.title, nowMs()))
    }

    override fun delete(id: String) {
        store.delete(File(dir, "$id.json"))
        writeIndex(loadIndex().filterNot { it.id == id })
    }

    private fun loadIndex(): List<ConversationMetaDto> = runCatching {
        store.readText(indexFile).takeIf { it.isNotBlank() }?.let { appJson.decodeFromString<List<ConversationMetaDto>>(it) }
    }.getOrNull() ?: emptyList()

    private fun writeIndex(metas: List<ConversationMetaDto>) =
        store.writeText(indexFile, appJson.encodeToString(metas))

    private fun upsertIndex(meta: ConversationMetaDto) =
        writeIndex(loadIndex().filterNot { it.id == meta.id } + meta)
}
