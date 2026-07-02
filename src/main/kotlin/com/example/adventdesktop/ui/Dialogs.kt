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

// ============================== Настройки ==============================

@Composable
fun SettingsDialog(state: ChatState, onClose: () -> Unit) {
    var orKey by remember { mutableStateOf(state.config.openrouterKey) }
    var dsKey by remember { mutableStateOf(state.config.deepseekKey) }
    var model by remember { mutableStateOf(state.model) }
    var devMode by remember { mutableStateOf(state.config.developerMode) }
    var darkTheme by remember { mutableStateOf(state.config.darkTheme) }
    var reducedMotion by remember { mutableStateOf(state.config.reducedMotion) }
    var proxy by remember { mutableStateOf(state.config.httpProxy) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Ключи хранятся локально в ~/.adventai/config.json (или берутся из переменных окружения).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = orKey, onValueChange = { orKey = it },
                    label = { Text("OpenRouter API-ключ") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dsKey, onValueChange = { dsKey = it },
                    label = { Text("DeepSeek API-ключ") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                Text("Модель по умолчанию", style = MaterialTheme.typography.labelLarge)
                ModelSelector(model) { model = it }
                OutlinedTextField(
                    value = proxy, onValueChange = { proxy = it },
                    label = { Text("HTTP-прокси (необязательно)") },
                    placeholder = { Text("http://127.0.0.1:10809") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Для сетей с локальным туннелем, где прямой выход/DNS закрыты. Пусто — прямое соединение.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingToggle("Тёмная тема", "Тёмное оформление приложения.", darkTheme) { darkTheme = it }
                SettingToggle("Меньше анимаций", "Мгновенная прокрутка без плавных переходов.", reducedMotion) { reducedMotion = it }
                SettingToggle("Режим разработчика", "Показывать инженерные витрины: инструменты MCP, коннекторы, демо-пайплайн.", devMode) { devMode = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                state.saveConfig(
                    state.config.copy(
                        openrouterKey = orKey.trim(), deepseekKey = dsKey.trim(), modelId = model.id,
                        developerMode = devMode, darkTheme = darkTheme, reducedMotion = reducedMotion,
                        httpProxy = proxy.trim(),
                    )
                )
                onClose()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } }
    )
}

/** Строка-настройка «название + описание + ползунок» (аудит #10 — единый компактный вид для тумблеров). */
@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = AppColors.accent))
    }
}

@Composable
private fun ModelSelector(selected: ModelOption, onSelect: (ModelOption) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(Radii.sm),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().clickable { open = true }
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
                    Text("Профиль предпочтений", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = DpSize(700.dp, 660.dp)),
        title = "Память"
    ) {
        AdventTheme(dark = state.config.darkTheme) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                var refresh by remember { mutableStateOf(0) }
                val longTerm = remember(refresh) { state.longTerm() }
                val working = remember(refresh) { state.working() }
                val profileItems = remember(longTerm) {
                    longTerm.profile.lines().mapNotNull { line ->
                        val t = line.trim()
                        if (t.startsWith("-")) t.removePrefix("-").trim().takeIf { it.isNotEmpty() } else null
                    }
                }

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Заголовок
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Память", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "Что агент помнит о вас и текущей задаче",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { refresh++ }) { Text("Обновить") }
                    }

                    // Долговременная
                    MemoryCard("Долговременная память", "профиль и решения · сохраняется между сессиями") {
                        SubLabel("Профиль")
                        if (profileItems.isEmpty()) EmptyHint() else profileItems.forEach { Bullet(it) }
                        AddRow("Добавить факт о пользователе…", "Добавить") { state.addProfileFact(it); refresh++ }

                        Spacer(Modifier.size(2.dp))
                        SubLabel("Решения")
                        if (longTerm.decisions.isEmpty()) EmptyHint() else longTerm.decisions.forEach { Bullet(it) }
                        AddRow("Добавить решение / договорённость…", "Добавить") { state.addDecision(it); refresh++ }
                    }

                    // Рабочая
                    MemoryCard("Рабочая память", "цель и ограничения · текущий диалог") {
                        SubLabel("Цель")
                        Text(
                            working.goal.ifBlank { "—" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (working.goal.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        AddRow("Задать / изменить цель задачи…", "Задать") { state.setGoal(it); refresh++ }

                        Spacer(Modifier.size(2.dp))
                        SubLabel("Ограничения")
                        if (working.constraints.isEmpty()) EmptyHint() else working.constraints.forEach { Bullet(it) }
                        AddRow("Добавить ограничение…", "Добавить") { state.addConstraint(it); refresh++ }
                    }

                    // Низ
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { state.clearWorking(); refresh++ }) { Text("Очистить рабочую") }
                        TextButton(onClick = { state.clearLongTerm(); refresh++ }) { Text("Очистить долговременную") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onClose, shape = RoundedCornerShape(Radii.sm)) { Text("Готово") }
                    }
                    Text(
                        "Память пополняется автоматически по ходу диалога; здесь можно дополнить вручную.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ============================== Инварианты (отдельное окно) ==============================

@Composable
fun InvariantsDialog(state: ChatState, onClose: () -> Unit) {
    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = DpSize(700.dp, 640.dp)),
        title = "Правила"
    ) {
        AdventTheme(dark = state.config.darkTheme) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val all = state.invariants
                val builtIns = all.filter { it.builtIn }
                val userInv = all.filterNot { it.builtIn }

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Правила", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Правила, которые ассистент не имеет права нарушать. Хранятся отдельно от диалога, учитываются в каждом ответе; при конфликте ассистент отказывается и объясняет причину.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    MemoryCard("Встроенные правила", "жёсткие · всегда активны") {
                        builtIns.forEach { Bullet(it.text) }
                    }

                    MemoryCard("Ваши правила", "бизнес-правила, ограничения по бюджету/стеку, договорённости") {
                        if (userInv.isEmpty()) EmptyHint() else userInv.forEach { inv ->
                            InvariantRow(inv, onToggle = { state.toggleInvariant(inv.id) }, onRemove = { state.removeInvariant(inv.id) })
                        }
                        AddRow("Добавить правило…", "Добавить") { state.addInvariant(it) }
                    }

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onClose, shape = RoundedCornerShape(Radii.sm)) { Text("Готово") }
                    }
                }
            }
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
                    Text("Пробное собеседование", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
                                shape = RoundedCornerShape(Radii.sm)
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
                        Button(onClick = { state.closeInterview() }, shape = RoundedCornerShape(Radii.sm)) { Text("Вернуться к задаче") }
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
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = AppColors.accent)
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 7.dp).size(5.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
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
            shape = RoundedCornerShape(Radii.sm)
        ) { Text(action) }
    }
}
