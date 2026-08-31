package com.example.adventdesktop.cli

import com.example.adventdesktop.domain.Conversation
import com.example.adventdesktop.domain.Derived
import com.example.adventdesktop.domain.GatewayResponse
import com.example.adventdesktop.domain.Invariant
import com.example.adventdesktop.domain.LlmGateway
import com.example.adventdesktop.domain.LlmParams
import com.example.adventdesktop.domain.LongTermMemory
import com.example.adventdesktop.domain.MemoryMode
import com.example.adventdesktop.domain.MemoryPlanner
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import com.example.adventdesktop.domain.STAGE_WINDOW
import com.example.adventdesktop.domain.TokenUsage
import com.example.adventdesktop.domain.Tool
import com.example.adventdesktop.domain.UserProfile
import com.example.adventdesktop.domain.WINDOW_N
import com.example.adventdesktop.domain.WorkingMemory
import com.example.adventdesktop.domain.ContextAssembler
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * ХАРНЕСС РЕЖИМОВ ПАМЯТИ (селектор «Память» в композере, DESIGN_BRIEF.md §Раскладка/§Память).
 *
 * Проверяет ровно то, что не поймает компилятор: ЧТО каждый режим кладёт в запрос, сколько реплик
 * выбрасывает и сколько платных вызовов свёртки делает. Детерминированно и БЕЗ СЕТИ: шлюз подменён
 * скриптованным [CountingGateway], который считает обращения. В проекте нет тест-фреймворка — тот же
 * CLI-паттерн, что `runTaskFlowCheck` / `runRagCountryCheck`.
 *
 * Запуск: `.\gradlew.bat runMemoryModeCheck`. exitCode 1 при провале.
 */
private class CountingGateway(private val fail: Boolean = false) : LlmGateway {
    var calls = 0
        private set

    override suspend fun complete(
        messages: List<Message>,
        tools: List<Tool>,
        params: LlmParams,
        executeTool: (suspend (String, String) -> String)?,
    ): GatewayResponse {
        calls++
        if (fail) throw IllegalStateException("свёртка недоступна (имитация сбоя сети)")
        return GatewayResponse(
            "[СТРАТЕГИЧЕСКОЕ]\n- цель: Шенген во Францию\n[ТАКТИЧЕСКОЕ]\n- обсудили пакет документов",
            TokenUsage(1, 1, 2)
        )
    }
}

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("  [OK]   $name")
    } else {
        failures++
        println("  [FAIL] $name${if (detail.isNotEmpty()) " — $detail" else ""}")
    }
}

/** Синтетический диалог: [n] реплик, чередование пользователь/агент. */
private fun history(n: Int): List<Message> = (1..n).map { i ->
    Message(if (i % 2 == 1) Role.User else Role.Assistant, "реплика номер $i про визу и документы")
}

