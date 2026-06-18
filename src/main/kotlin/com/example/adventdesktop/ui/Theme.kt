package com.example.adventdesktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Палитра в духе Claude Code: тёплый почти-белый фон, тёмные primary-кнопки, один синий акцент. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1A1A19),            // кнопка отправки / основные действия (тёмная)
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDEDEA),
    onPrimaryContainer = Color(0xFF1C1C1A),
    secondary = Color(0xFF2F6BED),          // акцент
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7EEFC), // активный диалог
    onSecondaryContainer = Color(0xFF15315F),
    background = Color(0xFFFAFAF9),
    onBackground = Color(0xFF1C1C1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1A),
    surfaceVariant = Color(0xFFF1F1EF),     // «пилюли» пользователя, чипы
    onSurfaceVariant = Color(0xFF6B6B66),
    outline = Color(0xFFD9D9D4),
    outlineVariant = Color(0xFFE7E7E3),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFBE6E4),
    onErrorContainer = Color(0xFF7A1C16)
)

/** Дополнительные цвета вне Material-схемы. */
object AppColors {
    val sidebar = Color(0xFFF4F4F2)
    val accent = Color(0xFF2F6BED)
}

/** Цвета статусов документов чек-листа. */
object StatusColors {
    val needed = Color(0xFF6B7280)    // нужен
    val uploaded = Color(0xFF2F6BED)  // загружен
    val verified = Color(0xFF2E7D32)  // проверен
    val missing = Color(0xFFB26A00)   // не хватает / нет
}

@Composable
fun AdventTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
