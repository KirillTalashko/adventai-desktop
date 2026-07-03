package com.example.adventdesktop.data

import com.example.adventdesktop.domain.rag.GoldQuestion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Загрузка контрольного набора (День 22) из ресурса `rag_eval/gold_questions.json`. */
object GoldQuestions {

    @Serializable
    private data class Dto(
        val id: Int,
        val question: String,
        val expect: String,
        val sources: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(): List<GoldQuestion> {
        val bytes = GoldQuestions::class.java.classLoader
            .getResourceAsStream("rag_eval/gold_questions.json")?.use { it.readBytes() } ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<Dto>>(bytes.decodeToString())
                .map { GoldQuestion(it.id, it.question, it.expect, it.sources) }
        }.getOrDefault(emptyList())
    }
}
