@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.adventdesktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UploadFile
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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import java.awt.Desktop
import java.net.URI
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adventdesktop.data.LocalRun
import com.example.adventdesktop.data.Models
import com.example.adventdesktop.domain.Awaiting
import com.example.adventdesktop.domain.MemoryMode
import com.example.adventdesktop.domain.Message
import com.example.adventdesktop.domain.Role
import com.example.adventdesktop.domain.TokenUsage
import com.example.adventdesktop.domain.rag.CitationCheck
import com.example.adventdesktop.domain.rag.GoldAnswer
import com.example.adventdesktop.domain.rag.GoldRetrieval
import com.example.adventdesktop.domain.rag.RagAnswer
import com.example.adventdesktop.domain.rag.RagSource
import com.example.adventdesktop.domain.rag.RerankMode
import com.example.adventdesktop.domain.rag.RetrievalTrace
import com.example.adventdesktop.domain.rag.RewriteOutcome
import com.example.adventdesktop.domain.rag.Scored
import com.example.adventdesktop.domain.rag.ragLooksLikeRefusal

/**
 * Ховер поверхности (§4 «движение сдержанное»): плавная смена тона вместо теней и вспышек.
 * Возвращает цвет для `Surface(color = …)`; источник взаимодействия отдаётся той же `Surface`.
 */
@Composable
internal fun hoverColor(source: MutableInteractionSource, idle: Color, hover: Color): Color {
    val hovered by source.collectIsHoveredAsState()
    val color by animateColorAsState(if (hovered) hover else idle, label = "hover")
    return color
}

/**
 * Колонка чтения (§4): лента и композер живут в центрированной колонке ≤ [Layout.readingWidth]
 * с крупными полями — так длинный ответ агента читается как страница, а не как таблица во всю ширину.
 */
@Composable
private fun ReadingColumn(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = Layout.readingWidth).fillMaxWidth()) { content() }
    }
}

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
                        state, Modifier.width(Layout.sidebar).fillMaxHeight(),
                        onSettings = { showSettings = true },
                        onMemory = { showMemory = true },
                        onProfile = { showProfile = true },
                        onInvariants = { showInvariants = true }
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outline)
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
        if (state.rag.ragOpen) RagDialog(state)
        if (state.localLlm.localLlmOpen) LocalLlmDialog(state)
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
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        // Бренд-марка: единственное место в сайдбаре, где акцент заливкой.
        Row(
            Modifier.padding(start = Space.xs, top = Space.xs, bottom = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Box(
                Modifier.size(30.dp).background(AppColors.accent, RoundedCornerShape(Radii.xs)),
                contentAlignment = Alignment.Center
            ) { Text("В", color = MaterialTheme.colorScheme.onSecondary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall) }
            Column {
                Text("AdventAI", style = MaterialTheme.typography.titleMedium)
                Text("Визовый специалист", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        AccountSwitcher(state, onProfile)

        // «Новая сессия» — заметная, но не кричащая: пилюля с волосяной границей, чернильный текст.
        val newInteraction = remember { MutableInteractionSource() }
        Surface(
            onClick = { state.newConversation() },
            interactionSource = newInteraction,
            color = hoverColor(newInteraction, Color.Transparent, MaterialTheme.colorScheme.surface),
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(Space.sm))
                Text("Новая сессия", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
            }
        }

        Text(
            "ДИАЛОГИ",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Space.sm, top = Space.xs)
        )

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(state.conversationList, key = { it.id }) { meta ->
                ConversationRow(
                    title = meta.title,
                    active = meta.id == state.current?.id,
                    onOpen = { state.open(meta.id) },
                    onDelete = { state.deleteConversation(meta.id) }
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        SidebarButton("Правила", Icons.AutoMirrored.Outlined.Rule, onInvariants)
        SidebarButton("Память", Icons.Outlined.Memory, onMemory)
        SidebarButton("Настройки", Icons.Outlined.Settings, onSettings)
    }
}

/**
 * Айтем списка диалогов: активный — тёплая заливка `secondaryContainer` с левой акцентной полоской
 * (не рамкой вокруг), ✕ проявляется только на ховере, чтобы список оставался спокойным.
 */
@Composable
private fun ConversationRow(title: String, active: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Surface(
        onClick = onOpen,
        interactionSource = interaction,
        color = when {
            active -> MaterialTheme.colorScheme.secondaryContainer
            hovered -> MaterialTheme.colorScheme.surface
            else -> Color.Transparent
        },
        shape = RoundedCornerShape(Radii.sm),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(3.dp).height(20.dp)
                    .background(if (active) AppColors.accent else Color.Transparent, RoundedCornerShape(2.dp))
            )
            Text(
                title,
                Modifier.weight(1f).padding(start = 9.dp, top = 8.dp, bottom = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
            )
            Box(
                Modifier.size(24.dp).clip(CircleShape).clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                if (hovered) {
                    Icon(Icons.Outlined.Close, "удалить", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(Space.xs))
        }
    }
}

@Composable
private fun AccountSwitcher(state: ChatState, onProfile: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        val interaction = remember { MutableInteractionSource() }
        Surface(
            onClick = { open = true },
            interactionSource = interaction,
            color = hoverColor(interaction, Color.Transparent, MaterialTheme.colorScheme.surface),
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).background(AppColors.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text(
                        (state.activeAccount?.name?.trim()?.firstOrNull() ?: 'П').uppercaseChar().toString(),
                        color = MaterialTheme.colorScheme.onSecondary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(Space.sm))
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

/** Низ сайдбара: строка с линейной иконкой и приглушённым текстом; на ховере — заливка бумаги. */
@Composable
private fun SidebarButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        color = hoverColor(interaction, Color.Transparent, MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(Radii.sm),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = Space.md, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChatPane(state: ChatState, modifier: Modifier) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.lg)) {
            Text(
                state.current?.title ?: "Визовый специалист",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

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
                    contentPadding = PaddingValues(horizontal = Space.xxl, vertical = Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.xl)
                ) {
                    items(state.messages) { ReadingColumn { MessageView(it) } }
                    when {
                        taskActive -> {
                            item { ReadingColumn { TaskStatusLine(state) } }
                            item { ReadingColumn { TaskInlineActions(state) } }
                        }
                        state.loading -> item { ReadingColumn { TypingRow(state.config.reducedMotion) } }
                    }
                }
            }
        }

        state.error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(message, Modifier.padding(horizontal = Space.xxl, vertical = Space.sm), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            }
        }

        Composer(state)
    }
}

