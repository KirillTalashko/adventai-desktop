package com.example.adventdesktop.domain

/**
 * Режим управления контекстом — селектор «Память» в композере (DESIGN_BRIEF.md §Память).
 *
 * Режим влияет ТОЛЬКО на объём истории и на блоки памяти диалога. Инварианты (жёсткие правила),
 * профиль пользователя и состояние задачи попадают в system при ЛЮБОМ режиме — это правила и
 * контекст задачи, а не память диалога.
 *
 * [title] — в выпадающем списке, [chip] — короткая подпись на чипе, [hint] — пояснение под пунктом
 * списка (пользователь выбирает не вслепую). Термины вроде «sliding window» намеренно оставлены
 * только в пояснении: интерфейс читает неспециалист.
 */
enum class MemoryMode(val title: String, val chip: String, val hint: String) {
    Auto(
        "Авто", "авто",
        "Как сейчас: профиль, состояние задачи и последние реплики. Когда окно модели заполняется — " +
            "старый хвост сворачивается автоматически. Режим по умолчанию."
    ),
    Window(
        "Только последние сообщения", "последние $WINDOW_N",
        "В модель уходят лишь последние $WINDOW_N реплик, всё старше отбрасывается (sliding window). " +
            "Дёшево и быстро, но ранние детали диалога теряются."
    ),
    Facts(
        "Ключевые факты", "факты",
        "Старый хвост сжимается в устойчивые факты (цели, договорённости, решения) и идёт отдельным " +
            "блоком. Детали не теряются без полной истории; сжатие изредка стоит доп. запроса к модели."
    ),
    Summary(
        "Краткий пересказ", "пересказ",
        "Старая часть диалога сворачивается в короткую сводку хода разговора вместо полной истории. " +
            "Экономит токены; сжатие изредка стоит доп. запроса к модели."
    ),
    Full(
        "Вся история", "вся история",
        "В модель уходит весь диалог целиком. Дороже всего по токенам; при переполнении окна модели " +
            "самые старые реплики всё равно обрезаются предохранителем."
    );

    companion object {
        /** Значение из конфига (строка) → режим; неизвестное/пустое → [Auto], без исключения. */
        fun byId(id: String?): MemoryMode = entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: Auto
    }
}

/** Размер жёсткого окна режима [MemoryMode.Window] (дефолт Android-прототипа). */
const val WINDOW_N = 6

/** Окно истории стадии по умолчанию: столько последних реплик уходит в запрос ([TaskOrchestrator]). */
const val STAGE_WINDOW = 12

/** Готовые блоки уровневой памяти для system — один источник заголовков (ассемблер и оркестратор). */
fun renderMemoryLevels(strategic: String, tactical: String): String = buildString {
    if (strategic.isNotBlank()) {
        append("[СТРАТЕГИЧЕСКАЯ ПАМЯТЬ ДИАЛОГА — устойчивые факты, цели и договорённости за весь диалог]\n").append(strategic)
    }
    if (tactical.isNotBlank()) {
        if (isNotEmpty()) append("\n\n")
        append("[ТАКТИЧЕСКОЕ РЕЗЮМЕ — краткая сводка недавнего хода разговора]\n").append(tactical)
    }
}.trim()

/** Что режим отдал запросу: два уровня памяти + окно истории + сколько реплик осталось за бортом. */
data class MemoryPlan(
    val strategic: String,
    val tactical: String,
    val history: List<Message>,
    val dropped: Int,
    val derived: Derived,
)

/**
 * Вклад памяти в стадийный запрос [TaskOrchestrator]: готовый блок в system + окно истории.
 * [derived] — обновлённый кэш свёрток (null, если не менялся); вызывающий сохраняет его в диалог.
 * Возвращается ЗНАЧЕНИЕМ, а не через поле состояния: иначе кэш утекал бы между диалогами при ошибке стадии.
 */
data class MemoryWindow(
    val block: String,
    val history: List<Message>,
    val dropped: Int,
    val derived: Derived? = null,
)

/** Порт: снаружи ([ChatState]) знают режим и кэш свёрток и отдают стадии её память. */
fun interface MemorySupplier {
    suspend fun supply(history: List<Message>, limit: Int): MemoryWindow
}

/**
 * Планировщик контекста: по выбранному [MemoryMode] решает, что уйдёт в запрос. Чистый домен —
 * зависит только от порта [LlmGateway] (свёртка хвоста делается моделью).
 */
