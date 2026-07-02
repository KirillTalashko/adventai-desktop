package com.example.adventdesktop.data

import java.io.File

/** Настройки приложения, редактируемые в UI. Хранит оба ключа — провайдер выбирается моделью. */
data class DesktopConfig(
    val openrouterKey: String,
    val deepseekKey: String,
    val modelId: String,
    // День 18: удалённый MCP-сервер (VPS). Если URL задан — агент ходит за инструментами туда (SSE+токен).
    val mcpRemoteUrl: String = "",
    val mcpRemoteToken: String = "",
    // День 20: переключатели коннекторов агента (MCP vs локальный Skill+CLI).
    val mcpEnabled: Boolean = true,
    val skillDocsEnabled: Boolean = false,
    val skillPromptTuneEnabled: Boolean = false,
    // День 20: подключить СТОРОННЕЕ MCP (server-everything через npx) — второй сервер в маршрутизаторе.
    val extraMcpEnabled: Boolean = false,
    // Режим разработчика: показывать инженерные витрины (инструменты MCP, коннекторы, демо-пайплайн). По умолчанию скрыто.
    val developerMode: Boolean = false,
    // Оформление (аудит Рамса #9): тёмная тема и «меньше анимаций» (reduced-motion). По умолчанию — светлая, анимации вкл.
    val darkTheme: Boolean = false,
    val reducedMotion: Boolean = false,
    // HTTP-прокси для всех запросов приложения (LLM + удалённый MCP). Для сетей с локальным туннелем,
    // где прямой выход/DNS закрыты (напр. http://127.0.0.1:10809). Пусто → прямое соединение.
    val httpProxy: String = "",
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
            modelId = dto.modelId,
            mcpRemoteUrl = dto.mcpRemoteUrl.ifBlank { System.getenv("MCP_REMOTE_URL").orEmpty() }.trim(),
            mcpRemoteToken = dto.mcpRemoteToken.ifBlank { System.getenv("MCP_REMOTE_TOKEN").orEmpty() }.trim(),
            mcpEnabled = dto.mcpEnabled,
            skillDocsEnabled = dto.skillDocsEnabled,
            skillPromptTuneEnabled = dto.skillPromptTuneEnabled,
            extraMcpEnabled = dto.extraMcpEnabled,
            developerMode = dto.developerMode,
            darkTheme = dto.darkTheme,
            reducedMotion = dto.reducedMotion,
            httpProxy = dto.httpProxy.ifBlank { System.getenv("HTTPS_PROXY") ?: System.getenv("HTTP_PROXY").orEmpty() }.trim(),
        )
    }

    fun save(config: DesktopConfig) {
        store.writeText(
            file,
            appJson.encodeToString(
                AppConfigDto(
                    openrouterKey = config.openrouterKey,
                    deepseekKey = config.deepseekKey,
                    modelId = config.modelId,
                    mcpRemoteUrl = config.mcpRemoteUrl,
                    mcpRemoteToken = config.mcpRemoteToken,
                    mcpEnabled = config.mcpEnabled,
                    skillDocsEnabled = config.skillDocsEnabled,
                    skillPromptTuneEnabled = config.skillPromptTuneEnabled,
                    extraMcpEnabled = config.extraMcpEnabled,
                    developerMode = config.developerMode,
                    darkTheme = config.darkTheme,
                    reducedMotion = config.reducedMotion,
                    httpProxy = config.httpProxy,
                )
            )
        )
    }
}
