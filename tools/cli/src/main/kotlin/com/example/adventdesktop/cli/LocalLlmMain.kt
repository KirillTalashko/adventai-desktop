package com.example.adventdesktop.cli

import com.example.adventdesktop.data.LOCAL_LLM_SAMPLES
import com.example.adventdesktop.data.LOCAL_LLM_SYSTEM
import com.example.adventdesktop.data.LlmSample
import com.example.adventdesktop.data.LocalLlmClient
import com.example.adventdesktop.domain.LlmParams
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * `runLocalLlm` — консольная проверка локальной LLM (Неделя 6, День 26). Обращается к запущенной Ollama по
 * HTTP (`localhost:11434/api/chat`) и прогоняет **3 запроса разной сложности** ([LOCAL_LLM_SAMPLES], + любые
 * свои из аргументов), печатая для каждого ответ, задержку и токены. Модель по умолчанию —
 * [LocalLlmClient.DEFAULT_MODEL], переопределяется системным свойством `-Dmodel=<name>`.
 *
 * Запуск: `.\gradlew.bat runLocalLlm` (или `.\gradlew.bat runLocalLlm -Pmodel=llama3.2:3b`).
 * Требует: `ollama serve` + `ollama pull <model>` (иначе — внятная ошибка от клиента).
 */
fun main(args: Array<String>) {
    val model = System.getProperty("model")?.takeIf { it.isNotBlank() } ?: LocalLlmClient.DEFAULT_MODEL
    val extra = args.filterNot { it.startsWith("--") }.filter { it.isNotBlank() }.map { LlmSample("свой запрос", it) }
    val samples = LOCAL_LLM_SAMPLES + extra

    println("=== День 26 — локальная LLM через Ollama ===")
    println("Модель: $model · эндпойнт: http://localhost:11434/api/chat")
    println("Запросов: ${samples.size}")
    println()

    val client = LocalLlmClient(model = model)
    try {
        runBlocking {
            samples.forEachIndexed { i, s ->
                println("──────────────────────────────────────────────")
                println("Запрос ${i + 1}/${samples.size} · сложность: ${s.level}")
                println("❓ ${s.prompt}")
                try {
                    val start = System.currentTimeMillis()
                    val resp = client.complete(
                        listOf(Message(Role.System, LOCAL_LLM_SYSTEM), Message(Role.User, s.prompt)),
                        params = LlmParams(temperature = 0.3),
                    )
                    val ms = System.currentTimeMillis() - start
                    val u = resp.usage
                    println("💬 ${resp.text}")
                    println("⏱ $ms мс · токены: вход ${u?.prompt ?: 0}, выход ${u?.completion ?: 0}, всего ${u?.total ?: 0}")
                } catch (e: CancellationException) {
                    throw e   // Ctrl+C / отмена — прекращаем прогон, а не «ошибка запроса»
                } catch (e: Exception) {
                    println("⚠️ Ошибка: ${e.message}")
                }
                println()
            }
        }
    } finally {
        client.close()
    }
    println("=== Готово ===")
}
