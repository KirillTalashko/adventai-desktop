package com.example.adventdesktop.cli

import com.example.adventdesktop.data.AccountStore
import com.example.adventdesktop.data.FileStore
import com.example.adventdesktop.data.appHomeDir
import com.example.adventdesktop.domain.Role
import com.example.adventdesktop.domain.TunableRole

/**
 * `visa-cli` — локальный CLI «Визового специалиста» (День 20, Skill + CLI как альтернатива MCP).
 *
 * Запуск: `java -cp <classpath> com.example.adventdesktop.cli.VisaCliMainKt <команда> [--account <id>]`
 * (или из собранного `visa-cli` jar). Печатает результат в **stdout**. Только локальные операции на чтение —
 * без сети и без shell, чтобы это нельзя было превратить в «логическую бомбу».
 *
 * Команды (растут по мере Дня 20):
 *  - `docs [list]`  — список приложенных документов аккаунта (`DocStore`).
 *  - `version`, `help`.
 */
fun main(args: Array<String>) {
    val opts = parseOpts(args)
    val sub = args.getOrNull(1)?.takeUnless { it.startsWith("--") }?.lowercase().orEmpty()
    when (args.firstOrNull()?.lowercase().orEmpty()) {
        "docs" -> docsCommand(sub, opts)
        "prompt-tune" -> promptTuneCommand(sub, opts)
        "version" -> println("visa-cli $VERSION")
        "", "help", "--help", "-h" -> println(USAGE)
        else -> println("Неизвестная команда: ${args.first()}\n\n$USAGE")
    }
}

private const val VERSION = "0.1.0"

private val USAGE = """
    visa-cli $VERSION — локальный помощник «Визового специалиста» (Skill + CLI).
    Использование: visa-cli <команда> [опции]

    Команды:
      docs [list]                       список приложенных документов аккаунта
      prompt-tune collect               сигналы из диалогов для улучшения промтов
      prompt-tune apply --role <id> --add "<текст>" [--reason "<r>"]   добавить персонализацию роли
      prompt-tune list                  текущая персонализация
      prompt-tune reset                 сбросить персонализацию
      version                           версия CLI
      help                              эта справка

    Опции:
      --account <id>     id аккаунта (по умолчанию — активный из ~/.adventai/accounts.json)
""".trimIndent()

/** Разбор опций вида `--key value` (остальные позиционные аргументы игнорируем). */
private fun parseOpts(args: Array<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val key = a.removePrefix("--")
            val value = args.getOrNull(i + 1)?.takeUnless { it.startsWith("--") }.orEmpty()
            map[key] = value
            i += if (value.isEmpty()) 1 else 2
        } else i++
    }
    return map
}

/** Активный аккаунт из опции `--account` или из accounts.json. */
private fun resolveAccountId(opts: Map<String, String>, accounts: AccountStore): String? =
    opts["account"]?.ifBlank { null } ?: accounts.state().activeId.ifBlank { null }

private fun docsCommand(sub: String, opts: Map<String, String>) {
    if (sub.isNotEmpty() && sub != "list") {
        println("docs: неизвестная подкоманда «$sub» (доступно: list)")
        return
    }
    val accounts = AccountStore(FileStore(appHomeDir()))
    val id = resolveAccountId(opts, accounts) ?: run {
        println("Аккаунт не найден. Укажите --account <id>."); return
    }
    val files = accounts.docs(id).list()
    if (files.isEmpty()) {
        println("Документы не приложены (аккаунт $id).")
        return
    }
    println("Приложенные документы (аккаунт $id): ${files.size}")
    files.forEach { f -> println("- ${f.name} (${humanSize(f.length())})") }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f МБ".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f КБ".format(bytes / 1024.0)
    else -> "$bytes Б"
}

// --- prompt-tune: персонализация промтов ролей (День 20, навык самоулучшения) ---

