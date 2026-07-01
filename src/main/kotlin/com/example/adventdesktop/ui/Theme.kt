package com.example.adventdesktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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

/** Тёмная палитра (аудит Рамса #9): тот же синий акцент `#2F6BED`, тёмные фон/поверхности, светлый текст. */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFEDEDEA),            // светлые основные действия на тёмном
    onPrimary = Color(0xFF1A1A19),
    primaryContainer = Color(0xFF2A2A28),
    onPrimaryContainer = Color(0xFFEDEDEA),
    secondary = Color(0xFF6E9BF5),          // акцент, чуть светлее для контраста на тёмном
    onSecondary = Color(0xFF0A1B3A),
    secondaryContainer = Color(0xFF20365E), // активный диалог
    onSecondaryContainer = Color(0xFFD6E2FB),
    background = Color(0xFF17171A),
    onBackground = Color(0xFFE6E6E3),
    surface = Color(0xFF1E1E22),
    onSurface = Color(0xFFE6E6E3),
    surfaceVariant = Color(0xFF2A2A2E),     // сайдбар/«пилюли»/чипы на тёмном
    onSurfaceVariant = Color(0xFFA6A6A1),
    outline = Color(0xFF45454A),
    outlineVariant = Color(0xFF34343A),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF5C1D1A),
    onErrorContainer = Color(0xFFF9DEDC)
)

/** Дополнительные цвета вне Material-схемы (акцент одинаков в свете/тьме — бренд). */
object AppColors {
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
fun AdventTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
