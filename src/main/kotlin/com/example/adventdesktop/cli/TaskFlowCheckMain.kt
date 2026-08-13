package com.example.adventdesktop.cli

import com.example.adventdesktop.domain.Awaiting
import com.example.adventdesktop.domain.CaseFile
import com.example.adventdesktop.domain.GatewayResponse
import com.example.adventdesktop.domain.LlmGateway
import com.example.adventdesktop.domain.LlmParams
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import com.example.adventdesktop.domain.TaskContext
import com.example.adventdesktop.domain.TaskOrchestrator
import com.example.adventdesktop.domain.TaskState
import com.example.adventdesktop.domain.TaskStep
import com.example.adventdesktop.domain.TokenUsage
import com.example.adventdesktop.domain.Tool
import com.example.adventdesktop.domain.ToolGateway
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * ХАРАКТЕРИЗУЮЩИЙ ХАРНЕСС (шаг A1 скилла refactor-architecture, метод Feathers). Фиксирует ТЕКУЩЕЕ поведение
 * [TaskOrchestrator] — движка конечного автомата, который гоняет god-object `ChatState` — как СЕТЬ БЕЗОПАСНОСТИ
 * перед расшивкой `ChatState` (шаги PanelOp / state-holders). Не «как правильно», а «как есть сейчас»: если
 * последующий рефакторинг изменит эти инварианты — харнесс упадёт.
 *
 * Детерминированно и БЕЗ СЕТИ: LLM подменён скриптованным [ScriptGateway] (по системному промпту отличает
 * «писаря досье» от стадийного вызова), RAG выключен (retriever=null), инструменты выключены (tools=null),
 * профиль пуст. В проекте нет тест-фреймворка/`src/test` — тот же CLI-паттерн, что и `runRagCountryCheck`.
 *
 * Запуск: `.\gradlew.bat runTaskFlowCheck` (задача runTaskFlowCheck в build.gradle.kts). exitCode 1 при провале.
 */
private class ScriptGateway(private val reply: (system: String, user: String) -> String) : LlmGateway {
    override suspend fun complete(
        messages: List<Message>,
        tools: List<Tool>,
        params: LlmParams,
        executeTool: (suspend (String, String) -> String)?,
    ): GatewayResponse {
        val system = messages.firstOrNull { it.role == Role.System }?.text.orEmpty()
        val user = messages.lastOrNull { it.role == Role.User }?.text.orEmpty()
        return GatewayResponse(reply(system, user), TokenUsage(1, 1, 2))
    }
}

/** Скрипт: «писарь досье» (извлечение) → ничего не извлекает (досье не меняется); иначе — [stageReply]. */
private fun script(stageReply: String) = ScriptGateway { system, _ ->
    if (system.contains("писарь")) "" else stageReply
}

/**
 * Шлюз, ЗАПОМИНАЮЩИЙ сколько инструментов получил СТАДИЙНЫЙ вызов (не «писарь»-извлечение) — для проверки
 * ISP/LSP-фикса `supportsTools`: поддерживающий шлюз должен получить инструменты, не поддерживающий — ноль.
 */
private class RecordingGateway(override val supportsTools: Boolean) : LlmGateway {
    var lastToolCount = -1
        private set

    override suspend fun complete(
        messages: List<Message>,
        tools: List<Tool>,
        params: LlmParams,
        executeTool: (suspend (String, String) -> String)?,
    ): GatewayResponse {
        val sys = messages.firstOrNull { it.role == Role.System }?.text.orEmpty()
        if (!sys.contains("писарь")) lastToolCount = tools.size
        return GatewayResponse(if (sys.contains("писарь")) "" else "[SIMPLE] ok", TokenUsage(1, 1, 2))
    }
}

/** Стаб MCP: один инструмент, чтобы оркестратору было что «дать» модели. */
private object StubTools : ToolGateway {
    override suspend fun connect() {}
    override suspend fun listTools(): List<Tool> = listOf(Tool("get_visa_requirements", "тест", null))
    override suspend fun callTool(name: String, arguments: Map<String, Any?>): String = ""
    override suspend fun callToolJson(name: String, argumentsJson: String): String = ""
    override suspend fun close() {}
}

