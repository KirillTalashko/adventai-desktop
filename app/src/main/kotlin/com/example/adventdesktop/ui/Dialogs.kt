package com.example.adventdesktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.example.adventdesktop.data.ModelOption
import com.example.adventdesktop.data.Models
import com.example.adventdesktop.domain.Invariant
import com.example.adventdesktop.domain.Role
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.HorizontalDivider

// ============================== Настройки ==============================


/**
 * Единый каркас модального окна (§5.8): скрим (клик — закрыть), карточка `surface` R22,
 * серифный заголовок + круглая кнопка-крестик, скроллируемое тело.
 *
 * Это ВНУТРИОКОННЫЙ модал, а не отдельное окно ОС: по прототипу диалог затемняет приложение и
 * закрывается кликом по скриму — отдельное окно так себя не ведёт.
 */
@Composable
fun ModalScaffold(title: String, onClose: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim)
            // Клик по скриму закрывает; indication = null, чтобы фон не подсвечивался как кнопка.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        val maxCardHeight = maxHeight * Layout.dialogMaxHeightFraction
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(Radii.dialog),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = Elevations.dialog,
            modifier = Modifier.widthIn(max = Sizes.dialogMaxWidth).heightIn(max = maxCardHeight)
                // Клик по самой карточке не должен «протекать» в скрим и закрывать окно.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
        ) {
            Column(
                Modifier.padding(vertical = Layout.dialogPaddingV, horizontal = Layout.dialogPaddingH)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = AppColors.ink)
                    Surface(
                        onClick = onClose,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Close, "закрыть", Modifier.size(16.dp), tint = AppColors.muted)
                        }
                    }
                }
                Spacer(Modifier.height(Space.md))
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                    content = body
                )
            }
        }
    }
}

/**
 * Блок внутри модального окна (§5.8): подложка `bg` на белой карточке, слабая граница, R16.
 * Заголовок и подпись разделены «·», как в прототипе.
 */
@Composable
private fun ModalBlock(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = AppColors.ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.muted)
            Spacer(Modifier.height(Space.xs))
            content()
        }
    }
}

@Composable
fun SettingsDialog(state: ChatState, onClose: () -> Unit) {
    var orKey by remember { mutableStateOf(state.config.openrouterKey) }
    var dsKey by remember { mutableStateOf(state.config.deepseekKey) }
    var model by remember { mutableStateOf(state.model) }
    var devMode by remember { mutableStateOf(state.config.developerMode) }
    var darkTheme by remember { mutableStateOf(state.config.darkTheme) }
    var reducedMotion by remember { mutableStateOf(state.config.reducedMotion) }
    var proxy by remember { mutableStateOf(state.config.httpProxy) }

    ModalScaffold("Настройки", onClose) {
        Text(
            "Ключи хранятся локально в ~/.adventai/config.json (или берутся из переменных окружения).",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal), color = AppColors.muted
        )
        FieldLabel("OpenRouter API-ключ")
        ModalField(orKey, { orKey = it }, password = true)
        FieldLabel("DeepSeek API-ключ")
        ModalField(dsKey, { dsKey = it }, password = true)
        FieldLabel("Модель по умолчанию")
        ModelSelector(model) { model = it }
        FieldLabel("HTTP-прокси (необязательно)")
        ModalField(proxy, { proxy = it }, placeholder = "http://127.0.0.1:10809")
        Text(
            "Для сетей с локальным туннелем, где прямой выход/DNS закрыты. Пусто — прямое соединение.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.muted
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingToggle("Тёмная тема", "Тёмное оформление приложения.", darkTheme) { darkTheme = it }
        SettingToggle("Меньше анимаций", "Мгновенная прокрутка без плавных переходов.", reducedMotion) { reducedMotion = it }
        SettingToggle("Режим разработчика", "Показывать инженерные витрины: инструменты MCP, коннекторы, RAG.", devMode) { devMode = it }
        Row(
            Modifier.fillMaxWidth().padding(top = Space.sm),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("Отмена", style = MaterialTheme.typography.labelLarge, color = AppColors.muted) }
            Spacer(Modifier.width(Space.sm))
            Surface(
                onClick = {
                    state.saveConfig(
                        state.config.copy(
                            openrouterKey = orKey.trim(), deepseekKey = dsKey.trim(), modelId = model.id,
                            developerMode = devMode, darkTheme = darkTheme, reducedMotion = reducedMotion,
                            httpProxy = proxy.trim(),
                        )
                    )
                    onClose()
                },
                shape = RoundedCornerShape(Radii.sm),
                color = AppColors.accent
            ) {
                Text(
                    "Сохранить",
                    Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
    }
}

/** Подпись над полем в модальном окне (§5.8). */
@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = AppColors.ink)
}

