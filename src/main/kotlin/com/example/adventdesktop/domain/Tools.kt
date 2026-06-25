package com.example.adventdesktop.domain

/**
 * Доменное описание MCP-инструмента (tool). Чистая модель — без зависимости от SDK,
 * процессов и JSON-RPC. Заполняется реализацией [ToolGateway] в data-слое.
 *
 * @param name        имя инструмента (например, `get_visa_news`)
 * @param description человекочитаемое описание (используется LLM для выбора инструмента)
 * @param inputSchema краткое описание входной схемы (тип + обязательные поля), для отладки/вывода
 */
data class Tool(
    val name: String,
    val description: String?,
    val inputSchema: String?,
)
