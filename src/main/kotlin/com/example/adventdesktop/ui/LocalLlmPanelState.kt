package com.example.adventdesktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.adventdesktop.data.LOCAL_LLM_SAMPLES
import com.example.adventdesktop.data.LOCAL_LLM_SYSTEM
import com.example.adventdesktop.data.LlmSample
import com.example.adventdesktop.data.LocalLlmClient
import com.example.adventdesktop.data.LocalRun
import com.example.adventdesktop.data.LocalTuning
import com.example.adventdesktop.data.OPT_TASK_PROMPT
import com.example.adventdesktop.data.fetchOllamaModels
import com.example.adventdesktop.domain.LlmParams
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import com.example.adventdesktop.domain.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Держатель состояния панели «Локальная LLM» (Ollama) — Дни 26/29. Второй срез из god-object [ChatState]
 * (SRP-расшивка, см. .claude/MODULARIZATION.md). Своя зона: выбор модели/список, запрос и 3 контрольных
 * прогона + A/B «до vs после оптимизации». Единственная зависимость — [scope]; клиент/константы берутся из
 * data-слоя. Список установленных моделей может выставляться извне ([setAvailableModels]) — панель RAG
 * переиспользует его для дропдауна «локально vs облако».
 */
class LocalLlmPanelState(private val scope: CoroutineScope) {

    /** Результат одного запроса к локальной LLM (для панели): метка, промпт, ответ/ошибка, задержка, токены. */
    data class LocalLlmResult(
        val level: String,
        val prompt: String,
        val answer: String,
        val ms: Long,
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int,
        val error: Boolean = false,
    )

    var localLlmOpen by mutableStateOf(false)
        private set
    var localLlmModel by mutableStateOf(LocalLlmClient.DEFAULT_MODEL)
    /** Установленные в Ollama модели (для выпадашки выбора); заполняется при открытии панели. */
    var localLlmModels by mutableStateOf<List<String>>(emptyList())
        private set
    var localLlmPrompt by mutableStateOf("Кратко: что такое шенгенская виза?")
    var localLlmRunning by mutableStateOf(false)
        private set
    var localLlmNote by mutableStateOf<String?>(null)
        private set
    var localLlmResults by mutableStateOf<List<LocalLlmResult>>(emptyList())
        private set

    /** Внешняя установка списка моделей (панель RAG переиспользует его для сравнения local-vs-cloud). */
    fun setAvailableModels(models: List<String>) { localLlmModels = models }

    fun openLocalLlm() {
        localLlmOpen = true
        scope.launch {
            val models = fetchOllamaModels()
            localLlmModels = models
            if (localLlmModel !in models && models.isNotEmpty()) localLlmModel = models.first()
        }
    }
    fun closeLocalLlm() { localLlmOpen = false }

    /** Один запрос к локальной LLM из поля ввода панели. */
    fun localLlmAsk() {
        val prompt = localLlmPrompt.trim()
        if (prompt.isEmpty() || localLlmRunning) return
        runLocalLlm(listOf(LlmSample("свой запрос", prompt)))
    }

    /** Прогнать 3 контрольных запроса разной сложности ([LOCAL_LLM_SAMPLES]). */
    fun localLlmRunSamples() {
        if (localLlmRunning) return
        runLocalLlm(LOCAL_LLM_SAMPLES)
    }

    private fun runLocalLlm(samples: List<LlmSample>) {
        localLlmRunning = true
        localLlmNote = null
        localLlmResults = emptyList()
        val model = localLlmModel.trim().ifBlank { LocalLlmClient.DEFAULT_MODEL }
        scope.launch {
            val client = LocalLlmClient(model = model)
            val acc = mutableListOf<LocalLlmResult>()
            for (s in samples) {
                val start = System.currentTimeMillis()
                val r = runCatchingCancellable {
                    client.complete(
                        listOf(Message(Role.System, LOCAL_LLM_SYSTEM), Message(Role.User, s.prompt)),
                        params = LlmParams(temperature = 0.3),
                    )
                }
                val ms = System.currentTimeMillis() - start
                r.onSuccess { resp ->
                    val u = resp.usage
                    acc.add(LocalLlmResult(s.level, s.prompt, resp.text, ms, u?.prompt ?: 0, u?.completion ?: 0, u?.total ?: 0))
                }.onFailure { e ->
                    acc.add(LocalLlmResult(s.level, s.prompt, e.message ?: "ошибка", ms, 0, 0, 0, error = true))
                }
                localLlmResults = acc.toList()   // прогрессивно показываем по мере ответов
            }
            runCatchingCancellable { client.close() }
            if (acc.isNotEmpty() && acc.all { it.error }) {
                localLlmNote = "Локальная модель недоступна. Запусти `ollama serve` и `ollama pull $model`."
            }
            localLlmRunning = false
        }
    }

    // --- День 29: оптимизация локальной модели под задачу (A/B «до vs после») ---

    var optRunning by mutableStateOf(false)
        private set
    var optBefore by mutableStateOf<LocalRun?>(null)
        private set
    var optAfter by mutableStateOf<LocalRun?>(null)
        private set
    var optModel by mutableStateOf(LocalLlmClient.DEFAULT_MODEL)
    var optTask by mutableStateOf(
        "Гражданин РФ едет в Германию на 7 дней с целью туризма. Какие ключевые документы нужны на шенгенскую визу?"
    )
    var optSystem by mutableStateOf(OPT_TASK_PROMPT)
    var optTemperature by mutableStateOf(0.2f)
    var optMaxTokens by mutableStateOf("256")
    var optNumCtx by mutableStateOf("4096")

    /**
     * День 29 — A/B «до vs после оптимизации» на одной задаче: baseline (общий промпт + дефолты Ollama) против
     * tuned (задачный промпт-шаблон + temperature/max tokens/context window). Модель/квант — [optModel] (общая;
     * смени её и прогони снова, чтобы сравнить кванты). Метрики: задержка, токены, throughput (ток/с).
     */
    fun optimizeCompare() {
        val task = optTask.trim()
        if (task.isEmpty() || optRunning) return
        optRunning = true
        localLlmNote = null
        optBefore = null
        optAfter = null
        scope.launch {
            val client = LocalLlmClient()
            runCatchingCancellable {
                optBefore = client.runTuned(task, LocalTuning(model = optModel))   // baseline: общий промпт + дефолты
                optAfter = client.runTuned(                                        // tuned: задачный шаблон + ручки
                    task,
                    LocalTuning(
                        model = optModel, system = optSystem,
                        temperature = optTemperature.toDouble(),
                        numPredict = optMaxTokens.toIntOrNull(),
                        numCtx = optNumCtx.toIntOrNull(),
                    ),
                )
            }.onFailure { localLlmNote = "Ошибка оптимизации: ${it.message}" }
            runCatchingCancellable { client.close() }
            optRunning = false
        }
    }
}
