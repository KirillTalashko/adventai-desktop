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

    println(if (failed == 0) "\nВСЕ ХАРАКТЕРИЗУЮЩИЕ ПРОВЕРКИ ПРОЙДЕНЫ." else "\nПРОВАЛОВ: $failed")
    if (failed > 0) exitProcess(1)
}