/** Поле ввода модального окна (§5.8): подложка `bg`, граница `outline`, R12, паддинг 11/13. */
@Composable
private fun ModalField(
    value: String,
    onValueChange: (String) -> Unit,
    password: Boolean = false,
    placeholder: String = "",
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(Radii.sm),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = AppColors.muted.copy(alpha = 0.85f))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = AppColors.ink),
                cursorBrush = SolidColor(AppColors.accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Строка-настройка: название, пояснение и переключатель по §5.8 (трек 42×22, кнопка 18). */
@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = AppColors.ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.muted)
        }
        AppSwitch(checked, onChange)
    }
}

/**
 * Переключатель прототипа (§5.8): трек 42 × 22 меняет цвет `outline` → `accent`, белая кнопка 18
 * едет на 20. Свой, а не Material: у M3 Switch другая геометрия и её не подогнать параметрами.
 */
@Composable
private fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) AppColors.accent else MaterialTheme.colorScheme.outline, tween(300), label = "track")
    val offset by animateDpAsState(if (checked) 20.dp else 2.dp, tween(300), label = "thumb")
    Box(
        Modifier.size(width = Sizes.switchTrackWidth, height = Sizes.switchTrackHeight)
            .background(track, RoundedCornerShape(Radii.pill))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier.padding(start = offset).size(Sizes.switchThumb)
                .background(Color.White, CircleShape)
        )
    }
}

@Composable
private fun ModelSelector(selected: ModelOption, onSelect: (ModelOption) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        // Кликабельность — через Surface(onClick), а не Modifier.clickable: ripple обрезается по скруглению.
        Surface(
            onClick = { open = true },
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(Radii.sm),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected.title + if (selected.free) "  · free" else "", Modifier.weight(1f))
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(open, { open = false }) {
            Models.all.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title + if (option.free) "  · free" else "") },
                    onClick = { onSelect(option); open = false }
                )
            }
        }
    }
}

// ============================== Профиль (отдельное окно) ==============================

@Composable
fun ProfileDialog(state: ChatState, onClose: () -> Unit) {
    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = DpSize(560.dp, 700.dp)),
        title = "Профиль"
    ) {
        AdventTheme(dark = state.config.darkTheme) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Профиль предпочтений", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Как ассистент должен с вами общаться — подмешивается в каждый запрос.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ProfileForm(
                        initial = state.profile,
                        submitLabel = "Сохранить",
                        onSubmit = { state.saveProfile(it); onClose() },
                        onCancel = onClose
                    )
                }
            }
        }
    }
}

// ============================== Память (отдельное окно) ==============================

@Composable
fun MemoryDialog(state: ChatState, onClose: () -> Unit) {
    var refresh by remember { mutableStateOf(0) }
    val longTerm = remember(refresh) { state.longTerm() }
    val working = remember(refresh) { state.working() }
    val profileItems = remember(longTerm) {
        longTerm.profile.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.startsWith("-")) t.removePrefix("-").trim().takeIf { it.isNotEmpty() } else null
        }
    }

    ModalScaffold("Память", onClose) {
        Text(
            "Что агент помнит о вас и текущей задаче. Память пополняется автоматически по ходу диалога; здесь можно дополнить вручную.",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal), color = AppColors.muted
        )

        ModalBlock("Долговременная память", "профиль и решения · сохраняется между сессиями") {
            SubLabel("Профиль")
            if (profileItems.isEmpty()) EmptyHint() else profileItems.forEach { Bullet(it) }
            AddRow("Добавить факт о пользователе…", "Добавить") { state.addProfileFact(it); refresh++ }

            Spacer(Modifier.height(Space.xs))
            SubLabel("Решения")
            if (longTerm.decisions.isEmpty()) EmptyHint() else longTerm.decisions.forEach { Bullet(it) }
            AddRow("Добавить решение / договорённость…", "Добавить") { state.addDecision(it); refresh++ }
        }

        ModalBlock("Рабочая память", "цель и ограничения · текущий диалог") {
            SubLabel("Цель")
            Text(
                working.goal.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (working.goal.isBlank()) AppColors.muted else AppColors.ink
            )
            AddRow("Задать / изменить цель задачи…", "Задать") { state.setGoal(it); refresh++ }

            Spacer(Modifier.height(Space.xs))
            SubLabel("Ограничения")
            if (working.constraints.isEmpty()) EmptyHint() else working.constraints.forEach { Bullet(it) }
            AddRow("Добавить ограничение…", "Добавить") { state.addConstraint(it); refresh++ }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.clearWorking(); refresh++ }) {
                Text("Очистить рабочую", style = MaterialTheme.typography.bodySmall, color = AppColors.muted)
            }
            TextButton(onClick = { state.clearLongTerm(); refresh++ }) {
                Text("Очистить долговременную", style = MaterialTheme.typography.bodySmall, color = AppColors.muted)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refresh++ }) {
                Text("Обновить", style = MaterialTheme.typography.bodySmall, color = AppColors.accent)
            }
        }
    }
}

