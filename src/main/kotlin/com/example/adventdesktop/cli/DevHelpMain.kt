package com.example.adventdesktop.cli

import com.example.adventdesktop.data.LocalLlmClient
import com.example.adventdesktop.data.McpClient
import com.example.adventdesktop.data.OllamaEmbedder
import com.example.adventdesktop.data.ProjectDocsIndex
import com.example.adventdesktop.data.appHomeDir
import com.example.adventdesktop.domain.DevAssistant
import com.example.adventdesktop.domain.rag.KnowledgeHit
import com.example.adventdesktop.domain.runCatchingCancellable
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * День 31 — приёмка ассистента разработчика БЕЗ окна: полный путь `/help` в консоли.
 *
 * Прогоняет ровно то, что делает команда `/help` в приложении: индексирует документацию проекта,
 * берёт живую git-ветку через dev-MCP и отвечает на вопросы локальной моделью строго по докам.
 * Последний вопрос из набора по умолчанию — **негативный контроль**: его в документации нет,
 * ассистент обязан честно отказаться, а не выдумать.
 *
 * Запуск:  .\gradlew.bat runDevHelp
 *          .\gradlew.bat runDevHelp --args="свой вопрос"
 *
 * Требует: `ollama serve` + модели `nomic-embed-text` (эмбеддер) и `qwen2.5:7b` (генерация).
 */
fun main(args: Array<String>) = runBlocking {
    val root = File(System.getProperty("user.dir"))
    val index = ProjectDocsIndex(File(appHomeDir(), "devdocs"), root)
    val embedder = OllamaEmbedder()
    val gateway = McpClient(serverMainClass = "com.example.adventdesktop.mcp.DevMcpServerKt")
    val llm = LocalLlmClient()

    try {
        println("=== Корпус документации проекта ===")
        val docs = index.documents()
        println("Документов: ${docs.size}")
        docs.take(30).forEach { println("  • ${it.source}") }

        // Индекс живёт в SQLite и переживает перезапуск: строим только если его нет или попросили явно.
        val forceReindex = args.any { it == "--reindex" }
        val existing = index.stats()
        val stats = if (existing != null && !forceReindex) {
            println("\n=== Индекс уже построен (переиндексировать: --reindex) ===")
            existing
        } else {
            println("\n=== Индексация (RAG по докам проекта) ===")
            println("Считаем эмбеддинги локально на CPU — это разовая операция, займёт несколько минут.")
            var lastShown = 0
            index.rebuild(embedder) { done, total ->
                // Печатаем не чаще, чем раз в 25 чанков, иначе лог тонет в прогрессе.
                if (done - lastShown >= 25 || done == total) {
                    lastShown = done
                    println("  … $done/$total чанков")
                }
            }
        }
        println("✓ ${stats.docCount} документов, ${stats.chunkCount} чанков за ${stats.buildMs} мс (эмбеддер ${stats.embedderId})")

        val questions = args.filter { it.isNotBlank() && !it.startsWith("--") }.ifEmpty {
            listOf(
                "Где хранятся диалоги и что лежит в каталоге ~/.adventai?",
                "Какое правило в проекте про кликабельные элементы в UI?",
                "Как настроить биллинг Stripe в этом проекте?",   // негативный контроль — этого в доках нет
            )
        }

        val assistant = DevAssistant(
            gateway = llm,
            retrieveDocs = { q ->
                // Диагностика порога: что нашлось БЕЗ отсечки (floor=0) — видно, режет ли порог нужное.
                // Без шлюза — чистое векторное ранжирование (эвристика), чтобы видеть, ЧТО вообще нашлось
                // до отбора моделью, и не тратить лишний вызов LLM.
                val raw = index.search(embedder, q, ProjectDocsIndex.DEV_OPTIONS.copy(floor = 0f))
                println("   · пул до LLM-отбора: " + raw.joinToString(", ") { "%s=%.3f".format(it.chunk.meta.source, it.score) })
                index.search(embedder, q, gateway = llm).map { s ->
                    KnowledgeHit(
                        source = s.chunk.meta.source,
                        section = s.chunk.meta.section,
                        chunkId = s.chunk.meta.chunkId,
                        score = s.score,
                        text = s.chunk.text.trim(),
                    )
                }
            },
            gitContext = {
                runCatchingCancellable { gateway.callTool("git_current_branch") }.getOrNull()?.trim()
            },
        )

        questions.forEach { q ->
            println("\n──────────────────────────────────────────────")
            println("❓ $q")
            val a = assistant.help(q)
            println("💬 ${a.text}")
            println("🌿 ветка: ${a.branch ?: "(недоступна)"} · по докам: ${if (a.grounded) "да" else "НЕТ (честный отказ)"}")
            if (a.sources.isNotEmpty()) println("📄 источники: ${a.sources.joinToString(", ")}")
        }
    } finally {
        embedder.close()
        llm.close()
        gateway.close()
    }
    println("\n✓ Готово.")
}