class MemoryPlanner(private val gateway: LlmGateway) {

    suspend fun plan(
        mode: MemoryMode,
        history: List<Message>,
        derived0: Derived,
        contextFill: Float,
        limit: Int,
        charBudget: Int = Int.MAX_VALUE,
    ): MemoryPlan {
        val keep = limit.coerceAtLeast(1)
        val tail = (history.size - keep).coerceAtLeast(0)
        return when (mode) {
            // Сегодняшнее поведение один-в-один: пока окно свободно — вся история, иначе окно + свёртка.
            MemoryMode.Auto ->
                if (contextFill < SUMMARY_FILL || history.size <= keep) {
                    MemoryPlan("", "", history, 0, derived0)
                } else {
                    val d = fold(history, tail, derived0)
                    MemoryPlan(d.facts, d.summary, history.takeLast(keep), tail, d)
                }

            MemoryMode.Window -> {
                val n = minOf(keep, WINDOW_N)
                MemoryPlan("", "", history.takeLast(n), (history.size - n).coerceAtLeast(0), derived0)
            }

            MemoryMode.Facts -> {
                val d = fold(history, tail, derived0)
                MemoryPlan(d.facts, "", history.takeLast(keep), tail, d)
            }

            MemoryMode.Summary -> {
                val d = fold(history, tail, derived0)
                MemoryPlan("", d.summary, history.takeLast(keep), tail, d)
            }

            MemoryMode.Full -> {
                val fit = capByChars(history, charBudget)
                MemoryPlan("", "", fit, history.size - fit.size, derived0)
            }
        }
    }

    /**
     * Свёртка старого хвоста с ГИСТЕРЕЗИСОМ: пересворачиваем не раньше, чем хвост подрос на
     * [REFOLD_STEP] реплик. Без этого условие «хвост длиннее свёрнутого» выполнялось бы почти
     * на каждом ходу (хвост растёт на 1–2 реплики за ход) — и каждый ход стоил бы лишнего вызова модели.
     */
    private suspend fun fold(history: List<Message>, needed: Int, d: Derived): Derived {
        if (needed <= 0 || needed - d.summarizedCount < REFOLD_STEP) return d
        val lv = runCatchingCancellable { summarizeLeveled(history.take(needed)) }.getOrNull() ?: return d
        if (lv.strategic.isEmpty() && lv.tactical.isEmpty()) return d
        return d.copy(summary = lv.tactical, summarizedCount = needed, facts = lv.strategic, factsCount = needed)
    }

    /** Предохранитель режима «вся история»: с конца набираем реплики, пока влезаем в бюджет символов. */
    private fun capByChars(history: List<Message>, budget: Int): List<Message> {
        if (budget == Int.MAX_VALUE || history.isEmpty()) return history
        var left = budget
        val out = ArrayDeque<Message>()
        for (m in history.asReversed()) {
            left -= m.text.length
            if (left < 0 && out.isNotEmpty()) break
            out.addFirst(m)
            if (left < 0) break
        }
        return out.toList()
    }

    /** Свёрнутый старый хвост, разбитый на уровни (P4). */
    private data class Leveled(val strategic: String, val tactical: String)

    private suspend fun summarizeLeveled(old: List<Message>): Leveled {
        val transcript = old.joinToString("\n") { "${label(it.role)}: ${it.text}" }
        val raw = gateway.complete(listOf(Message(Role.System, SUMMARY_PROMPT), Message(Role.User, transcript))).text
        val strategic = section(raw, "СТРАТЕГИЧЕСКОЕ")
        val tactical = section(raw, "ТАКТИЧЕСКОЕ")
        // Фолбэк: модель не разметила блоки — кладём весь ответ в тактический уровень (как было до P4).
        return if (strategic.isEmpty() && tactical.isEmpty()) Leveled("", raw.trim()) else Leveled(strategic, tactical)
    }

    /** Достаёт содержимое блока `[TAG] … ` до следующего `[` или конца текста. */
    private fun section(raw: String, tag: String): String {
        val open = "[$tag]"
        val start = raw.indexOf(open, ignoreCase = true)
        if (start < 0) return ""
        val from = start + open.length
        val end = raw.indexOf('[', from).let { if (it >= 0) it else raw.length }
        return raw.substring(from, end).trim()
    }

    private fun label(role: Role) = when (role) {
        Role.User -> "Пользователь"
        Role.Assistant -> "Агент"
        Role.System -> "Система"
    }

