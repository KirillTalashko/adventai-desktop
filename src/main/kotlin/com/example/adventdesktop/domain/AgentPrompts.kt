package com.example.adventdesktop.domain

/**
 * Роли агента, ДОСТУПНЫЕ для персонализации (День 20, prompt-tune). Это whitelist (перило C): сюда входят
 * только «мягкие» роли, влияющие на стиль/что спросить/детализацию. Безопасные правила — дирижёр, инварианты,
 * переходы конечного автомата, правила отказа — тюнингу НЕ подлежат и в этот список не входят.
 */
enum class TunableRole(val id: String, val displayName: String, val about: String) {
    INTERVIEWER("interviewer", "Интервьюер (приём)", "как собирает факты и что уточняет на старте"),
    PLANNER("planner", "Планировщик", "как предлагает подходы и строит план"),
    EXECUTOR("executor", "Исполнитель", "как выполняет шаги и что детализирует"),
    VALIDATOR("validator", "Валидатор", "как проверяет результат"),
    ASSISTANT("assistant", "Ассистент", "как отвечает на вопросы по текущей задаче");

    companion object {
        fun byId(id: String): TunableRole? = entries.firstOrNull { it.id == id }
    }
}
