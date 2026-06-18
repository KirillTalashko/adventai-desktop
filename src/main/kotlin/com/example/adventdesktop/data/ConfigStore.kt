package com.example.adventdesktop.data

import java.io.File

/** Настройки приложения, редактируемые в UI. Хранит оба ключа — провайдер выбирается моделью. */
data class DesktopConfig(
    val openrouterKey: String,
    val deepseekKey: String,
    val modelId: String
) {
    fun keyFor(provider: String): String = if (provider == "deepseek") deepseekKey else openrouterKey
}

/**
 * Хранит настройки в `~/.adventai/config.json`. Если ключ в файле пуст — берёт его из переменных
 * окружения `OPENROUTER_API_KEY` / `DEEPSEEK_API_KEY`.
 */
class ConfigStore(private val store: FileStore) {
    private val file = File(store.root, "config.json")

    fun load(): DesktopConfig {
        val dto = runCatching {
            store.readText(file).takeIf { it.isNotBlank() }?.let { appJson.decodeFromString<AppConfigDto>(it) }
        }.getOrNull() ?: AppConfigDto()

        val openrouter = dto.openrouterKey.ifBlank { System.getenv("OPENROUTER_API_KEY").orEmpty() }
        val deepseek = dto.deepseekKey.ifBlank { System.getenv("DEEPSEEK_API_KEY").orEmpty() }
        return DesktopConfig(
            openrouterKey = openrouter.trim(),
            deepseekKey = deepseek.trim(),
            modelId = dto.modelId
        )
    }

    fun save(config: DesktopConfig) {
        store.writeText(
            file,
            appJson.encodeToString(
                AppConfigDto(
                    openrouterKey = config.openrouterKey,
                    deepseekKey = config.deepseekKey,
                    modelId = config.modelId
                )
            )
        )
    }
}