    internal companion object {
        /** Порог заполнения окна, при котором в режиме «Авто» включается свёртка. */
        const val SUMMARY_FILL = 0.30f

        /** На сколько реплик должен подрасти хвост, чтобы пересворачивать (защита от вызова каждый ход). */
        const val REFOLD_STEP = 6

        const val SUMMARY_PROMPT =
            "Сожми приведённый диалог в ДВА уровня. Верни СТРОГО два блока в таком виде:\n" +
                "[СТРАТЕГИЧЕСКОЕ]\n- устойчивые факты, цели, договорённости и решения (верное для всего кейса; 3–6 пунктов)\n" +
                "[ТАКТИЧЕСКОЕ]\n- краткая сводка недавнего хода разговора (2–4 пункта)\n" +
                "Сохраняй факты, числа, даты, страны. Только эти два блока, без вступления."
    }
}

/** Что уходит в модель + обновлённая производная память + сколько реплик свёрнуто. */
data class Assembled(
    val messages: List<Message>,
    val derived: Derived,
    val dropped: Int
)

/**
 * Конвейер контекста: собирает system-блок (промпт + слои памяти) и окно истории.
 *
 *   system( базовый промпт + [ДОЛГОВРЕМЕННАЯ] + [РАБОЧАЯ] [+ уровни памяти] ) + последние N реплик
 *
 * ЧТО именно попадёт в запрос, решает [MemoryPlanner] по выбранному [MemoryMode]; сам ассемблер
 * только оформляет результат. Зависит только от порта [LlmGateway] (Clean Architecture).
 */
class ContextAssembler(
    gateway: LlmGateway,
    private val systemPrompt: String,
    private val windowSize: Int = 12
) {
    private val planner = MemoryPlanner(gateway)

    suspend fun assemble(
        conversation: Conversation,
        working: WorkingMemory,
        longTerm: LongTermMemory,
        profile: UserProfile?,
        invariants: List<Invariant>,
        contextFill: Float,
        mode: MemoryMode = MemoryMode.Auto,
    ): Assembled {
        val p = planner.plan(mode, conversation.messages, conversation.derived, contextFill, windowSize)
        val sys = systemBlock(working, longTerm, p.strategic, p.tactical, profile, invariants)
        return Assembled(listOf(system(sys)) + p.history, p.derived, p.dropped)
    }

    private fun systemBlock(working: WorkingMemory, longTerm: LongTermMemory, strategic: String, tactical: String, profile: UserProfile?, invariants: List<Invariant>): String = buildString {
        append(systemPrompt)
        val inv = renderInvariantsBlock(invariants)
        if (inv.isNotEmpty()) append("\n\n").append(inv)
        if (profile != null) {
            append("\n\n[ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ — как отвечать]\n").append(profile.toPromptBlock())
        }
        if (!longTerm.isEmpty) {
            append("\n\n[ДОЛГОВРЕМЕННАЯ ПАМЯТЬ — что известно о пользователе из прошлых реплик; может устареть]\n")
            if (longTerm.profile.isNotBlank()) append(longTerm.profile.trim()).append('\n')
            longTerm.decisions.forEach { append("- решение: ").append(it).append('\n') }
        }
        if (!working.isEmpty) {
            append("\n\n[РАБОЧАЯ ПАМЯТЬ — предполагаемая цель/ограничения текущей задачи]\n")
            if (working.goal.isNotBlank()) append("Цель: ").append(working.goal).append('\n')
            working.constraints.forEach { append("Ограничение: ").append(it).append('\n') }
        }
        if (profile != null || !longTerm.isEmpty || !working.isEmpty) {
            append("\n\nПрофиль и память — вспомогательный фон, а НЕ текущий запрос и не ограничение. Отвечай на ")
            append("ТЕКУЩИЙ вопрос пользователя; если фон (например, упомянутая страна или планы) не совпадает с ")
            append("вопросом — это НЕ противоречие, просто ответь на заданный вопрос.")
        }
        // Уровневая память (P4): стратегический (устойчивое) и тактический (недавнее) уровни — отдельными
        // блоками; локальный уровень — это последние N реплик, которые идут как есть после system.
        val levels = renderMemoryLevels(strategic, tactical)
        if (levels.isNotEmpty()) append("\n\n").append(levels)
    }.trim()

    private fun system(text: String) = Message(Role.System, text)
}
