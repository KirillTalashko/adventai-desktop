package com.example.adventdesktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Палитра «бумага + терракота» (REDESIGN_CLAUDE_DESIGN.md §2): тёплый кремовый холст вместо холодного
 * белого, плоскость с волосяными границами (теней нет), один тёплый акцент. Синий бренд-акцент из
 * интерфейса убран — остаётся только в иконке приложения.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F1E1D),            // круглая кнопка ↑ и primary-действия — тёмные чернила
    onPrimary = Color(0xFFFAF9F5),
    primaryContainer = Color(0xFFF0EEE6),
    onPrimaryContainer = Color(0xFF1F1E1D),
    secondary = Color(0xFFC96442),          // терракота: активное, выбранное, ссылки, бренд-марка
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3E7DF), // тёплая заливка активного диалога
    onSecondaryContainer = Color(0xFF7A3B23),
    background = Color(0xFFFAF9F5),         // холст ленты — «бумага»
    onBackground = Color(0xFF1F1E1D),
    surface = Color(0xFFFFFFFF),            // приподнятые поверхности: карточки, композер
    onSurface = Color(0xFF1F1E1D),
    surfaceVariant = Color(0xFFF0EEE6),     // сайдбар, «пилюли» пользователя, чипы — глубокая бумага
    onSurfaceVariant = Color(0xFF6B6A63),   // подписи, мета, плейсхолдеры
    outline = Color(0xFFE3E1D8),            // волосяные линии и рамки
    outlineVariant = Color(0xFFEAE8DF),     // ещё тише — разделители в списках
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF7E5E0),
    onErrorContainer = Color(0xFF7A2118)
)

/** Тёмная тема — «тёплый уголь» (не чёрный): тот же язык, акцент чуть ярче для контраста. */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFECEAE3),            // светлые действия на тёмном
    onPrimary = Color(0xFF22211E),
    primaryContainer = Color(0xFF33322E),
    onPrimaryContainer = Color(0xFFECEAE3),
    secondary = Color(0xFFD97757),
    onSecondary = Color(0xFF2A1409),
    secondaryContainer = Color(0xFF3D2A20),
    onSecondaryContainer = Color(0xFFF0C9B4),
    background = Color(0xFF22211E),
    onBackground = Color(0xFFECEAE3),
    surface = Color(0xFF2B2A27),
    onSurface = Color(0xFFECEAE3),
    surfaceVariant = Color(0xFF1D1C1A),     // сайдбар/чипы на тёмном — глубже фона
    onSurfaceVariant = Color(0xFFA3A199),
    outline = Color(0xFF3A3833),
    outlineVariant = Color(0xFF33322E),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF4A211B),
    onErrorContainer = Color(0xFFF9DEDC)
)

/**
 * Статусы документов чек-листа: они вне Material-схемы, но обязаны меняться со сменой темы,
 * поэтому едут через CompositionLocal (а не как константы объекта — иначе тёмная тема получила бы
 * светлые значения). Значения тёплые: загруженный — акцент, проверенный — тёплый зелёный, амбер.
 */
@Immutable
data class StatusPalette(
    val needed: Color,
    val uploaded: Color,
    val verified: Color,
    val missing: Color,
)

private val LightStatus = StatusPalette(
    needed = Color(0xFF6B6A63),
    uploaded = Color(0xFFC96442),
    verified = Color(0xFF4F7A3A),
    missing = Color(0xFFB26A00),
)

private val DarkStatus = StatusPalette(
    needed = Color(0xFFA3A199),
    uploaded = Color(0xFFD97757),
    verified = Color(0xFF79A863),
    missing = Color(0xFFD08A2E),
)

private val LocalStatusPalette = staticCompositionLocalOf { LightStatus }

/**
 * Акцент интерфейса — терракота. Читается из схемы (`secondary`), поэтому в тёмной теме
 * автоматически берётся более светлый оттенок: hex не дублируется по коду.
 *
 * Контраст (§2): терракота НЕ используется как цвет мелкого текста на бумаге — только заливки,
 * границы, иконки и крупные/полужирные подписи.
 */
object AppColors {
    val accent: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.secondary

    /**
     * Тот же тёплый акцент, но пригодный для МЕЛКОГО текста: тёмная терракота на бумаге
     * (`#7A3B23` ≈ 7.9:1) и светлая на угле. Чистый `accent` на белом даёт ~3.3:1 — для подписей мало.
     */
    val accentText: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSecondaryContainer
}

/** Цвета статусов документов чек-листа (свет/тьма — через [LocalStatusPalette]). */
object StatusColors {
    val needed: Color @Composable @ReadOnlyComposable get() = LocalStatusPalette.current.needed
    val uploaded: Color @Composable @ReadOnlyComposable get() = LocalStatusPalette.current.uploaded
    val verified: Color @Composable @ReadOnlyComposable get() = LocalStatusPalette.current.verified
    val missing: Color @Composable @ReadOnlyComposable get() = LocalStatusPalette.current.missing
}

@Composable
fun AdventTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusPalette provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            content = content
        )
    }
}