// ============================== Инварианты (отдельное окно) ==============================

@Composable
fun InvariantsDialog(state: ChatState, onClose: () -> Unit) {
    val all = state.invariants
    val builtIns = all.filter { it.builtIn }
    val userInv = all.filterNot { it.builtIn }

    ModalScaffold("Правила", onClose) {
        Text(
            "Правила, которые ассистент не имеет права нарушать. Учитываются в каждом ответе; при конфликте ассистент отказывается и объясняет причину.",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal), color = AppColors.muted
        )

        // §5.8: у встроенных правил маркеры акцентом — они жёсткие и всегда активны.
        ModalBlock("Встроенные правила", "жёсткие · всегда активны") {
            builtIns.forEach { Bullet(it.text, marker = AppColors.accent) }
        }

        ModalBlock("Ваши правила", "бизнес-правила, ограничения, договорённости") {
            if (userInv.isEmpty()) EmptyHint() else userInv.forEach { inv ->
                InvariantRow(inv, onToggle = { state.toggleInvariant(inv.id) }, onRemove = { state.removeInvariant(inv.id) })
            }
            AddRow("Добавить правило…", "Добавить") { state.addInvariant(it) }
        }
    }
}

@Composable
private fun InvariantRow(inv: Invariant, onToggle: () -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            inv.text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (inv.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onToggle) { Text(if (inv.active) "Вкл" else "Выкл") }
        TextButton(onClick = onRemove) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
    }
}

// ============================== Пробное собеседование (отдельное окно) ==============================

@Composable
fun InterviewDialog(state: ChatState) {
    DialogWindow(
        onCloseRequest = { state.closeInterview() },
        state = rememberDialogState(size = DpSize(640.dp, 680.dp)),
        title = "Пробное собеседование"
    ) {
        AdventTheme(dark = state.config.darkTheme) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val scroll = rememberScrollState()
                LaunchedEffect(state.interviewMessages.size, state.interviewLoading) {
                    if (state.config.reducedMotion) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue)
                }
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Пробное собеседование", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Тренировка с «визовым офицером». Основная задача не двигается — после окончания вернётесь на свой шаг.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.interviewMessages.forEach { m ->
                            val mine = m.role == Role.User
                            Text(
                                if (mine) "Вы" else "Визовый офицер",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (mine) MaterialTheme.colorScheme.onSurfaceVariant else AppColors.accent
                            )
                            Text(m.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        if (state.interviewLoading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        }
                    }
                    if (!state.interviewFinished) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.interviewInput,
                                onValueChange = { state.interviewInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Ваш ответ офицеру…") },
                                maxLines = 4,
                                shape = RoundedCornerShape(Radii.sm)
                            )
                            FilledTonalButton(
                                onClick = { state.interviewSubmit() },
                                enabled = !state.interviewLoading && state.interviewInput.isNotBlank(),
                                shape = CircleShape
                            ) { Text("Ответить") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.interviewFinished) {
                            TextButton(
                                onClick = { state.finishInterview() },
                                enabled = !state.interviewLoading && state.interviewMessages.isNotEmpty()
                            ) { Text("Завершить и оценить") }
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { state.closeInterview() }, shape = CircleShape) { Text("Вернуться к задаче") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(2.dp))
            content()
        }
    }
}

@Composable
private fun SubLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = AppColors.accent)
}

/** Пункт списка (§5.8): маркер 5 px выровнен по ПЕРВОЙ строке текста, а не по центру абзаца. */
@Composable
private fun Bullet(text: String, marker: Color? = null) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Box(Modifier.padding(top = 8.dp).size(5.dp).background(marker ?: AppColors.muted, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.ink)
    }
}

@Composable
private fun EmptyHint() {
    Text("пока пусто", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AddRow(placeholder: String, action: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder) },
            singleLine = true,
            shape = RoundedCornerShape(Radii.sm)
        )
        FilledTonalButton(
            onClick = { if (text.isNotBlank()) { onAdd(text.trim()); text = "" } },
            shape = CircleShape
        ) { Text(action) }
    }
}