/** Пустое состояние: крупное serif-приветствие и чипы-подсказки — воздух вместо иллюстраций. */
@Composable
private fun EmptyState(state: ChatState) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Чем помочь с визой?", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.size(Space.md))
        Text(
            "Опишите ситуацию — разберём документы, сроки и риски.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(Space.xxl))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            listOf("Какие документы нужны?", "Сроки оформления", "Риски отказа").forEach { hint ->
                HintChip(hint) { state.input = hint; state.submitComposer() }
            }
        }
    }
}

/** Чип-подсказка пустого состояния: пилюля с волосяной границей, на ховере — заливка бумаги. */
@Composable
private fun HintChip(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        color = hoverColor(interaction, Color.Transparent, MaterialTheme.colorScheme.surface),
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(
            text,
            Modifier.padding(horizontal = Space.lg, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun Composer(state: ChatState) = ReadingColumn {
    Column(Modifier.padding(horizontal = Space.sm).padding(top = Space.sm, bottom = Space.lg)) {
        // Главный элемент экрана: самое щедрое скругление, при фокусе граница загорается терракотой.
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Surface(
            shape = RoundedCornerShape(Radii.xl),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                if (focused) 1.5.dp else 1.dp,
                if (focused) AppColors.accent else MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(Space.sm)) {
                TextField(
                    value = state.input,
                    onValueChange = { state.input = it },
                    interactionSource = interaction,
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                        if (e.key == Key.Enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed) { state.submitComposer(); true } else false
                    },
                    placeholder = {
                        val hint = if (state.task?.awaiting == Awaiting.ANSWER) "Ответьте на уточняющие вопросы…" else "Спросите визового специалиста…"
                        Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
                Row(Modifier.fillMaxWidth().padding(start = Space.xs, top = Space.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    AttachButton(state)
                    // Инженерные витрины (MCP/коннекторы) — только в режиме разработчика (Настройки).
                    if (state.config.developerMode) {
                        IconActionButton(Icons.Outlined.Extension, "Инструменты MCP", { state.connectMcp() }, enabled = !state.mcpConnecting, busy = state.mcpConnecting)
                        IconActionButton(Icons.Outlined.Tune, "Коннекторы агента", { state.openConnectors() })
                        IconActionButton(Icons.Outlined.Storage, "Индексация знаний (RAG)", { state.rag.openRag() })
                        IconActionButton(Icons.Outlined.Memory, "Локальная LLM", { state.localLlm.openLocalLlm() })
                    }
                    DropdownChip(state.model.title, Models.all, { it.title }) { state.chooseModel(it) }
                    // Раскладка DESIGN_BRIEF.md §Раскладка: под полем — выбор модели И выбор режима памяти.
                    MemoryChip(state)
                    // День 27 — визуальный маркер: выбрана локальная модель → чат работает без облака.
                    if (state.model.local) {
                        Text(
                            "⚡ локально",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.accentText
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.sessionTokens > 0) {
                        val cost = if (state.sessionCost > 0) " · $%.4f".format(state.sessionCost) else ""
                        Text(
                            "${state.sessionTokens} ток.$cost",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = AppFonts.mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SendButton(state)
                }
            }
        }
        Row(
            Modifier.padding(top = Space.sm, start = Space.lg),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (state.hasKey) "Enter — отправить · Shift+Enter — перенос" else "Нет ключа — откройте «Настройки» или задайте переменную окружения",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Эффект режима памяти виден сразу при выборе, а не после следующей отправки.
            if (state.memoryDropped > 0) {
                Text(
                    "· вне окна: ${state.memoryDropped} реплик",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.missing
                )
            }
        }
    }
}

/** Первичное действие: круглая кнопка тёмными чернилами (`primary`), иконка — `onPrimary`. */
@Composable
private fun SendButton(state: ChatState) {
    val enabled = !state.loading && state.input.isNotBlank()
    Surface(
        onClick = { state.submitComposer() },
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(38.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            val tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp, color = tint)
            } else {
                Icon(Icons.Filled.ArrowUpward, "отправить", Modifier.size(20.dp), tint = tint)
            }
        }
    }
}

/**
 * Атом UI-kit: круглая иконка-кнопка вторичного действия (линейная иконка · круглый ховер · 34.dp).
 * Сжимает 4 почти одинаковых кнопки композера (MCP/коннекторы/RAG/локальная LLM) в один параметризованный вызов.
 * `SendButton` НЕ входит — он первичный (тёмная заливка `primary`, 38.dp): отдельная семантика, а не булев флаг.
 */
@Composable
private fun IconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = CircleShape,
        color = hoverColor(interaction, Color.Transparent, MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.size(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = AppColors.accent)
            else Icon(icon, contentDescription, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Окно «Локальная LLM» (День 26): запрос к локальной модели (Ollama) + прогон 3 запросов разной сложности. */
@Composable
private fun LocalLlmDialog(state: ChatState) {
    AlertDialog(
        // Единый шаблон окна (§5): бумага, xl-скругление, плоскость (tonalElevation = 0).
        shape = RoundedCornerShape(Radii.xl),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        onDismissRequest ={ state.localLlm.closeLocalLlm() },
        confirmButton = { TextButton(onClick = { state.localLlm.closeLocalLlm() }) { Text("Закрыть") } },
        title = { Text("Локальная LLM (Ollama)") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Локальная LLM отвечает прямо на вашей машине через Ollama (localhost:11434) — бесплатно и приватно, " +
                        "без облака. Нужны запущенная «ollama serve» и «ollama pull <модель>».",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface
                )
                state.localLlm.localLlmNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.accentText)
                }
                if (state.localLlm.localLlmModels.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Модель:", style = MaterialTheme.typography.labelLarge)
                        DropdownChip(state.localLlm.localLlmModel, state.localLlm.localLlmModels, { it }) { state.localLlm.localLlmModel = it }
                    }
                } else {
                    OutlinedTextField(
                        value = state.localLlm.localLlmModel, onValueChange = { state.localLlm.localLlmModel = it },
                        label = { Text("Модель Ollama (список пуст — впиши вручную)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = state.localLlm.localLlmPrompt, onValueChange = { state.localLlm.localLlmPrompt = it },
                    label = { Text("Ваш запрос") }, maxLines = 4, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { state.localLlm.localLlmRunSamples() },
                        enabled = !state.localLlm.localLlmRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)
                    ) { Text("Прогнать 3 запроса") }
                    TextButton(
                        onClick = { state.localLlm.localLlmAsk() },
                        enabled = !state.localLlm.localLlmRunning && state.localLlm.localLlmPrompt.isNotBlank()
                    ) { Text("Спросить своё") }
                    if (state.localLlm.localLlmRunning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                }
                state.localLlm.localLlmResults.forEach { LocalLlmResultCard(it) }

                // === День 29 — оптимизация под задачу (до vs после) ===
                HorizontalDivider()
                Text("День 29 · Оптимизация под задачу (до vs после)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = AppColors.accent)
                Text(
                    "Настройте локальную модель под конкретный кейс и сравните ДО/ПОСЛЕ. «До» — общий промпт и " +
                        "дефолты Ollama; «После» — задачный шаблон + ваши параметры (temperature · max tokens · context " +
                        "window). Метрики: задержка, токены, throughput (ток/с). Чтобы сравнить квантование — установите " +
                        "другой квант (напр. ollama pull qwen2.5:7b-instruct-q8_0), выберите его в «Модель» выше и прогоните снова.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(value = state.localLlm.optTask, onValueChange = { state.localLlm.optTask = it }, label = { Text("Задача (запрос)") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = state.localLlm.optSystem, onValueChange = { state.localLlm.optSystem = it }, label = { Text("Промпт-шаблон «После» (под кейс)") }, maxLines = 10, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Temp: %.2f".format(state.localLlm.optTemperature), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = state.localLlm.optTemperature, onValueChange = { state.localLlm.optTemperature = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = state.localLlm.optMaxTokens, onValueChange = { state.localLlm.optMaxTokens = it.filter(Char::isDigit) }, label = { Text("Max tokens") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = state.localLlm.optNumCtx, onValueChange = { state.localLlm.optNumCtx = it.filter(Char::isDigit) }, label = { Text("Context window") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { state.localLlm.optimizeCompare() }, enabled = !state.localLlm.optRunning && state.localLlm.optTask.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)) { Text("Сравнить: до vs после") }
                    if (state.localLlm.optRunning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                }
                state.localLlm.optBefore?.let { OptRunCard("До — общий промпт + дефолты", it, tuned = false) }
                state.localLlm.optAfter?.let { OptRunCard("После — шаблон под кейс + тюнинг", it, tuned = true) }
                val optB = state.localLlm.optBefore
                val optA = state.localLlm.optAfter
                if (optB != null && optA != null) {
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(Radii.md), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Разница (до → после)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = AppColors.accent)
                            Text("• Скорость: ${optB.ms} → ${optA.ms} мс · throughput ${"%.0f".format(optB.tokPerSec)} → ${"%.0f".format(optA.tokPerSec)} ток/с.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text("• Ресурсы: выход ${optB.evalTokens} → ${optA.evalTokens} ток. (лимит max tokens + короткий шаблон обычно снижают расход и ускоряют).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text("• Качество — оцените глазами: задачный шаблон даёт строгий формат (нумерованный список без воды).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }

                // === День 30 — обращение к приватному LLM-сервису по HTTP ===
                HorizontalDivider()
                Text("День 30 · Обращение к сервису по HTTP", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = AppColors.accent)
                Text(
                    "Приложение как HTTP-клиент к приватному LLM-сервису. Подними сервис (gradlew runLocalLlmService), " +
                        "укажи URL и токен. URL — любой: localhost, IP в сети (подними с LLM_HOST=0.0.0.0) или домен. " +
                        "«Прогнать 3 вопроса» — батч по сети; «Нагрузка ×6» — лимиты (429 rate-limit / 503 занят).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(value = state.service.serviceUrl, onValueChange = { state.service.serviceUrl = it }, label = { Text("URL сервиса") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = state.service.serviceToken, onValueChange = { state.service.serviceToken = it }, label = { Text("Токен (LLM_AUTH_TOKEN, если задан)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { state.service.serviceHealth() }, enabled = !state.service.serviceRunning, colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)) { Text("GET /health") }
                    Button(onClick = { state.service.serviceChat() }, enabled = !state.service.serviceRunning, colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)) { Text("POST /chat") }
                    if (state.service.serviceRunning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { state.service.serviceRunSamples() }, enabled = !state.service.serviceRunning, colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)) { Text("Прогнать 3 вопроса") }
                    TextButton(onClick = { state.service.serviceBurst() }, enabled = !state.service.serviceRunning) { Text("Нагрузка ×6") }
                }
                if (state.service.serviceLog.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(Radii.md), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Ответы сервиса (свежие сверху):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { state.service.clearServiceLog() }) { Text("очистить", style = MaterialTheme.typography.labelSmall) }
                            }
                            state.service.serviceLog.asReversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                }
            }
        }
    )
}

/** Карточка одного ответа локальной LLM: сложность, промпт, ответ и метрики (задержка, токены). */
@Composable
private fun LocalLlmResultCard(r: LocalLlmPanelState.LocalLlmResult) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radii.md),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Сложность: ${r.level}",
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = AppColors.accent
            )
            Text("❓ ${r.prompt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                (if (r.error) "⚠️ " else "💬 ") + r.answer,
                style = MaterialTheme.typography.bodyMedium,
                color = if (r.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            if (!r.error) {
                Text(
                    "⏱ ${r.ms} мс · токены: вход ${r.promptTokens}, выход ${r.completionTokens}, всего ${r.totalTokens}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** День 29 — карточка одного прогона (до/после): метрики (задержка, ток/с, токены) + текст ответа. */
@Composable
private fun OptRunCard(label: String, r: LocalRun, tuned: Boolean) {
    val accent = if (tuned) AppColors.accent else MaterialTheme.colorScheme.onSurfaceVariant
    ResultCard {
        Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = accent)
        Text(
            "⏱ ${r.ms} мс · ${"%.0f".format(r.tokPerSec)} ток/с · вход ${r.promptTokens} / выход ${r.evalTokens} ток.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ExpandableText(r.text, collapsedLines = 6)
    }
}

/** Окно «Индексация знаний (RAG)» (День 21): построить индекс, сравнить 2 стратегии chunking, найти. */
@Composable
private fun RagDialog(state: ChatState) {
    AlertDialog(
        // Единый шаблон окна (§5): бумага, xl-скругление, плоскость (tonalElevation = 0).
        shape = RoundedCornerShape(Radii.xl),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        onDismissRequest ={ state.rag.closeRag() },
        confirmButton = { TextButton(onClick = { state.rag.closeRag() }) { Text("Закрыть") } },
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
                state.rag.ragNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.accentText)
                }

                // === Шаг 1 — индекс ===
                Text("Шаг 1 · Построить индекс базы", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = AppColors.accent)
                Text(
                    "База знаний → чанки → эмбеддинги → SQLite-индекс с метаданными. Документов в корпусе: ${state.rag.ragDocCount}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ConnectorToggleRow(
                    "Эмбеддер: Ollama (nomic-embed-text)",
                    if (state.ragUseOllama) "локально, 768-мерные вектора; нужна запущенная Ollama" else "выключено → офлайн-фолбэк (hashing, без сети)",
                    state.ragUseOllama
                ) { state.chooseRagEmbedder(it) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { state.rag.buildIndex() },
                        enabled = !state.rag.ragBuilding,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)
                    ) { Text("Построить индекс") }
                    if (state.rag.ragBuilding) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        Text(state.rag.ragProgress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.rag.ragComparison?.let { RagComparisonTable(it) }

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
                    value = state.rag.ragQuery, onValueChange = { state.rag.ragQuery = it },
                    label = { Text("Вопрос") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { state.rag.ragCompare() },
                        enabled = state.rag.ragComparison != null && !state.rag.ragAnswering,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)
                    ) { Text("Спросить в обоих режимах") }
                    if (state.rag.ragAnswering) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { state.rag.askNegativeExample() }, enabled = state.rag.ragComparison != null && !state.rag.ragAnswering) {
                        Text("Пример: вопрос не из базы", style = MaterialTheme.typography.labelSmall)
                    }
                }
                val rag = state.rag.ragAnswerRag
                val plain = state.rag.ragAnswerPlain
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
                state.rag.ragTrace?.let { RagTraceView(it) }
                if (rag != null && plain != null) {
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(Radii.md), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
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
                        onClick = { state.rag.runGoldRetrieval() },
                        enabled = state.rag.ragComparison != null && !state.rag.goldRetrievalRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)
                    ) { Text("Сравнить поиск (без/с фильтром)") }
                    if (state.rag.goldRetrievalRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        Text(state.rag.goldRetrievalProgress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.rag.goldRetrieval.isNotEmpty()) GoldRetrievalView(state.rag.goldRetrieval)

                Text(
                    "Б. Качество ОТВЕТОВ в обоих режимах (нужен LLM): с RAG — ответ со ссылкой + отказ на ловушке; без RAG — без ссылок, на ловушке выдумка.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { state.rag.runGoldAnswers() },
                        enabled = state.rag.ragComparison != null && !state.rag.goldRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)
                    ) { Text("Прогнать ответы (10 вопросов)") }
                    if (state.rag.goldRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        Text(state.rag.goldProgress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.rag.goldAnswers.isNotEmpty()) GoldAnswersView(state.rag.goldAnswers)

                // === День 24 — обязательные цитаты/источники + режим «не знаю» ===
                HorizontalDivider()
                Text("День 24 · Цитаты, источники и «не знаю»", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = AppColors.accent)
                Text(
                    "Каждый ответ С RAG обязан нести источники (файл › раздел · chunk_id) и дословные цитаты из базы. " +
                        "Если релевантность ниже порога — ассистент отвечает «не знаю» и просит уточнить (не выдумывает). " +
                        "Прогон проверяет по 10 вопросам: есть ли источники, есть ли цитаты и совпадает ли смысл ответа " +
                        "с цитатами (отдельная модель-судья, метрика Faithfulness).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { state.rag.runCitationEval() },
                        enabled = state.rag.ragComparison != null && !state.rag.citationEvalRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)
                    ) { Text("Проверить цитаты и источники (10 вопросов)") }
                    if (state.rag.citationEvalRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        Text(state.rag.citationEvalProgress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.rag.citationChecks.isNotEmpty()) CitationEvalView(state.rag.citationChecks)

                // === День 28 — RAG локально vs облако ===
                HorizontalDivider()
                Text("День 28 · RAG локально vs облако", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = AppColors.accent)
                Text(
                    "Один и тот же ЛОКАЛЬНЫЙ retrieval (эмбеддер nomic-embed-text) → ответ генерируют две модели: " +
                        "локальная (Ollama) и облачная. Локальная колонка — RAG полностью без облака. Сравните качество, " +
                        "скорость (задержку) и стабильность.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Локальная модель:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val localModels = state.localLlm.localLlmModels.ifEmpty { listOf(state.rag.ragLocalModel) }
                    DropdownChip(state.rag.ragLocalModel, localModels, { it }) { state.rag.ragLocalModel = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { state.rag.ragCompareLocalVsCloud() },
                        enabled = state.rag.ragComparison != null && !state.rag.ragVsRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent)
                    ) { Text("Ответить: локаль vs облако") }
                    if (state.rag.ragVsRunning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                }
                RagVsView(state)
            }
        }
    )
}

