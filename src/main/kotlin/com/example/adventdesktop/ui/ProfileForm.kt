package com.example.adventdesktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.adventdesktop.domain.FormatPref
import com.example.adventdesktop.domain.ResponseLength
import com.example.adventdesktop.domain.Tone
import com.example.adventdesktop.domain.UserProfile

/**
 * Форма профиля предпочтений (Day 12) — общая для онбординга и редактирования.
 * Держит локальное состояние полей; по кнопке отдаёт собранный [UserProfile].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileForm(
    initial: UserProfile,
    submitLabel: String,
    onSubmit: (UserProfile) -> Unit,
    onCancel: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initial.name) }
    var about by remember { mutableStateOf(initial.about) }
    var length by remember { mutableStateOf(initial.length) }
    var tone by remember { mutableStateOf(initial.tone) }
    var formats by remember { mutableStateOf(initial.formats) }
    var constraints by remember { mutableStateOf(initial.constraints) }
    var language by remember { mutableStateOf(initial.language) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = about, onValueChange = { about = it },
            label = { Text("О себе (контекст)") },
            placeholder = { Text("например: гражданин РФ, еду впервые, турист") },
            minLines = 2, modifier = Modifier.fillMaxWidth()
        )

        FieldLabel("Длина ответа")
        SingleChips(ResponseLength.entries, length, { it.title }) { length = it }

        FieldLabel("Тон")
        SingleChips(Tone.entries, tone, { it.title }) { tone = it }

        FieldLabel("Формат ответа")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FormatPref.entries.forEach { pref ->
                FilterChip(
                    selected = pref in formats,
                    onClick = { formats = if (pref in formats) formats - pref else formats + pref },
                    label = { Text(pref.title) }
                )
            }
        }

        OutlinedTextField(
            value = constraints, onValueChange = { constraints = it },
            label = { Text("Ограничения / предпочтения") },
            placeholder = { Text("например: только бесплатные источники, объясняй простыми словами") },
            minLines = 2, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = language, onValueChange = { language = it },
            label = { Text("Язык ответов") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text("Отмена") }
            }
            Button(onClick = {
                onSubmit(
                    UserProfile(
                        name = name.trim(), about = about.trim(), length = length, tone = tone,
                        formats = formats, constraints = constraints.trim(), language = language.trim().ifBlank { "Русский" }
                    )
                )
            }) { Text(submitLabel) }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun <T> SingleChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}
