package com.example.adventdesktop.data

/** Вариант модели для выбора в композере. Цена — USD за 1M токенов (0 = бесплатная). */
data class ModelOption(
    val id: String,
    val title: String,
    val provider: String,          // "openrouter" | "deepseek"
    val priceInPerMillion: Double,
    val priceOutPerMillion: Double,
    val contextLimit: Int          // лимит контекстного окна (токенов) — для расчёта заполнения
) {
    val free: Boolean get() = priceInPerMillion == 0.0 && priceOutPerMillion == 0.0

    fun costUsd(promptTokens: Int, completionTokens: Int): Double =
        promptTokens / 1_000_000.0 * priceInPerMillion + completionTokens / 1_000_000.0 * priceOutPerMillion
}

object Models {
    val all: List<ModelOption> = listOf(
        ModelOption("deepseek-chat", "DeepSeek Chat", "deepseek", 0.27, 1.10, 65_536),
        ModelOption("deepseek-reasoner", "DeepSeek Reasoner", "deepseek", 0.55, 2.19, 65_536),
        ModelOption("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B (free)", "openrouter", 0.0, 0.0, 131_072),
        ModelOption("deepseek/deepseek-chat-v3-0324:free", "DeepSeek V3 (free)", "openrouter", 0.0, 0.0, 131_072),
        ModelOption("google/gemini-2.0-flash-exp:free", "Gemini 2.0 Flash (free)", "openrouter", 0.0, 0.0, 1_000_000)
    )
    val default: ModelOption = all.first()

    fun byId(id: String?): ModelOption = all.firstOrNull { it.id == id } ?: default
}

internal fun baseUrlFor(provider: String): String = when (provider) {
    "deepseek" -> "https://api.deepseek.com/chat/completions"
    else -> "https://openrouter.ai/api/v1/chat/completions"
}

/** Конфиг обращения к выбранной модели; null — нет ключа для её провайдера. */
fun resolveLlmConfig(model: ModelOption, config: DesktopConfig): LlmConfig? {
    val key = config.keyFor(model.provider)
    if (key.isBlank()) return null
    return LlmConfig(baseUrl = baseUrlFor(model.provider), apiKey = key, model = model.id)
}
