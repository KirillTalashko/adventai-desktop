package com.example.adventdesktop.domain

/**
 * Инвариант — правило, которое ассистент НЕ имеет права нарушать (День 14). Хранится ОТДЕЛЬНО от диалога:
 * встроенные жёсткие правила — в коде ([BUILT_IN_INVARIANTS]), пользовательские — в `invariants.json`
 * каталога аккаунта. Инжектится в системный промпт и проверяется стражем ([InvariantGuard]).
 */
data class Invariant(
    val id: String,
    val text: String,
    val builtIn: Boolean = false,   // встроенное жёсткое правило (нельзя удалить/выключить)
    val active: Boolean = true
)

/** Встроенные жёсткие инварианты визового консультанта — всегда активны. */
val BUILT_IN_INVARIANTS: List<Invariant> = listOf(
    Invariant("legal-only", "Предлагать только законные пути. Никогда не советовать поддельные документы, ложные сведения в анкете или обход закона.", builtIn = true),
    Invariant("no-guarantee", "Не давать юридических гарантий одобрения визы. Всегда отмечать риски и необходимость сверяться с официальными источниками.", builtIn = true),
    Invariant("scope-visa", "Оставаться в роли визового консультанта: не давать врачебных, налоговых или иных профессиональных советов вне визовой темы.", builtIn = true)
)

/** Блок инвариантов для инжекта в системный промпт (только активные). Пусто, если активных нет. */
fun renderInvariantsBlock(invariants: List<Invariant>): String {
    val active = invariants.filter { it.active }
    if (active.isEmpty()) return ""
    return buildString {
        append("[ИНВАРИАНТЫ — правила, которые НЕЛЬЗЯ нарушать]\n")
        active.forEach { append("- ").append(it.text).append('\n') }
        append("Учитывай их в рассуждениях. Если запрос пользователя противоречит инварианту — НЕ предлагай ")
        append("такое решение: прямо откажись, назови нарушаемый инвариант и кратко объясни причину; при ")
        append("возможности предложи допустимую альтернативу.")
    }.trim()
}