fun main() = runBlocking {
    println("=== Режимы памяти: что уходит в запрос ===")
    val h40 = history(40)

    // --- Авто: пока окно свободно — вся история, без свёртки ---
    run {
        val gw = CountingGateway()
        val p = MemoryPlanner(gw).plan(MemoryMode.Auto, h40, Derived(), contextFill = 0.0f, limit = STAGE_WINDOW)
        check("Авто · окно свободно → вся история", p.history.size == 40, "было ${p.history.size}")
        check("Авто · окно свободно → ничего не выброшено", p.dropped == 0, "dropped=${p.dropped}")
        check("Авто · окно свободно → блоков памяти нет", p.strategic.isEmpty() && p.tactical.isEmpty())
        check("Авто · окно свободно → 0 платных вызовов", gw.calls == 0, "вызовов ${gw.calls}")
    }

    // --- Авто: окно заполнено — окно + оба уровня памяти ---
    run {
        val gw = CountingGateway()
        val p = MemoryPlanner(gw).plan(MemoryMode.Auto, h40, Derived(), contextFill = 0.5f, limit = STAGE_WINDOW)
        check("Авто · окно заполнено → последние $STAGE_WINDOW", p.history.size == STAGE_WINDOW, "было ${p.history.size}")
        check("Авто · окно заполнено → dropped=28", p.dropped == 28, "dropped=${p.dropped}")
        check("Авто · окно заполнено → есть оба уровня", p.strategic.isNotEmpty() && p.tactical.isNotEmpty())
        check("Авто · окно заполнено → ровно 1 свёртка", gw.calls == 1, "вызовов ${gw.calls}")
    }

    // --- Окно: жёсткие последние N, без блоков и без вызовов ---
    run {
        val gw = CountingGateway()
        val p = MemoryPlanner(gw).plan(MemoryMode.Window, h40, Derived(), contextFill = 0.9f, limit = STAGE_WINDOW)
        check("Окно · только последние $WINDOW_N", p.history.size == WINDOW_N, "было ${p.history.size}")
        check("Окно · dropped=${40 - WINDOW_N}", p.dropped == 40 - WINDOW_N, "dropped=${p.dropped}")
        check("Окно · блоков памяти нет", p.strategic.isEmpty() && p.tactical.isEmpty())
        check("Окно · 0 платных вызовов", gw.calls == 0, "вызовов ${gw.calls}")
    }

    // --- Окно: история короче окна — ничего не теряем (граница из прототипа) ---
    run {
        val gw = CountingGateway()
        val p = MemoryPlanner(gw).plan(MemoryMode.Window, history(4), Derived(), contextFill = 0.9f, limit = STAGE_WINDOW)
        check("Окно · история короче окна → вся", p.history.size == 4, "было ${p.history.size}")
        check("Окно · история короче окна → dropped=0", p.dropped == 0, "dropped=${p.dropped}")
    }

    // --- Факты: только стратегический уровень ---
    run {
        val gw = CountingGateway()
        val p = MemoryPlanner(gw).plan(MemoryMode.Facts, h40, Derived(), contextFill = 0.0f, limit = STAGE_WINDOW)
        check("Факты · есть стратегический уровень", p.strategic.isNotEmpty())
        check("Факты · тактического нет", p.tactical.isEmpty(), "было «${p.tactical}»")
        check("Факты · ровно 1 свёртка", gw.calls == 1, "вызовов ${gw.calls}")

        // Гистерезис: хвост подрос на 1 реплику — пересворачивать рано (иначе платный вызов каждый ход).
        val gw2 = CountingGateway()
        val p2 = MemoryPlanner(gw2).plan(MemoryMode.Facts, h40 + history(1), p.derived, contextFill = 0.0f, limit = STAGE_WINDOW)
        check("Факты · +1 реплика → свёртки НЕ повторяем", gw2.calls == 0, "вызовов ${gw2.calls}")
        check("Факты · кэш свёртки переиспользован", p2.strategic == p.strategic)
    }

    // --- Пересказ: только тактический уровень ---
    run {
        val gw = CountingGateway()
        val p = MemoryPlanner(gw).plan(MemoryMode.Summary, h40, Derived(), contextFill = 0.0f, limit = STAGE_WINDOW)
        check("Пересказ · есть тактический уровень", p.tactical.isNotEmpty())
        check("Пересказ · стратегического нет", p.strategic.isEmpty(), "было «${p.strategic}»")
    }

    // --- Сбой свёртки: историю не теряем, кэш не портим ---
    run {
        val gw = CountingGateway(fail = true)
        val p = MemoryPlanner(gw).plan(MemoryMode.Facts, h40, Derived(), contextFill = 0.0f, limit = STAGE_WINDOW)
        check("Сбой свёртки · окно истории на месте", p.history.size == STAGE_WINDOW, "было ${p.history.size}")
        check("Сбой свёртки · кэш не испорчен", p.derived == Derived())
        check("Сбой свёртки · блоков памяти нет", p.strategic.isEmpty() && p.tactical.isEmpty())
    }

    // --- Вся история + предохранитель ---
    run {
        val gw = CountingGateway()
        val planner = MemoryPlanner(gw)
        val full = planner.plan(MemoryMode.Full, h40, Derived(), contextFill = 0.9f, limit = STAGE_WINDOW)
        check("Вся история · уходит целиком", full.history.size == 40, "было ${full.history.size}")
        check("Вся история · dropped=0", full.dropped == 0, "dropped=${full.dropped}")
        check("Вся история · 0 платных вызовов", gw.calls == 0, "вызовов ${gw.calls}")

        val capped = planner.plan(MemoryMode.Full, h40, Derived(), contextFill = 0.9f, limit = STAGE_WINDOW, charBudget = 500)
        val chars = capped.history.sumOf { it.text.length }
        check("Вся история · предохранитель режет", capped.dropped > 0, "dropped=${capped.dropped}")
        check("Вся история · влезли в бюджет", chars <= 500 + 60, "символов $chars")
        check("Вся история · последняя реплика сохранена", capped.history.lastOrNull() == h40.last())
    }

    // --- Инвариант поперёк ВСЕХ режимов: правила и профиль не зависят от режима ---
    println("=== Правила и профиль — при любом режиме ===")
    val inv = listOf(Invariant(id = "i1", text = "не выдумывать сроки", builtIn = true, active = true))
    val profile = UserProfile(name = "Кирилл")
    val conv = Conversation(id = "c1", title = "t", createdAtMs = 0L, messages = h40)
    for (mode in MemoryMode.entries) {
        val gw = CountingGateway()
        val assembled = ContextAssembler(gw, "БАЗОВЫЙ ПРОМПТ").assemble(
            conv, WorkingMemory(goal = "виза во Францию"), LongTermMemory(profile = "живёт в Москве"),
            profile, inv, contextFill = 0.5f, mode = mode,
        )
        val sys = assembled.messages.first().text
        check("${mode.title} · жёсткие правила на месте", sys.contains("не выдумывать сроки"))
        check("${mode.title} · профиль на месте", sys.contains("ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ"))
        check("${mode.title} · system идёт первым", assembled.messages.first().role == Role.System)
    }

    // --- Разбор значения из конфига ---
    println("=== Чтение режима из конфига ===")
    check("пустое значение → Авто", MemoryMode.byId("") == MemoryMode.Auto)
    check("неизвестное значение → Авто", MemoryMode.byId("нет-такого") == MemoryMode.Auto)
    check("null → Авто", MemoryMode.byId(null) == MemoryMode.Auto)
    for (mode in MemoryMode.entries) {
        check("${mode.name.lowercase()} → ${mode.title}", MemoryMode.byId(mode.name.lowercase()) == mode)
    }

    println()
    if (failures == 0) {
        println("ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ")
    } else {
        println("ПРОВАЛОВ: $failures")
        exitProcess(1)
    }
}
