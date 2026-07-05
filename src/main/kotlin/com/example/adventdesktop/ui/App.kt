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
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WarningAmber
import com.example.adventdesktop.domain.TunableRole
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.example.adventdesktop.domain.rag.GoldAnswer
import com.example.adventdesktop.domain.rag.GoldRetrieval
import com.example.adventdesktop.domain.rag.RagAnswer
import com.example.adventdesktop.domain.rag.RagSource
import com.example.adventdesktop.domain.rag.RerankMode
import com.example.adventdesktop.domain.rag.RetrievalTrace
import com.example.adventdesktop.domain.rag.RewriteOutcome
import com.example.adventdesktop.domain.rag.Scored
import com.example.adventdesktop.domain.rag.ragLooksLikeRefusal

private val LogoBg = Color(0xFFDADAD6)
private val LogoFg = Color(0xFF8A8A85)

@Composable
fun App(state: ChatState) {
    var showSettings by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showInvariants by remember { mutableStateOf(false) }

    AdventTheme(dark = state.config.darkTheme) {
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
        if (state.connectorsOpen) ConnectorsDialog(state)
        if (state.ragOpen) RagDialog(state)
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
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(30.dp).background(LogoBg, RoundedCornerShape(Radii.sm)),
                contentAlignment = Alignment.Center
            ) { Text("В", color = LogoFg, fontWeight = FontWeight.Bold) }
            Text("Визовый специалист", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }

        AccountSwitcher(state, onProfile)

        Surface(
            onClick = { state.newConversation() },
            color = AppColors.accent,
            shape = RoundedCornerShape(Radii.md),
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
                    shape = RoundedCornerShape(Radii.sm),
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
                            Modifier.size(22.dp).clip(RoundedCornerShape(Radii.xs)).clickable { state.deleteConversation(meta.id) },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.Close, "удалить", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SidebarButton("Правила", null, onInvariants, Modifier.fillMaxWidth())
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
            shape = RoundedCornerShape(Radii.sm),
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
        shape = RoundedCornerShape(Radii.sm),
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
                LaunchedEffect(count, state.task?.awaiting, state.loading) {
                    if (count > 0) {
                        if (state.config.reducedMotion) listState.scrollToItem(count - 1)
                        else listState.animateScrollToItem(count - 1)
                    }
                }
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
                    shape = RoundedCornerShape(Radii.md),
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
            shape = RoundedCornerShape(Radii.lg),
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
                    // Инженерные витрины (MCP/коннекторы) — только в режиме разработчика (Настройки).
                    if (state.config.developerMode) {
                        McpButton(state)
                        ConnectorsButton(state)
                        RagButton(state)
                    }
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

/** Кнопка «Коннекторы агента» (День 20): включить/выключить MCP и локальные навыки (Skill + CLI). */
@Composable
private fun ConnectorsButton(state: ChatState) {
    Surface(
        onClick = { state.openConnectors() },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Tune, "Коннекторы агента", Modifier.size(20.dp), tint = AppColors.accent)
        }
    }
}

/** Кнопка «Индексация знаний (RAG)» (День 21) — открыть панель построения и сравнения индекса. */
@Composable
private fun RagButton(state: ChatState) {
    Surface(
        onClick = { state.openRag() },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Storage, "Индексация знаний (RAG)", Modifier.size(20.dp), tint = AppColors.accent)
        }
    }
}