private val FULL_CASE = CaseFile(
    destination = "Испания", citizenship = "Россия", purpose = "туризм", timeframe = "осень 2026", travelers = "1",
)

private var failed = 0
private fun check(ok: Boolean, label: String) {
    if (!ok) failed++
    println("  ${if (ok) "PASS" else "FAIL"}  $label")
}

private fun step(gateway: LlmGateway, ctx: TaskContext, userText: String): TaskStep = runBlocking {
    val orch = TaskOrchestrator(gateway = gateway, retriever = null)
    val history = listOf(Message(Role.User, userText))
    val r = if (ctx.state == TaskState.EXECUTION || ctx.state == TaskState.VALIDATION) {
        orch.step(ctx, history, profile = null)
    } else {
        orch.intake(ctx, history, profile = null)
    }
    r.getOrElse { error("оркестратор бросил исключение: ${it.message}") }
}

fun main() {
    println("=== Характеризующий харнесс: поток задачи (TaskOrchestrator) ===")

    // 1) INTAKE + приветствие → [SIMPLE] → простой вопрос, режим задачи снимается (cancel).
    println("\n[1] INTAKE · приветствие → [SIMPLE] → cancel")
    run {
        val s = step(script("[SIMPLE] Здравствуйте! Чем помочь по визам?"), TaskContext(), "привет")
        check(s.cancel, "cancel == true (простой вопрос не заводит кейс)")
        check(s.context.state == TaskState.INTAKE, "состояние осталось INTAKE")
    }

    // 2) INTAKE + полное досье → готовность решает КОД → переход в PLANNING.
    println("\n[2] INTAKE · полное досье (турзима) → PLANNING")
    run {
        val s = step(script("Досье полное, готовлю план."), TaskContext(caseFile = FULL_CASE), "хочу оформить визу")
        check(!s.cancel, "cancel == false")
        check(s.context.state == TaskState.PLANNING, "переход INTAKE → PLANNING (isReadyForPlan)")
    }

    // 3) INTAKE + неполное досье (только страна) → остаёмся в INTAKE, ждём ответ.
    println("\n[3] INTAKE · неполное досье (нет гражданства/цели/дат) → остаёмся, awaiting=ANSWER")
    run {
        val ctx = TaskContext(caseFile = CaseFile(destination = "Испания"))
        val s = step(script("Уточните, пожалуйста, ваше гражданство и цель поездки."), ctx, "хочу в Испанию")
        check(s.context.state == TaskState.INTAKE, "состояние осталось INTAKE (не готов к плану)")
        check(s.context.awaiting == Awaiting.ANSWER, "awaiting == ANSWER (ждём недостающее)")
        check(!s.cancel, "cancel == false (кейс ведётся)")
    }

    // 4) EXECUTION · план из 1 шага → шаг продвигается и поток уходит в VALIDATION.
    println("\n[4] EXECUTION · план из 1 шага → step→1, переход в VALIDATION")
    run {
        val ctx = TaskContext(
            task = "Виза в Испанию", state = TaskState.EXECUTION, approach = "самостоятельно",
            plan = listOf("Собрать базовый пакет документов"), step = 0, caseFile = FULL_CASE,
        )
        val s = step(script("[STEP_RESULT] пакет собран"), ctx, "продолжаем")
        check(s.context.step == 1, "step продвинулся 0 → 1")
        check(s.context.state == TaskState.VALIDATION, "план кончился → переход в VALIDATION")
        check(!s.reply.text.contains("[STEP_RESULT]"), "управляющий тег [STEP_RESULT] вырезан из ответа")
    }

    // 5) EXECUTION · план из 2 шагов → продвигаемся, но остаёмся в EXECUTION.
    println("\n[5] EXECUTION · план из 2 шагов → step→1, остаёмся в EXECUTION")
    run {
        val ctx = TaskContext(
            task = "Виза в Испанию", state = TaskState.EXECUTION, approach = "самостоятельно",
            plan = listOf("Шаг один", "Шаг два"), step = 0, caseFile = FULL_CASE,
        )
        val s = step(script("[STEP_RESULT] шаг один готов"), ctx, "дальше")
        check(s.context.step == 1, "step продвинулся 0 → 1")
        check(s.context.state == TaskState.EXECUTION, "остаёмся в EXECUTION (есть ещё шаги)")
    }

    // 6) ISP/LSP: инструменты уходят в шлюз, КОТОРЫЙ ведёт tool-loop (supportsTools=true).
    println("\n[6] ISP · tools → шлюз supportsTools=true ПОЛУЧАЕТ инструменты")
    run {
        val gw = RecordingGateway(supportsTools = true)
        val orch = TaskOrchestrator(gateway = gw, tools = StubTools, retriever = null)
        runBlocking { orch.intake(TaskContext(), listOf(Message(Role.User, "привет")), profile = null) }
        check(gw.lastToolCount > 0, "поддерживающий шлюз получил инструменты (${gw.lastToolCount})")
    }

    // 7) ISP/LSP-ФИКС: инструменты НЕ уходят в шлюз без tool-loop (локальная модель) — конец молчаливому сбросу.
    println("\n[7] ISP · tools ✕ шлюз supportsTools=false инструментов НЕ получает (ФИКС)")
    run {
        val gw = RecordingGateway(supportsTools = false)
        val orch = TaskOrchestrator(gateway = gw, tools = StubTools, retriever = null)
        runBlocking { orch.intake(TaskContext(), listOf(Message(Role.User, "привет")), profile = null) }
        check(gw.lastToolCount == 0, "шлюз без поддержки инструментов НЕ получил их (${gw.lastToolCount})")
    }

    // 8) Bug-fix (kotlin-diagnostics): отказ на EXECUTION ([SIMPLE], без [STEP_RESULT]) — шаг НЕ продвигается,
    //    отказ НЕ маскируется под «шаг выполнен». Раньше дефолтился в пустышку и молча уходил в VALIDATION.
    println("\n[8] EXECUTION · отказ [SIMPLE] → шаг НЕ продвинут, остаёмся в EXECUTION")
    run {
        val ctx = TaskContext(
            task = "виза", state = TaskState.EXECUTION, approach = "самостоятельно",
            plan = listOf("Шаг один", "Шаг два"), step = 0, caseFile = FULL_CASE,
        )
        val s = step(script("[SIMPLE] не могу выполнить этот шаг"), ctx, "дальше")
        check(s.context.step == 0, "шаг НЕ продвинулся при отказе (было: продвигался с пустышкой)")
        check(s.context.state == TaskState.EXECUTION, "остаёмся в EXECUTION — отказ не штампуется как DONE")
    }

    // 9) EXECUTION без тега, но с реальным ответом → шаг продвигается СОДЕРЖАНИЕМ, а не пустышкой «шаг N выполнен».
    println("\n[9] EXECUTION · текст без [STEP_RESULT] → в [Сделано] реальный текст, не пустышка")
    run {
        val ctx = TaskContext(
            task = "виза", state = TaskState.EXECUTION, approach = "самостоятельно",
            plan = listOf("Собрать пакет документов"), step = 0, caseFile = FULL_CASE,
        )
        val s = step(script("Нужны: загранпаспорт, фото 3.5×4.5, справка с работы."), ctx, "дальше")
        check(s.context.step == 1, "шаг продвинулся")
        check(s.context.done.any { it.contains("загранпаспорт") }, "в [Сделано] реальный текст, не «шаг N выполнен»")
    }

    println(if (failed == 0) "\nВСЕ ХАРАКТЕРИЗУЮЩИЕ ПРОВЕРКИ ПРОЙДЕНЫ." else "\nПРОВАЛОВ: $failed")
    if (failed > 0) exitProcess(1)
}