/** День 28 — сравнение RAG-ответа локальной и облачной модели поверх ОДНОГО локального retrieval. */
@Composable
private fun RagVsView(state: ChatState) {
    val local = state.rag.ragVsLocal
    val cloud = state.rag.ragVsCloud
    if (local == null && cloud == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (local?.answer != null && cloud?.answer != null) {
            val faster = if (local.ms <= cloud.ms) "локальная" else "облачная"
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(Radii.md), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Оценка", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = AppColors.accent)
                    Text("• Скорость: локальная ${local.ms} мс · облачная ${cloud.ms} мс → быстрее $faster.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("• Контекст идентичен (общий локальный retrieval) — различается только генератор ответа.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("• Качество/стабильность оцените глазами: оба ответа опираются на одни источники ниже; локаль пиньована на русский.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        local?.let { RagVsCard(it, localCol = true) }
        cloud?.let { RagVsCard(it, localCol = false) }
        val sources = local?.answer?.sources?.takeIf { it.isNotEmpty() } ?: cloud?.answer?.sources.orEmpty()
        if (sources.isNotEmpty()) RagEvidence(sources, refused = false)
    }
}

/** Одна колонка сравнения (локальная/облачная): модель · задержка · токены + текст ответа. */
@Composable
private fun RagVsCard(r: RagPanelState.RagVsResult, localCol: Boolean) {
    val accent = if (localCol) AppColors.accent else MaterialTheme.colorScheme.onSurfaceVariant
    ResultCard {
        val head = (if (localCol) "⚡ " else "☁ ") + r.label +
            (r.answer?.let { " · ${r.ms} мс · ${it.usage?.total ?: 0} ток." } ?: "")
        Text(head, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = accent)
        when {
            !r.available -> Text(r.error ?: "недоступно", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            r.error != null -> Text("Ошибка: ${r.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            r.answer != null -> {
                if (r.answer.abstained) Text("🚫 режим «не знаю» — контекст слабее порога", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                ExpandableText(r.answer.text, collapsedLines = 6)
            }
        }
    }
}

private fun rerankLabel(m: RerankMode): String = when (m) {
    RerankMode.OFF -> "Выкл"; RerankMode.HEURISTIC -> "Эвристика"; RerankMode.LLM -> "LLM"
}

private fun hitMark(h: Boolean?): String = when (h) { true -> "✓"; false -> "✗"; else -> "—" }

/** Настройки улучшенного поиска (День 23): стратегия, реранк, query rewrite, порог отсечения. */
@Composable
private fun RagPipelineControls(state: ChatState) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(Radii.md), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Улучшенный поиск (День 23)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = AppColors.accent)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Стратегия:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownChip(state.rag.ragStrategy, listOf("contextual", "structural", "fixed"), { it }) { state.rag.ragStrategy = it }
                Text("Реранк:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownChip(rerankLabel(state.rag.ragRerank), RerankMode.entries.toList(), { rerankLabel(it) }) { state.rag.ragRerank = it }
            }
            ConnectorToggleRow("Query rewrite (LLM)", "переписать вопрос в поисковый запрос перед эмбеддингом", state.rag.ragRewrite) { state.rag.ragRewrite = it }
            if (state.rag.ragRewrite) RewriteStatusLine(state.rag.ragTrace)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Порог: %.2f".format(state.rag.ragFloor), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = state.rag.ragFloor, onValueChange = { state.rag.ragFloor = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
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
            "✏ переписал: «${t.originalQuery}» → «${t.usedQuery}»" to AppColors.accentText
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
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(Radii.md), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Второй этап поиска (реранк + фильтр)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, color = AppColors.accentText)
            when (t.rewrite) {
                RewriteOutcome.REWRITTEN -> Text("Query rewrite: «${t.originalQuery}» → «${t.usedQuery}»", style = MaterialTheme.typography.labelSmall, color = AppColors.accentText)
                RewriteOutcome.UNCHANGED -> Text("Query rewrite включён, но запрос не изменился (вопрос уже краткий).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RewriteOutcome.FAILED -> Text("Query rewrite не сработал (сеть/лимит) — искали по исходному вопросу.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                RewriteOutcome.OFF -> {}
            }
            Text("Пул: ${t.poolSize} → после порога: ${t.survived} (отсёк ${t.droppedByFilter}) → в ответ: ${t.after.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("top-K ДО (сырой cosine):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            hits(t.before, MaterialTheme.colorScheme.onSurface)
            Text("top-K ПОСЛЕ (реранк+фильтр):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (t.after.isEmpty()) Text("  — пусто: нерелевантно → честный отказ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            else hits(t.after, AppColors.accentText)
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
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(Radii.md), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
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
                color = if (flipped) AppColors.accentText else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Карточка ответа одного режима: ярлык, техстрока, текст, обязательные источники (+chunk_id) и цитаты (День 24). */
@Composable
private fun RagAnswerCard(a: RagAnswer, warn: Boolean, whatIs: String, note: String? = null) {
    val accent = if (warn) MaterialTheme.colorScheme.error else AppColors.accent
    ResultCard {
        Text("${a.mode} — $whatIs", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = accent)
        if (a.abstained) Text("🚫 режим «не знаю» — контекст слабее порога, ответ не выдумывается", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        note?.let { Text(it, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        val toks = a.usage?.let { "${it.total} ток." } ?: "—"
        val ctx = if (a.contextChars > 0) "контекст ${a.contextChars} симв." else "без контекста базы"
        Text("$toks · $ctx", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ExpandableText(a.text, collapsedLines = 6)
        if (a.sources.isNotEmpty()) {
            Text("Источники (файл › раздел · chunk_id):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            a.sources.forEach { s ->
                Text("[${s.n}] %.3f · %s › %s · %s".format(s.score, s.source, s.section, s.chunkId), style = MaterialTheme.typography.labelSmall, color = AppColors.accentText)
            }
        }
        if (a.citations.isNotEmpty()) {
            Text("Цитаты (дословно из источников):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            a.citations.forEach { c ->
                Text("[${c.n}] «${c.quote}»", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
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
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(Radii.md), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("[${s.n}] %s › %s · %s · %.3f".format(s.source, s.section, s.chunkId, s.score), style = MaterialTheme.typography.labelSmall, color = AppColors.accentText)
                    ExpandableText(s.text, collapsedLines = 3)
                }
            }
        }
    }
}

/** День 24: сводка «источники / цитаты / смысл совпал» по набору + разбор по каждому вопросу (и ловушка → «не знаю»). */
@Composable
private fun CitationEvalView(items: List<CitationCheck>) {
    val pos = items.filter { !it.q.isNegative }
    val withSources = pos.count { it.sourcesPresent }
    val withQuotes = pos.count { it.quotesPresent }
    val faithful = pos.count { it.faithful == true }
    val neg = items.firstOrNull { it.q.isNegative }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(Radii.md), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Итог (по ${pos.size} содержательным вопросам)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = AppColors.accent)
            Text("• источники есть: $withSources из ${pos.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            Text("• цитаты есть: $withQuotes из ${pos.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            Text("• смысл ответа опирается на источники (Faithfulness): $faithful из ${pos.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            neg?.let {
                Text(
                    "• вопрос-ловушка (в базе нет ответа): " + if (it.abstained) "ответил «не знаю» ✓" else "НЕ воздержался ✗",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (it.abstained) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                )
            }
            HorizontalDivider()
            items.forEach { c -> CitationEvalRow(c) }
        }
    }
}

/** Одна строка проверки Дня 24: вопрос + вердикт + сам ответ, источники (chunk_id) и цитаты (для наглядности). */
@Composable
private fun CitationEvalRow(c: CitationCheck) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(Radii.md), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("#${c.q.id}. ${c.q.question}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            val line = if (c.expectedAbstain) {
                "ловушка → " + if (c.abstained) "«не знаю» ✓" else "ответил ✗ (должен был воздержаться)"
            } else {
                "источники ${hitMark(c.sourcesPresent)} · цитаты ${hitMark(c.quotesPresent)} · смысл ${hitMark(c.faithful)}" +
                    if (c.abstained) " · «не знаю» (контекст слабый)" else ""
            }
            Text(line, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = if (c.pass) AppColors.accentText else MaterialTheme.colorScheme.error)

            Text("Ответ:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExpandableText(c.answer.text, collapsedLines = 3)

            if (c.answer.sources.isNotEmpty()) {
                Text("Источники (файл › раздел · chunk_id):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                c.answer.sources.forEach { s ->
                    Text("[${s.n}] %.3f · %s › %s · %s".format(s.score, s.source, s.section, s.chunkId), style = MaterialTheme.typography.labelSmall, color = AppColors.accentText)
                }
            }
            if (c.answer.citations.isNotEmpty()) {
                Text("Цитаты (дословно):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                c.answer.citations.forEach { q ->
                    Text("[${q.n}] «${q.quote}»", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
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
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(Radii.md), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
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
        Text("С RAG: $ragMark   ·   Без RAG: $plainMark", style = MaterialTheme.typography.labelSmall, color = AppColors.accentText)
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
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(Radii.md), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
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

/**
 * Строка установленного скилла БЕЗ переключателя — информационная: название + описание + бейдж «установлен».
 * Для dev-скиллов Claude Code (напр. рефакторинг): это не рантайм-источник инструментов агента, а факт установки,
 * поэтому переключателя нет (пустой тумблер вводил бы в заблуждение).
 */
@Composable
private fun InstalledSkillRow(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
            Text(
                "установлен",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * Атом UI-kit: «бумажная карточка» (§5) — `surface` + волосяная граница + скругление md, во всю ширину.
 * Единый каркас для всех блоков результатов (Opt/Connector/RagVs/RagAnswer/сводки/цитаты/логи): цветом
 * теперь отличается только заголовок-метка внутри, а не подложка — плоскость вместо тонированных плашек.
 */
@Composable
private fun ResultCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radii.md),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs), content = content)
    }
}

/** Результат одного прогона коннектора (MCP/Skill): токены, след вызовов и ответ. */
@Composable
private fun ConnectorResultView(label: String, run: ConnectorRun?) {
    if (run == null) return
    ResultCard {
        val toks = run.usage?.let { "prompt ${it.prompt} · total ${it.total}" } ?: "—"
        Text("$label · токены: $toks", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = AppColors.accent)
        run.steps.forEach { Text(it.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ExpandableText(run.reply, collapsedLines = 5)
    }
}

/** Окно «Коннекторы агента» (День 20): переключатели MCP/Skill + сравнение на одном вопросе (токены). */
@Composable
private fun ConnectorsDialog(state: ChatState) {
    AlertDialog(
        // Единый шаблон окна (§5): бумага, xl-скругление, плоскость (tonalElevation = 0).
        shape = RoundedCornerShape(Radii.xl),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        onDismissRequest ={ state.closeConnectors() },
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
                ConnectorToggleRow("RAG в агенте", "внутренняя база знаний (50 док.): выдержки + источники в ответах агента", state.ragInAgentEnabled) { state.setRagInAgentEnabled(it) }
                HorizontalDivider()
                ConnectorToggleRow("Skill — документы", "локально: visa-cli docs (проверка приложенных файлов)", state.skillDocsEnabled) { state.setSkillDocsEnabled(it) }
                ConnectorToggleRow("Skill — автоулучшение промтов", "локально: анализ диалогов и точечные предложения", state.skillPromptTuneEnabled) { state.setSkillPromptTuneEnabled(it) }
                InstalledSkillRow(
                    "Skill — рефакторинг архитектуры",
                    "Claude Code · SOLID/DRY/KISS: детект платформы, Mikado-цепочки, рой агентов (.claude/skills/refactor-architecture)"
                )
                Text(
                    "Установлено скиллов: 3 — документы · автоулучшение промтов · рефакторинг архитектуры",
                    style = MaterialTheme.typography.labelMedium, color = AppColors.accent, fontWeight = FontWeight.SemiBold
                )
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
                        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(Radii.md), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
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
        // Единый шаблон окна (§5): бумага, xl-скругление, плоскость (tonalElevation = 0).
        shape = RoundedCornerShape(Radii.xl),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        onDismissRequest ={ state.closeMcpDialog() },
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
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(Radii.md),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(Radii.md),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                color = AppColors.accentText,
            )
        }
    }
}

/**
 * Чип режима памяти (DESIGN_BRIEF.md §Раскладка/§Память). Отдельный композабл, а не [DropdownChip]:
 * у пунктов две строки — название и пояснение, что режим сделает с диалогом. Без пояснения выбор из
 * пяти режимов управления контекстом был бы вслепую.
 */
@Composable
private fun MemoryChip(state: ChatState) {
    var open by remember { mutableStateOf(false) }
    val mode = state.config.memoryMode
    Box {
        Surface(
            onClick = { open = true },
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape
        ) {
            Row(
                Modifier.padding(start = Space.md, end = Space.sm, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "Память: ${mode.chip}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(open, { open = false }) {
            MemoryMode.entries.forEach { item ->
                val active = item == mode
                DropdownMenuItem(
                    text = {
                        Column(Modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                item.title + if (active) "  ✓" else "",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (active) AppColors.accentText else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                item.hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = { state.chooseMemoryMode(item); open = false }
                )
            }
        }
    }
}

@Composable
private fun <T> DropdownChip(label: String, items: List<T>, itemLabel: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        val interaction = remember { MutableInteractionSource() }
        Surface(
            onClick = { open = true },
            interactionSource = interaction,
            color = hoverColor(interaction, Color.Transparent, MaterialTheme.colorScheme.surfaceVariant),
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(Modifier.padding(start = Space.md, end = Space.sm, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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

/**
 * Пользователь — мягкая пилюля справа на бумаге; агент — просто текст в колонке чтения, без пузыря
 * (так длинный ответ читается как страница, а не как реплика мессенджера).
 */
@Composable
private fun MessageView(message: Message) {
    if (message.role == Role.User) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(Radii.lg), modifier = Modifier.widthIn(max = 560.dp)) {
                Text(
                    message.text,
                    Modifier.padding(horizontal = Space.lg, vertical = Space.md),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text("Визовый специалист", style = MaterialTheme.typography.labelMedium, color = AppColors.accent, fontWeight = FontWeight.SemiBold)
            parseSegments(message.text).forEach { seg ->
                when (seg) {
                    is Segment.Plain -> MarkdownText(seg.text, AppColors.accentText)
                    is Segment.Checklist -> ChecklistView(seg.items)
                }
            }
            message.usage?.let { TokenLine(it) }
        }
    }
}

/** Строка расхода под ответом: моноширинный чип, приглушённый — цифры не спорят с текстом. */
@Composable
private fun TokenLine(usage: TokenUsage) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            "↑${usage.prompt}  ↓${usage.completion}  ·  Σ${usage.total}",
            Modifier.padding(horizontal = Space.md, vertical = Space.xs),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = AppFonts.mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Чек-лист документов: бумажная карточка, в строке — линейная иконка статуса, имя и точка-бейдж. */
@Composable
private fun ChecklistView(items: List<Pair<String, String>>) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            items.forEach { (name, status) ->
                val color = statusColor(status)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    Icon(statusIcon(status), null, Modifier.size(17.dp), tint = color)
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Box(Modifier.size(7.dp).background(color, CircleShape))
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Индикатор набора: три терракотовые точки «дышат» по очереди (§4 — движение сдержанное).
 * При «Меньше анимаций» (Настройки) точки статичны — настройка обязана выключать и этот цикл.
 */
@Composable
private fun TypingRow(reducedMotion: Boolean) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        repeat(3) { i ->
            val breath by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(620, delayMillis = i * 180), RepeatMode.Reverse),
                label = "dot$i"
            )
            Box(Modifier.size(7.dp).alpha(if (reducedMotion) 0.6f else breath).background(AppColors.accent, CircleShape))
        }
    }
}

/** Линейная иконка статуса документа — парная к [statusColor]. */
private fun statusIcon(status: String): ImageVector {
    val s = status.trim().lowercase()
    return when {
        s.startsWith("провер") -> Icons.Outlined.CheckCircle
        s.startsWith("загруж") -> Icons.Outlined.UploadFile
        s.startsWith("не хват") || s.startsWith("нет") -> Icons.Outlined.WarningAmber
        else -> Icons.Outlined.RadioButtonUnchecked
    }
}

@Composable
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
private val MD_HEADING = Regex("^#{1,6}\\s+(.*)$")
private val MD_BULLET = Regex("^(\\s*)[-*]\\s+(.*)$")

/** Открыть URL в системном браузере (по клику на ссылку в ответе). */
private fun openUrl(url: String) {
    runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url)) }
}

/**
 * Лёгкий рендер Markdown из ответа LLM (без внешней зависимости — важно из-за оффлайн-Gradle). Модель шлёт
 * `**жирный**`, `##` заголовки, `- списки`, `` `код` `` и голые ссылки; без разбора они видны сырыми символами.
 * Разбираем построчно: заголовок/пункт списка/абзац; внутри строки — [inlineMd] (жирный/код/ссылки).
 */
@Composable
private fun MarkdownText(text: String, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        text.split('\n').forEach { raw ->
            val line = raw.trimEnd()
            val heading = MD_HEADING.matchEntire(line.trimStart())
            val bullet = MD_BULLET.matchEntire(line)
            when {
                line.isBlank() -> Spacer(Modifier.height(3.dp))
                heading != null -> Text(
                    inlineMd(heading.groupValues[1], accent), color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall
                )
                bullet != null -> {
                    val indent = ((bullet.groupValues[1].length / 2) * 14).dp
                    Row(Modifier.padding(start = indent), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("•", color = accent)
                        Text(inlineMd(bullet.groupValues[2], accent), color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                else -> Text(inlineMd(line, accent), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Инлайн-разметка одной строки: `**жирный**`, `` `код` `` и кликабельные ссылки. Незакрытые маркеры — как есть. */
private fun inlineMd(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end < 0) { append("**"); i += 2 }
                else { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }; i = end + 2 }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end < 0) { append('`'); i += 1 }
                else { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = accent)) { append(text.substring(i + 1, end)) }; i = end + 1 }
            }
            text.startsWith("http://", i) || text.startsWith("https://", i) -> {
                val raw = URL_REGEX.find(text, i)?.value ?: text.substring(i)
                val url = raw.trimEnd('.', ',', ';', ')', '»', '"', '!', '?')   // не цеплять хвостовую пунктуацию
                val styles = TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
                withLink(LinkAnnotation.Url(url, styles) { link -> (link as? LinkAnnotation.Url)?.url?.let(::openUrl) }) { append(url) }
                if (raw.length > url.length) append(raw.substring(url.length))
                i += raw.length
            }
            else -> { append(text[i]); i += 1 }
        }
    }
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