/** Окно «Индексация знаний (RAG)» (День 21): построить индекс, сравнить 2 стратегии chunking, найти. */
@Composable
private fun RagDialog(state: ChatState) {
    AlertDialog(
        onDismissRequest = { state.closeRag() },
        confirmButton = { TextButton(onClick = { state.closeRag() }) { Text("Закрыть") } },
        title = { Text("Индексация знаний (RAG)") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Вводная рамка — mental model простыми словами.
                Text(
                    "RAG = агент отвечает по ВАШИМ документам со ссылками, а не из общей памяти модели. Ниже 3 шага.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface
                )
                state.ragNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.accent)
                }

                // === Шаг 1 — индекс ===
                Text("Шаг 1 · Построить индекс базы", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = AppColors.accent)
                Text(
                    "База знаний → чанки → эмбеддинги → SQLite-индекс с метаданными. Документов в корпусе: ${state.ragDocCount}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ConnectorToggleRow(
                    "Эмбеддер: Ollama (nomic-embed-text)",
                    if (state.ragUseOllama) "локально, 768-мерные вектора; нужна запущенная Ollama" else "выключено → офлайн-фолбэк (hashing, без сети)",
                    state.ragUseOllama
                ) { state.chooseRagEmbedder(it) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { state.buildIndex() },
                        enabled = !state.ragBuilding,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)
                    ) { Text("Построить индекс") }
                    if (state.ragBuilding) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        Text(state.ragProgress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.ragComparison?.let { RagComparisonTable(it) }

                // === Шаг 2 — спросить, сравнить два режима ===
                HorizontalDivider()
                Text("Шаг 2 · Спросить — и сравнить два режима", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = AppColors.accent)
                Text(
                    "Один вопрос — два ответа. Без RAG: из общей памяти модели (без источника, может ошибиться или выдумать). " +
                        "С RAG: по найденным фрагментам вашей базы, со ссылками; если данных нет — честно скажет.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                RagPipelineControls(state)
                OutlinedTextField(
                    value = state.ragQuery, onValueChange = { state.ragQuery = it },
                    label = { Text("Вопрос") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { state.ragCompare() },
                        enabled = state.ragComparison != null && !state.ragAnswering,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)
                    ) { Text("Спросить в обоих режимах") }
                    if (state.ragAnswering) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { state.askNegativeExample() }, enabled = state.ragComparison != null && !state.ragAnswering) {
                        Text("Пример: вопрос не из базы", style = MaterialTheme.typography.labelSmall)
                    }
                }
                val rag = state.ragAnswerRag
                val plain = state.ragAnswerPlain
                // Ловушка: С RAG отказался, а Без RAG всё равно ответил → плашка «это выдумка» прямо на карточке.
                val plainInvented = rag != null && plain != null &&
                    ragLooksLikeRefusal(rag.text) && !ragLooksLikeRefusal(plain.text)
                plain?.let {
                    RagAnswerCard(
                        it, warn = true, whatIs = "из общей памяти модели — без источника",
                        note = if (plainInvented) "⚠ Похоже на выдумку: в вашей базе данных на этот вопрос нет, но модель всё равно ответила уверенно." else null
                    )
                }
                rag?.let { RagAnswerCard(it, warn = false, whatIs = "по вашей базе — со ссылками на источники") }
                if (rag != null && rag.sources.isNotEmpty()) RagEvidence(rag.sources, ragLooksLikeRefusal(rag.text))
                state.ragTrace?.let { RagTraceView(it) }
                if (rag != null && plain != null) {
                    Surface(color = AppColors.accent.copy(alpha = 0.08f), shape = RoundedCornerShape(Radii.xs), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("В чём разница", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = AppColors.accent)
                            if (plainInvented) {
                                Text("• Этого нет в вашей базе. С RAG честно отказался, а Без RAG выдумал правдоподобный ответ. Ровно ради этого и нужен RAG.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                            } else {
                                Text("• С RAG опирается на ${rag.sources.size} фрагмент(ов) вашей базы (выше). Без RAG — 0 источников, ответ из общей памяти модели.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                                Text("• Если ответа в базе нет — С RAG честно откажет, а Без RAG может выдумать. Проверьте кнопкой «Пример: вопрос не из базы».", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }

                // === Шаг 3 — сравнение качества на 10 контрольных вопросах ===
                HorizontalDivider()
                Text("Шаг 3 · Проверить на 10 контрольных вопросах", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = AppColors.accent)

                Text(
                    "А. Насколько хорошо работает ПОИСК (без участия LLM). Прогоняем 10 вопросов и смотрим, находит ли " +
                        "поиск ПРАВИЛЬНЫЙ документ, чтобы по нему ответить. Сравниваем старый поиск (День 22) и новый " +
                        "(День 23: умная нарезка + реранк + порог). Чем лучше поиск — тем реже промахи и меньше мусора на " +
                        "вопросах, которых в базе нет.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { state.runGoldRetrieval() },
                        enabled = state.ragComparison != null && !state.goldRetrievalRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)
                    ) { Text("Сравнить поиск (без/с фильтром)") }
                    if (state.goldRetrievalRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        Text(state.goldRetrievalProgress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.goldRetrieval.isNotEmpty()) GoldRetrievalView(state.goldRetrieval)

                Text(
                    "Б. Качество ОТВЕТОВ в обоих режимах (нужен LLM): с RAG — ответ со ссылкой + отказ на ловушке; без RAG — без ссылок, на ловушке выдумка.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { state.runGoldAnswers() },
                        enabled = state.ragComparison != null && !state.goldRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)
                    ) { Text("Прогнать ответы (10 вопросов)") }
                    if (state.goldRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        Text(state.goldProgress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.goldAnswers.isNotEmpty()) GoldAnswersView(state.goldAnswers)
            }
        }
    )
}

private fun rerankLabel(m: RerankMode): String = when (m) {
    RerankMode.OFF -> "Выкл"; RerankMode.HEURISTIC -> "Эвристика"; RerankMode.LLM -> "LLM"
}

private fun hitMark(h: Boolean?): String = when (h) { true -> "✓"; false -> "✗"; else -> "—" }

/** Настройки улучшенного поиска (День 23): стратегия, реранк, query rewrite, порог отсечения. */
@Composable
private fun RagPipelineControls(state: ChatState) {
    Surface(color = AppColors.accent.copy(alpha = 0.05f), shape = RoundedCornerShape(Radii.xs), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Улучшенный поиск (День 23)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = AppColors.accent)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Стратегия:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownChip(state.ragStrategy, listOf("contextual", "structural", "fixed"), { it }) { state.ragStrategy = it }
                Text("Реранк:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownChip(rerankLabel(state.ragRerank), RerankMode.entries.toList(), { rerankLabel(it) }) { state.ragRerank = it }
            }
            ConnectorToggleRow("Query rewrite (LLM)", "переписать вопрос в поисковый запрос перед эмбеддингом", state.ragRewrite) { state.ragRewrite = it }
            if (state.ragRewrite) RewriteStatusLine(state.ragTrace)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Порог: %.2f".format(state.ragFloor), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = state.ragFloor, onValueChange = { state.ragFloor = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Индикатор под тумблером «Query rewrite»: по последнему прогону показывает, ПЕРЕПИСАЛ ли LLM запрос,
 * вернул то же или вызов упал — чтобы наглядно видеть, что тумблер реально что-то делает.
 */
@Composable
private fun RewriteStatusLine(t: RetrievalTrace?) {
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    val (text, color) = when (t?.rewrite) {
        null, RewriteOutcome.OFF ->
            "включён — задайте вопрос и запустите поиск, чтобы увидеть результат" to onVar
        RewriteOutcome.REWRITTEN ->
            "✏ переписал: «${t.originalQuery}» → «${t.usedQuery}»" to AppColors.accent
        RewriteOutcome.UNCHANGED ->
            "✔ вернул то же (вопрос уже краткий — переписывать нечего)" to onVar
        RewriteOutcome.FAILED ->
            "⚠ вызов не удался (сеть/лимит) — искали по исходному вопросу" to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(start = 4.dp))
}

/** Трейс второго этапа: переписанный запрос + top-K ДО (cosine) и ПОСЛЕ (реранк+фильтр) — видно реордер/отсев. */
@Composable
private fun RagTraceView(t: RetrievalTrace) {
    @Composable
    fun hits(list: List<Scored>, color: androidx.compose.ui.graphics.Color) = list.forEach { s ->
        Text("  %.3f · %s › %s".format(s.score, s.chunk.meta.source, s.chunk.meta.section.ifBlank { "-" }), style = MaterialTheme.typography.labelSmall, color = color)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = RoundedCornerShape(Radii.xs), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Второй этап поиска (реранк + фильтр)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, color = AppColors.accent)
            when (t.rewrite) {
                RewriteOutcome.REWRITTEN -> Text("Query rewrite: «${t.originalQuery}» → «${t.usedQuery}»", style = MaterialTheme.typography.labelSmall, color = AppColors.accent)
                RewriteOutcome.UNCHANGED -> Text("Query rewrite включён, но запрос не изменился (вопрос уже краткий).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RewriteOutcome.FAILED -> Text("Query rewrite не сработал (сеть/лимит) — искали по исходному вопросу.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                RewriteOutcome.OFF -> {}
            }
            Text("Пул: ${t.poolSize} → после порога: ${t.survived} (отсёк ${t.droppedByFilter}) → в ответ: ${t.after.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("top-K ДО (сырой cosine):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            hits(t.before, MaterialTheme.colorScheme.onSurface)
            Text("top-K ПОСЛЕ (реранк+фильтр):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (t.after.isEmpty()) Text("  — пусто: нерелевантно → честный отказ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            else hits(t.after, AppColors.accent)
        }
    }
}

/** Сравнение качества поиска по набору: понятная сводка + разбор «старый поиск → новый поиск». */
@Composable
private fun GoldRetrievalView(items: List<GoldRetrieval>) {
    val pos = items.filter { !it.q.isNegative }
    val baseHit = pos.count { it.baseHit == true }
    val impHit = pos.count { it.improvedHit == true }
    val fixedCount = pos.count { it.baseHit != true && it.improvedHit == true }
    val neg = items.firstOrNull { it.q.isNegative }
    Surface(color = AppColors.accent.copy(alpha = 0.06f), shape = RoundedCornerShape(Radii.xs), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Итог: старый поиск → новый поиск", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = AppColors.accent)
            Text(
                "Нашли ПРАВИЛЬНЫЙ документ: было $baseHit из ${pos.size} → стало $impHit из ${pos.size}" +
                    if (fixedCount > 0) " (починено вопросов: $fixedCount)." else ".",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface
            )
            neg?.let {
                Text(
                    "Вопрос-ловушка (ответа в базе нет): старый поиск притащил ${it.baseSources.size} лишних кусок(ов) — по ним модель могла бы выдумать; " +
                        "новый — ${it.improvedSources.size}" + if (it.improvedSources.isEmpty()) " (ничего → агент честно откажется)." else ".",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface
                )
            }
            HorizontalDivider()
            items.forEach { r -> GoldRetrievalRow(r) }
        }
    }
}

/** Одна строка сравнения поиска: вопрос + какой документ нужен + вердикт «было → стало» (без шумных списков). */
@Composable
private fun GoldRetrievalRow(r: GoldRetrieval) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text("#${r.q.id}. ${r.q.question}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        if (r.q.isNegative) {
            Text(
                "В базе ответа нет. Было: нашёл ${r.baseSources.size} лишних кусок(ов) → стало: ${r.improvedSources.size}" +
                    if (r.improvedSources.isEmpty()) " (ничего → отказ)" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val flipped = r.baseHit != true && r.improvedHit == true
            val verdict = when {
                flipped -> "починено: раньше не находили → теперь находим"
                r.improvedHit == true -> "нашли (было и осталось верно)"
                else -> "не нашли"
            }
            Text(
                "Нужен: ${r.q.sources.joinToString(" / ")} · было ${hitMark(r.baseHit)} → стало ${hitMark(r.improvedHit)} — $verdict",
                style = MaterialTheme.typography.labelSmall,
                color = if (flipped) AppColors.accent else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Карточка ответа одного режима: ярлык (режим + «что это»), техстрока, текст, использованные источники. */
@Composable
private fun RagAnswerCard(a: RagAnswer, warn: Boolean, whatIs: String, note: String? = null) {
    val accent = if (warn) MaterialTheme.colorScheme.error else AppColors.accent
    Surface(color = accent.copy(alpha = 0.08f), shape = RoundedCornerShape(Radii.xs), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${a.mode} — $whatIs", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = accent)
            note?.let { Text(it, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            val toks = a.usage?.let { "${it.total} ток." } ?: "—"
            val ctx = if (a.contextChars > 0) "контекст ${a.contextChars} симв." else "без контекста базы"
            Text("$toks · $ctx", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExpandableText(a.text, collapsedLines = 6)
            if (a.sources.isNotEmpty()) {
                Text("Источники:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                a.sources.forEach { s ->
                    Text("[${s.n}] %.3f · %s › %s".format(s.score, s.source, s.section), style = MaterialTheme.typography.labelSmall, color = accent)
                }
            }
        }
    }
}

/** «На чём основан ответ С RAG»: фрагменты базы, что ушли в контекст. При отказе честно поясняем, почему. */
@Composable
private fun RagEvidence(sources: List<RagSource>, refused: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (refused)
                "Поиск всё равно вернул ближайшие фрагменты, но ни один не отвечает на вопрос — поэтому С RAG честно " +
                    "сказал «нет данных». (Векторный поиск всегда отдаёт k ближайших; отсечь нерелевантное помогает " +
                    "порог близости и reranking — День 23.)"
            else
                "На чём основан ответ С RAG (эти выдержки из вашей базы дописаны в запрос):",
            style = MaterialTheme.typography.labelSmall,
            color = if (refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        sources.forEach { s ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), shape = RoundedCornerShape(Radii.xs), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("[${s.n}] %s › %s · %.3f".format(s.source, s.section, s.score), style = MaterialTheme.typography.labelSmall, color = AppColors.accent)
                    ExpandableText(s.text, collapsedLines = 3)
                }
            }
        }
    }
}

/** Итог прогона набора (Вариант B): сводка-история + разбор по вопросам (вердикт С RAG / Без RAG + ответы). */
@Composable
private fun GoldAnswersView(items: List<GoldAnswer>) {
    val positives = items.filter { !it.q.isNegative }
    val withSources = positives.count { it.ragHasSources }
    val onTarget = positives.count { it.ragOnTarget == true }
    val neg = items.firstOrNull { it.q.isNegative }
    Surface(color = AppColors.accent.copy(alpha = 0.06f), shape = RoundedCornerShape(Radii.xs), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Итог сравнения", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = AppColors.accent)
            Text(
                "• С RAG: $withSources из ${positives.size} ответов со ссылками, из них $onTarget по нужному источнику." +
                    (neg?.let { "  На ловушке: ${if (it.ragRefused) "честно отказался" else "ответил — проверьте"}." } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "• Без RAG: 0 ответов со ссылками." +
                    (neg?.let { "  На ловушке: ${if (it.plainRefused) "отказался" else "выдумал ответ"}." } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface
            )
            HorizontalDivider()
            items.forEach { GoldAnswerRow(it) }
        }
    }
}

/** Одна строка разбора: вопрос + короткий вердикт по обоим режимам + раскрытие обоих ответов. */
@Composable
private fun GoldAnswerRow(a: GoldAnswer) {
    val ragMark = when {
        a.q.isNegative -> if (a.ragRefused) "✓ честный отказ" else "⚠ ответил (данных нет)"
        a.ragOnTarget == true -> "✓ по нужному источнику"
        a.ragHasSources -> "⚠ со ссылкой, но не на нужный документ"
        else -> "✗ без источника"
    }
    val plainMark = when {
        a.q.isNegative -> if (a.plainRefused) "отказался" else "выдумал"
        else -> "без ссылок"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("#${a.q.id}. ${a.q.question}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Text("С RAG: $ragMark   ·   Без RAG: $plainMark", style = MaterialTheme.typography.labelSmall, color = AppColors.accent)
        ExpandableText("С RAG — ${a.rag.text}", collapsedLines = 2)
        ExpandableText("Без RAG — ${a.plain.text}", collapsedLines = 2)
    }
}

/** Таблица сравнения 2 стратегий chunking: метрика | fixed | structural. */
@Composable
private fun RagComparisonTable(cmp: RagComparisonView) {
    @Composable
    fun row(label: String, fixed: String, structural: String, contextual: String, header: Boolean = false) {
        val weight = if (header) FontWeight.SemiBold else FontWeight.Normal
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, Modifier.weight(1.5f), style = MaterialTheme.typography.labelSmall, fontWeight = weight, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fixed, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = weight)
            Text(structural, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = weight)
            Text(contextual, Modifier.weight(1.1f), style = MaterialTheme.typography.labelSmall, fontWeight = weight, color = if (header) AppColors.accent else Color.Unspecified)
        }
    }
    Surface(color = AppColors.accent.copy(alpha = 0.06f), shape = RoundedCornerShape(Radii.xs), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val f = cmp.fixed
            val s = cmp.structural
            val c = cmp.contextual
            row("", "fixed", "structural", "contextual★", header = true)
            row("чанков", "${f.chunks}", "${s.chunks}", "${c.chunks}")
            row("ср. символов", "${f.avgChars}", "${s.avgChars}", "${c.avgChars}")
            row("ср. токенов", "${f.avgTokens}", "${s.avgTokens}", "${c.avgTokens}")
            row("разделов", "${f.sections}", "${s.sections}", "${c.sections}")
            row("время, мс", "${f.buildMs}", "${s.buildMs}", "${c.buildMs}")
            Text(
                "★ contextual — боевая стратегия (День 23): границы по разделам + размер/overlap + хлебные крошки «документ › раздел» в тексте чанка (эмбеддится с темой). Она — по умолчанию для ответов; fixed/structural остались для сравнения.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** Строка-коннектор с переключателем (как в панели коннекторов: название + описание + ползунок). */
@Composable
private fun ConnectorToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = AppColors.accent))
    }
}

/** Результат одного прогона коннектора (MCP/Skill): токены, след вызовов и ответ. */
@Composable
private fun ConnectorResultView(label: String, run: ConnectorRun?) {
    if (run == null) return
    Surface(color = AppColors.accent.copy(alpha = 0.06f), shape = RoundedCornerShape(Radii.xs), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val toks = run.usage?.let { "prompt ${it.prompt} · total ${it.total}" } ?: "—"
            Text("$label · токены: $toks", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = AppColors.accent)
            run.steps.forEach { Text(it.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            ExpandableText(run.reply, collapsedLines = 5)
        }
    }
}

/** Окно «Коннекторы агента» (День 20): переключатели MCP/Skill + сравнение на одном вопросе (токены). */
@Composable
private fun ConnectorsDialog(state: ChatState) {
    AlertDialog(
        onDismissRequest = { state.closeConnectors() },
        confirmButton = { TextButton(onClick = { state.closeConnectors() }) { Text("Закрыть") } },
        title = { Text("Коннекторы агента") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Источники инструментов агента. MCP грузит схемы тулзов в КАЖДЫЙ запрос; Skill + CLI — локально, по требованию (дешевле по токенам).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ConnectorToggleRow("MCP — visa-info", "удалённый сервер: актуальные требования, поиск, дайджест", state.mcpEnabled) { state.setMcpEnabled(it) }
                ConnectorToggleRow("MCP — server-everything (стороннее)", "локальный npx-сервер по stdio: echo, add… (второй MCP через маршрутизатор)", state.extraMcpEnabled) { state.setExtraMcpEnabled(it) }
                HorizontalDivider()
                ConnectorToggleRow("Skill — документы", "локально: visa-cli docs (проверка приложенных файлов)", state.skillDocsEnabled) { state.setSkillDocsEnabled(it) }
                ConnectorToggleRow("Skill — автоулучшение промтов", "локально: анализ диалогов и точечные предложения", state.skillPromptTuneEnabled) { state.setSkillPromptTuneEnabled(it) }
                HorizontalDivider()
                Text("Сравнить на одном вопросе:", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                OutlinedTextField(
                    value = state.connectorAsk,
                    onValueChange = { state.connectorAsk = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Вопрос агенту") },
                    maxLines = 3
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { state.askViaMcp() }, enabled = !state.connectorRunning) { Text("Через MCP") }
                    TextButton(onClick = { state.askViaSkill() }, enabled = !state.connectorRunning) { Text("Через Skill") }
                    if (state.connectorRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        state.connectorVia?.let { Text("идёт через $it…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                ConnectorResultView("MCP", state.connectorMcpRun)
                ConnectorResultView("Skill + CLI", state.connectorSkillRun)
                val m = state.connectorMcpRun?.usage?.prompt
                val s = state.connectorSkillRun?.usage?.prompt
                if (m != null && s != null) {
                    val ratio = if (s > 0) "%.1f×".format(m.toDouble() / s) else "—"
                    Text("Δ prompt-токенов: MCP $m vs Skill $s — MCP дороже в $ratio", fontWeight = FontWeight.SemiBold, color = AppColors.accent)
                }

                // --- День 20: навык автоулучшения промтов (предложения с подтверждением) ---
                if (state.skillPromptTuneEnabled) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Автоулучшение промтов", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        TextButton(onClick = { state.analyzePrompts() }, enabled = !state.promptTuneRunning) { Text("Проанализировать") }
                        if (state.promptTuneRunning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                    }
                    state.promptTuneNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    state.promptProposals.forEach { p ->
                        val roleName = TunableRole.byId(p.role)?.displayName ?: p.role
                        Surface(color = AppColors.accent.copy(alpha = 0.08f), shape = RoundedCornerShape(Radii.xs), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(roleName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = AppColors.accent)
                                Text("Добавить: ${p.add}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                Text("Почему: ${p.why}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { state.applyProposal(p) }) { Text("Применить") }
                                    TextButton(onClick = { state.dismissProposal(p) }) { Text("Отклонить") }
                                }
                            }
                        }
                    }
                    val pers = state.personalization
                    if (pers.isNotEmpty()) {
                        Text("Активная персонализация (${pers.size}):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        pers.forEach {
                            Text("• [${TunableRole.byId(it.role)?.displayName ?: it.role}] ${it.add}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                        TextButton(onClick = { state.resetPersonalization() }) { Text("Сбросить персонализацию") }
                    }
                }
            }
        }
    )
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
                            shape = RoundedCornerShape(Radii.sm),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                // Реально подключённые серверы — по меткам [server] в описаниях (их ставит McpRouter).
                                val byServer = state.mcpTools.groupingBy {
                                    Regex("^\\[(.+?)]").find(it.description.orEmpty())?.groupValues?.get(1) ?: ""
                                }.eachCount().filterKeys { it.isNotEmpty() }
                                if (state.extraMcpEnabled && byServer.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(14.dp), tint = AppColors.accent)
                                        Text("Подключено MCP-серверов: ${byServer.size}", color = AppColors.accent, fontWeight = FontWeight.SemiBold)
                                    }
                                    byServer.forEach { (srv, n) ->
                                        Text("• $srv — тулзов: $n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (state.mcpEnabled && !byServer.containsKey("visa-info")) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(Icons.Outlined.WarningAmber, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                            Text(
                                                "visa-info (${state.mcpServerUrl}) не ответил — нет сети/DNS до VPS. Локальные серверы работают.",
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(14.dp), tint = AppColors.accent)
                                        Text(
                                            if (state.mcpIsRemote) "Удалённый MCP-сервер · развёрнут, работает 24/7"
                                            else "Локальный MCP-сервер (подпроцесс)",
                                            color = AppColors.accent, fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Text(state.mcpServerUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "Инструменты ниже получены С СЕРВЕРА: ${state.mcpTools.size}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
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
                        if (state.extraMcpEnabled) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TextButton(onClick = { state.testExtraMcp() }, enabled = !state.mcpExtraTesting) {
                                    Text("Тест-прогон tools (echo, get-sum)")
                                }
                                if (state.mcpExtraTesting) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                                }
                            }
                            state.mcpExtraTestResult?.let {
                                Text(it, color = AppColors.accent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
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
                                Text("Запустить (по коду)")
                            }
                            TextButton(onClick = { state.runPipelineAgent() }, enabled = !state.mcpPipelineRunning) {
                                Text("Запустить (агент)")
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
                                shape = RoundedCornerShape(Radii.xs),
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
                if (expanded) "Свернуть" else "Развернуть весь ответ",
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
            shape = RoundedCornerShape(Radii.sm)
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
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(Radii.lg), modifier = Modifier.widthIn(max = 560.dp)) {
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
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(Radii.md)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items.forEach { (name, status) ->
                val color = statusColor(status)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).background(color, CircleShape))
                    Text(name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(Radii.xs)) {
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
