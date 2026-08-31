package com.example.adventdesktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.adventdesktop.data.LOCAL_LLM_SAMPLES
import com.example.adventdesktop.data.LlmServiceClient
import com.example.adventdesktop.domain.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * Держатель состояния dev-панели «Обращение к сервису по HTTP» (День 30) — ПЕРВЫЙ срез, вынесенный из
 * god-object [ChatState] (шаг расшивки по SRP, см. .claude/MODULARIZATION.md). Своя зона: URL/токен/лог +
 * прогоны health/chat/burst через [LlmServiceClient]. Зависимости инжектятся конструктором — ноль связей с
 * агентом и другими панелями, кроме промпта для `/chat` (берётся из панели «Локальная LLM» через [prompt]).
 *
 * @param scope корутин-скоуп приложения (жизненный цикл — у [ChatState]).
 * @param prompt текущий промпт для `POST /chat` (из панели «Локальная LLM»); пустой → дефолтный вопрос.
 */
class ServicePanelState(
    private val scope: CoroutineScope,
    private val prompt: () -> String,
) {
    var serviceUrl by mutableStateOf("http://127.0.0.1:3002")
    var serviceToken by mutableStateOf("")
    var serviceRunning by mutableStateOf(false)
        private set
    var serviceLog by mutableStateOf<List<String>>(emptyList())
        private set

    fun clearServiceLog() { serviceLog = emptyList() }

    /** `GET /health` — показать, что приватный сервис доступен по сети. */
    fun serviceHealth() = serviceCall { c ->
        val r = c.health()
        "GET /health → ${r.status} · ${r.body} (${r.ms} мс)"
    }

    /** `POST /chat` — отправить запрос локальной модели по сети через сервис. */
    fun serviceChat() = serviceCall { c ->
        val p = prompt().ifBlank { "Что такое шенгенская виза? Одним предложением." }
        val r = c.chat(p)
        "POST /chat → ${r.status} · ${r.body.take(300)} (${r.ms} мс)" + auth401(r.status)
    }

    /** Прогнать 3 контрольных вопроса через сервис по сети — модель на сервисе отвечает по одному. */
    fun serviceRunSamples() {
        if (serviceRunning) return
        serviceRunning = true
        scope.launch {
            val c = LlmServiceClient(serviceUrl, serviceToken.ifBlank { null })
            for (s in LOCAL_LLM_SAMPLES) {
                val line = runCatchingCancellable {
                    val r = c.chat(s.prompt)
                    "[${s.level}] ${r.status} · ${r.body.take(280)} (${r.ms} мс)" + auth401(r.status)
                }.getOrElse { "[${s.level}] ошибка: ${it.message}" }
                serviceLog = serviceLog + line
            }
            runCatchingCancellable { c.close() }
            serviceRunning = false
        }
    }

    /** Всплеск: 6 параллельных запросов — наглядно про лимиты (429 rate-limit / 503 занят). */
    fun serviceBurst() {
        if (serviceRunning) return
        serviceRunning = true
        scope.launch {
            val c = LlmServiceClient(serviceUrl, serviceToken.ifBlank { null })
            val codes = runCatchingCancellable {
                (1..6).map { i -> async { c.chat("Документ №$i на визу, одной строкой.").status } }.awaitAll()
            }.getOrElse { emptyList() }
            val tally = codes.groupingBy { it }.eachCount()
            serviceLog = serviceLog + "Нагрузка ×6 → коды: $tally  (200 ответ · 429 rate-limit · 503 занят)"
            runCatchingCancellable { c.close() }
            serviceRunning = false
        }
    }

    private fun serviceCall(block: suspend (LlmServiceClient) -> String) {
        if (serviceRunning) return
        serviceRunning = true
        scope.launch {
            val c = LlmServiceClient(serviceUrl, serviceToken.ifBlank { null })
            val line = runCatchingCancellable { block(c) }.getOrElse { "ошибка: ${it.message}" }
            serviceLog = serviceLog + line
            runCatchingCancellable { c.close() }
            serviceRunning = false
        }
    }

    /** Дружелюбная подсказка при 401 (частая причина — токен в поле не совпал с LLM_AUTH_TOKEN сервиса). */
    private fun auth401(status: Int): String = if (status == 401) "  ⚠ токен в поле ≠ LLM_AUTH_TOKEN сервиса" else ""
}
