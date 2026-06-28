@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.adventdesktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import java.awt.Desktop
import java.net.URI
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.adventdesktop.data.Models
import com.example.adventdesktop.domain.Awaiting
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import com.example.adventdesktop.domain.TokenUsage

private val LogoBg = Color(0xFFDADAD6)
private val LogoFg = Color(0xFF8A8A85)

@Composable
fun App(state: ChatState) {
    var showSettings by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showInvariants by remember { mutableStateOf(false) }

    AdventTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (state.needsOnboarding) {
                Onboarding(state)
            } else {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(
                        state, Modifier.width(272.dp).fillMaxHeight(),
                        onSettings = { showSettings = true },
                        onMemory = { showMemory = true },
                        onProfile = { showProfile = true },
                        onInvariants = { showInvariants = true }
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ChatPane(state, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        if (showSettings) SettingsDialog(state) { showSettings = false }
        if (showMemory) MemoryDialog(state) { showMemory = false }
        if (showProfile) ProfileDialog(state) { showProfile = false }
        if (showInvariants) InvariantsDialog(state) { showInvariants = false }
        if (state.interviewOpen) InterviewDialog(state)
        if (state.mcpDialogOpen) McpToolsDialog(state)
    }
}

@Composable
private fun Sidebar(
    state: ChatState,
    modifier: Modifier,
    onSettings: () -> Unit,
    onMemory: () -> Unit,
    onProfile: () -> Unit,
    onInvariants: () -> Unit
) {
    Column(
        modifier = modifier.background(AppColors.sidebar).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(30.dp).background(LogoBg, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) { Text("В", color = LogoFg, fontWeight = FontWeight.Bold) }
            Text("Визовый специалист", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }

        AccountSwitcher(state, onProfile)

        Surface(
            onClick = { state.newConversation() },
            color = AppColors.accent,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("Новая сессия", color = Color.White, fontWeight = FontWeight.Medium)
            }
        }

        Text("Диалоги", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 2.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(state.conversationList, key = { it.id }) { meta ->
                val active = meta.id == state.current?.id
                Surface(
                    onClick = { state.open(meta.id) },
                    color = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            meta.title,
                            Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                        Box(
                            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).clickable { state.deleteConversation(meta.id) },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.Close, "удалить", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SidebarButton("Инварианты", null, onInvariants, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SidebarButton("Память", null, onMemory, Modifier.weight(1f))
            SidebarButton("Настройки", Icons.Filled.Settings, onSettings, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AccountSwitcher(state: ChatState, onProfile: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            onClick = { open = true },
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).background(AppColors.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text(
                        (state.activeAccount?.name?.trim()?.firstOrNull() ?: 'П').uppercaseChar().toString(),
                        color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.activeAccount?.name ?: "Профиль",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                    )
                    Text("аккаунт", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(open, { open = false }) {
            state.accountList.forEach { acc ->
                val isActive = acc.id == state.activeAccount?.id
                DropdownMenuItem(
                    text = { Text(acc.name + if (isActive) "  ✓" else "") },
                    onClick = { state.switchAccount(acc.id); open = false }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Профиль…") }, onClick = { onProfile(); open = false })
            DropdownMenuItem(text = { Text("Новый аккаунт") }, onClick = { state.startNewAccount(); open = false })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Выйти") }, onClick = { state.logout(); open = false })
            DropdownMenuItem(
                text = { Text("Удалить аккаунт", color = MaterialTheme.colorScheme.error) },
                onClick = { confirmDelete = true; open = false }
            )
        }
        if (confirmDelete) {
            val acc = state.activeAccount
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Удалить аккаунт?") },
                text = {
                    Text("Аккаунт «${acc?.name ?: "—"}» и все его данные (диалоги, память, профиль, документы) будут удалены без возможности восстановления.")
                },
                confirmButton = {
                    TextButton(onClick = { acc?.let { state.deleteAccount(it.id) }; confirmDelete = false }) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
            )
        }
    }
}

@Composable
private fun SidebarButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Row(Modifier.padding(vertical = 9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(5.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ChatPane(state: ChatState, modifier: Modifier) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(
                state.current?.title ?: "Визовый специалист",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty() && !state.loading) {
                EmptyState(state)
            } else {
                val listState = rememberLazyListState()
                val taskActive = state.task != null
                val count = state.messages.size + if (taskActive) 2 else if (state.loading) 1 else 0
                LaunchedEffect(count, state.task?.awaiting, state.loading) { if (count > 0) listState.animateScrollToItem(count - 1) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(state.messages) { MessageView(it) }
                    when {
                        taskActive -> {
                            item { TaskStatusLine(state) }
                            item { TaskInlineActions(state) }
                        }
                        state.loading -> item { TypingRow() }
                    }
                }
            }
        }

        state.error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(message, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            }
        }

        Composer(state)
    }
}

@Composable
private fun EmptyState(state: ChatState) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Чем помочь с визой?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(6.dp))
        Text("Опишите ситуацию — разберём документы, сроки и риски.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Какие документы нужны?", "Сроки оформления", "Риски отказа").forEach { hint ->
                Surface(
                    onClick = { state.input = hint; state.submitComposer() },
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(hint, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Composer(state: ChatState) {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 14.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(8.dp)) {
                TextField(
                    value = state.input,
                    onValueChange = { state.input = it },
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                        if (e.key == Key.Enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed) { state.submitComposer(); true } else false
                    },
                    placeholder = {
                        val hint = if (state.task?.awaiting == Awaiting.ANSWER) "Ответьте на уточняющие вопросы…" else "Спросите визового специалиста…"
                        Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    maxLines = 6,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AttachButton(state)
                    McpButton(state)
                    DropdownChip(state.model.title, Models.all, { it.title }) { state.chooseModel(it) }
                    Spacer(Modifier.weight(1f))
                    if (state.sessionTokens > 0) {
                        val cost = if (state.sessionCost > 0) " · $%.4f".format(state.sessionCost) else ""
                        Text("${state.sessionTokens} ток.$cost", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SendButton(state)
                }
            }
        }
        Text(
            if (state.hasKey) "Enter — отправить · Shift+Enter — перенос" else "Нет ключа — откройте «Настройки» или задайте переменную окружения",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 6.dp)
        )
    }
}

@Composable
private fun SendButton(state: ChatState) {
    val enabled = !state.loading && state.input.isNotBlank()
    Surface(
        onClick = { state.submitComposer() },
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) AppColors.accent else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.size(38.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Icon(Icons.Filled.ArrowUpward, "отправить", Modifier.size(20.dp), tint = Color.White)
            }
        }
    }
}

/** Кнопка MCP в композере (рядом с «+») — подключиться к MCP-серверу и показать список инструментов. */
@Composable
private fun McpButton(state: ChatState) {
    Surface(
        onClick = { state.connectMcp() },
        enabled = !state.mcpConnecting,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (state.mcpConnecting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
            } else {
                Icon(Icons.Filled.Extension, "Инструменты MCP", Modifier.size(20.dp), tint = AppColors.accent)
            }
        }
    }
}

/** Окно с результатом подключения к MCP: статус соединения и список доступных инструментов (День 16). */
@Composable
private fun McpToolsDialog(state: ChatState) {
    AlertDialog(
        onDismissRequest = { state.closeMcpDialog() },
        confirmButton = { TextButton(onClick = { state.closeMcpDialog() }) { Text("Закрыть") } },
        title = { Text("Инструменты MCP") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    state.mcpConnecting -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        Text("Подключаюсь к MCP-серверу…")
                    }
                    state.mcpError != null -> Text("Ошибка: ${state.mcpError}", color = MaterialTheme.colorScheme.error)
                    else -> {
                        Surface(
                            color = AppColors.accent.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    if (state.mcpIsRemote) "🟢 Удалённый MCP-сервер · развёрнут, работает 24/7"
                                    else "🟢 Локальный MCP-сервер (подпроцесс)",
                                    color = AppColors.accent, fontWeight = FontWeight.SemiBold
                                )
                                Text(state.mcpServerUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "Инструменты ниже получены С СЕРВЕРА: ${state.mcpTools.size}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        state.mcpTools.forEach { tool ->
                            Column {
                                Text("• ${tool.name}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                tool.description?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                tool.inputSchema?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        HorizontalDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TextButton(onClick = { state.checkConnection() }, enabled = !state.mcpChecking) {
                                Text("Проверить связь")
                            }
                            if (state.mcpChecking) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                            }
                            state.mcpCheckResult?.let {
                                Text(it, color = AppColors.accent, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        HorizontalDivider()
                        Text(
                            "get_visa_requirements — умная визовая сводка (источник + дата, агент внутри MCP):",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        OutlinedTextField(
                            value = state.mcpVisaCountry,
                            onValueChange = { state.mcpVisaCountry = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Страна назначения") },
                            placeholder = { Text("напр. Испания") }
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = state.mcpVisaCitizenship,
                                onValueChange = { state.mcpVisaCitizenship = it },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                label = { Text("Гражданство") }
                            )
                            TextButton(onClick = { state.callVisaRequirements() }, enabled = !state.mcpVisaLoading) {
                                Text("Узнать")
                            }
                        }
                        if (state.mcpVisaLoading) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                                Text("Собираю визовую сводку…")
                            }
                        }
                        state.mcpVisaResult?.let {
                            Text(
                                "↓ ответ получен С СЕРВЕРА (живой запрос):",
                                style = MaterialTheme.typography.labelSmall, color = AppColors.accent, fontWeight = FontWeight.SemiBold
                            )
                            ExpandableText(it)
                        }

                        // --- День 19: композиция MCP-инструментов (пайплайн) ---
                        HorizontalDivider()
                        Text(
                            "День 19 — пайплайн (композиция): visa_search → visa_summarize → save_report",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Цепочка автоматически: вывод каждого тула идёт на вход следующему.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = state.mcpPipelineQuery,
                            onValueChange = { state.mcpPipelineQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Запрос для пайплайна") },
                            maxLines = 3
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(onClick = { state.runPipelineDeterministic() }, enabled = !state.mcpPipelineRunning) {
                                Text("▶ Запустить (по коду)")
                            }
                            TextButton(onClick = { state.runPipelineAgent() }, enabled = !state.mcpPipelineRunning) {
                                Text("🤖 Запустить (агент)")
                            }
                            if (state.mcpPipelineRunning) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                            }
                        }
                        state.mcpPipelineMode?.let {
                            Text("режим: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.mcpPipelineSteps.forEach { step ->
                            val tint = if (step.ok) AppColors.accent else MaterialTheme.colorScheme.error
                            Surface(
                                color = tint.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        step.title, fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.labelMedium, color = tint
                                    )
                                    ExpandableText(step.output, collapsedLines = 5)
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

/**
 * Длинный текст с кнопкой «Развернуть/Свернуть» (свёрнут — несколько строк) и возможностью выделить/скопировать.
 * Состояние сбрасывается при смене текста (`remember(text)`). Используется для длинных ответов MCP/пайплайна.
 */
@Composable
private fun ExpandableText(text: String, collapsedLines: Int = 6) {
    var expanded by remember(text) { mutableStateOf(false) }
    val isLong = text.length > 280 || text.count { it == '\n' } >= collapsedLines
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
    if (isLong) {
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                if (expanded) "Свернуть ▲" else "Развернуть весь ответ ▼",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.accent,
            )
        }
    }
}

@Composable
private fun <T> DropdownChip(label: String, items: List<T>, itemLabel: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(open, { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(itemLabel(item)) }, onClick = { onSelect(item); open = false })
            }
        }
    }
}

@Composable
private fun MessageView(message: Message) {
    if (message.role == Role.User) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 560.dp)) {
                Text(message.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(end = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Визовый специалист", style = MaterialTheme.typography.labelMedium, color = AppColors.accent, fontWeight = FontWeight.SemiBold)
            parseSegments(message.text).forEach { seg ->
                when (seg) {
                    is Segment.Plain -> Text(linkify(seg.text, AppColors.accent), color = MaterialTheme.colorScheme.onSurface)
                    is Segment.Checklist -> ChecklistView(seg.items)
                }
            }
            message.usage?.let { TokenLine(it) }
        }
    }
}

@Composable
private fun TokenLine(usage: TokenUsage) {
    Text(
        "промпт ${usage.prompt} · ответ ${usage.completion} · всего ${usage.total}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ChecklistView(items: List<Pair<String, String>>) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items.forEach { (name, status) ->
                val color = statusColor(status)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).background(color, CircleShape))
                    Text(name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
                        Text(status, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = color, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = AppColors.accent)
        Text("печатает…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

private fun statusColor(status: String): Color {
    val s = status.trim().lowercase()
    return when {
        s.startsWith("провер") -> StatusColors.verified
        s.startsWith("загруж") -> StatusColors.uploaded
        s.startsWith("не хват") || s.startsWith("нет") -> StatusColors.missing
        else -> StatusColors.needed
    }
}

// --- Парсинг блока [checklist] ---

private val URL_REGEX = Regex("https?://[^\\s)\\]]+")

/** Превращает голые URL в тексте в кликабельные ссылки (по клику открывается системный браузер). */
private fun linkify(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in URL_REGEX.findAll(text)) {
        append(text.substring(last, m.range.first))
        val raw = m.value
        val url = raw.trimEnd('.', ',', ';', ')', '»', '"', '!', '?')   // не цеплять хвостовую пунктуацию
        val styles = TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
        withLink(LinkAnnotation.Url(url, styles) { link ->
            runCatching {
                val u = (link as? LinkAnnotation.Url)?.url ?: return@runCatching
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(u))
            }
        }) { append(url) }
        if (raw.length > url.length) append(raw.substring(url.length))
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

internal sealed interface Segment {
    data class Plain(val text: String) : Segment
    data class Checklist(val items: List<Pair<String, String>>) : Segment
}

internal fun parseSegments(text: String): List<Segment> {
    val segments = mutableListOf<Segment>()
    val plain = StringBuilder()
    val items = mutableListOf<Pair<String, String>>()
    var inChecklist = false

    fun flushPlain() {
        if (plain.isNotBlank()) segments.add(Segment.Plain(plain.toString().trim()))
        plain.clear()
    }

    text.split("\n").forEach { line ->
        val trimmed = line.trim()
        when {
            trimmed.equals("[checklist]", ignoreCase = true) -> { flushPlain(); inChecklist = true; items.clear() }
            trimmed.equals("[/checklist]", ignoreCase = true) -> {
                if (items.isNotEmpty()) segments.add(Segment.Checklist(items.toList()))
                inChecklist = false; items.clear()
            }
            inChecklist -> {
                val body = trimmed.removePrefix("-").trim()
                if (body.isNotEmpty()) {
                    val parts = body.split(";")
                    val name = parts.getOrNull(0)?.trim().orEmpty()
                    val status = parts.getOrNull(1)?.trim().orEmpty().ifBlank { "нужен" }
                    if (name.isNotEmpty()) items.add(name to status)
                }
            }
            else -> plain.append(line).append('\n')
        }
    }
    flushPlain()
    if (inChecklist && items.isNotEmpty()) segments.add(Segment.Checklist(items.toList()))
    return segments
}