private fun promptTuneCommand(sub: String, opts: Map<String, String>) {
    val accounts = AccountStore(FileStore(appHomeDir()))
    val id = resolveAccountId(opts, accounts) ?: run { println("Аккаунт не найден. Укажите --account <id>."); return }
    val store = accounts.promptOverrides(id)
    when (sub) {
        "", "collect" -> ptCollect(accounts, id, store)
        "apply" -> ptApply(store, opts)
        "list" -> ptList(store)
        "reset" -> { store.clear(); println("Персонализация сброшена (аккаунт $id).") }
        else -> println("prompt-tune: неизвестная подкоманда «$sub» (collect|apply|list|reset)")
    }
}

/** Сигналы из диалогов — вход для суждения LLM (что улучшить в промтах ролей). */
private fun ptCollect(accounts: AccountStore, id: String, store: com.example.adventdesktop.data.PromptOverrideStore) {
    val repo = accounts.conversations(id)
    val convs = repo.listMetas().mapNotNull { repo.load(it.id) }
    val userMsgs = convs.flatMap { it.messages }.filter { it.role == Role.User }
        .map { it.text.trim() }.filter { it.isNotEmpty() }
    val agentMsgs = convs.flatMap { it.messages }.filter { it.role == Role.Assistant }
    fun count(pattern: String) = userMsgs.count { Regex(pattern).containsMatchIn(it.lowercase()) }

    println("[prompt-tune collect] аккаунт $id")
    println("диалогов: ${convs.size}; реплик пользователя: ${userMsgs.size}; реплик агента: ${agentMsgs.size}")
    println("готовность: ${if (userMsgs.size >= 6) "ДА (данных достаточно)" else "НЕТ (нужно ≥6 реплик пользователя)"}")
    println()
    println("СИГНАЛЫ (по словам пользователя):")
    println("- просит ссылки/источники: ${count("ссылк|источник|официальн")}")
    println("- поправляет/недоволен: ${count("\\bнет\\b|я же|не то|неверно|опять|сухо|топорн")}")
    println("- просит короче: ${count("короче|кратко|сократи|без воды")}")
    println("- просит подробнее: ${count("подробн|разверни|поясни")}")
    println("- про даты/сроки: ${count("дат|срок|когда|дедлайн")}")
    println()
    println("ПОСЛЕДНИЕ РЕПЛИКИ ПОЛЬЗОВАТЕЛЯ:")
    userMsgs.takeLast(15).forEach { println("• ${it.replace("\n", " ").take(160)}") }
    println()
    println("ТЮНЯЩИЕСЯ РОЛИ: ${TunableRole.entries.joinToString(", ") { "${it.id} — ${it.about}" }}")
    val cur = store.load()
    println("ТЕКУЩАЯ ПЕРСОНАЛИЗАЦИЯ: ${if (cur.isEmpty()) "нет" else cur.joinToString("; ") { "${it.role}: ${it.add}" }}")
}

private fun ptApply(store: com.example.adventdesktop.data.PromptOverrideStore, opts: Map<String, String>) {
    val role = opts["role"]?.trim().orEmpty()
    val add = opts["add"]?.trim().orEmpty()
    val reason = opts["reason"]?.trim().orEmpty()
    if (TunableRole.byId(role) == null) {
        println("apply: роль «$role» не тюнится. Доступно: ${TunableRole.entries.joinToString(", ") { it.id }}")
        return
    }
    if (add.isBlank()) { println("apply: нужен --add \"<текст>\""); return }
    val entry = store.add(role, add, reason)
    println("✅ Персонализация применена к роли «$role»: «${entry.add}»")
}

private fun ptList(store: com.example.adventdesktop.data.PromptOverrideStore) {
    val list = store.load()
    if (list.isEmpty()) { println("Персонализация пуста."); return }
    println("Персонализация (${list.size}):")
    list.forEach { println("- [${it.role}] ${it.add}${if (it.reason.isNotBlank()) "  (основание: ${it.reason})" else ""}") }
}
