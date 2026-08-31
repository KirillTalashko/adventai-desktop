package com.example.adventdesktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Редизайн «бумага + документ»: тёплый бумажный фон, спокойный синий акцент,
 * серифные заголовки (см. Type.kt), паспортная символика.
 *
 * Публичный API файла НЕ изменился: AdventTheme / AppColors / StatusColors.
 * Существующие вызовы AppColors.accent, StatusColors.*, MaterialTheme.colorScheme.*
 * продолжают компилироваться и автоматически получают новую палитру.
 *
 * Новое: AppColors и StatusColors — Composable-геттеры, значения зависят от темы
 * (в тёмной теме акцент светлее, иначе синий проваливается в фон).
 */

/** Сырые значения палитры — доступны вне composable-контекста. */
object AppPalette {
    // Светлая
    val deskLight = Color(0xFFE7E4DA)
    val bgLight = Color(0xFFFAF9F5)
    val surfaceLight = Color(0xFFFFFFFF)
    val sidebarLight = Color(0xFFF0EEE6)
    val inkLight = Color(0xFF1F1E1D)
    val mutedLight = Color(0xFF6B6A63)
    val outlineLight = Color(0xFFE3E1D8)
    val outlineVariantLight = Color(0xFFEAE8DF)
    val accentLight = Color(0xFF2F6BED)
    val accentHoverLight = Color(0xFF5286F2)
    val winbarLight = Color(0xFFEFEDE4)
    val shadowLight = Color(0x333C372D)

    // Тёмная («тёплый уголь»)
    val deskDark = Color(0xFF141311)
    val bgDark = Color(0xFF22211E)
    val surfaceDark = Color(0xFF2B2A27)
    val sidebarDark = Color(0xFF1D1C1A)
    val inkDark = Color(0xFFECEAE3)
    val mutedDark = Color(0xFFA3A199)
    val outlineDark = Color(0xFF3A3833)
    val outlineVariantDark = Color(0xFF33322E)
    val accentDark = Color(0xFF6FA0F5)
    val accentHoverDark = Color(0xFF8AB4F8)
    val winbarDark = Color(0xFF1D1C1A)
    val shadowDark = Color(0x8C000000)
}

private val LightColors = lightColorScheme(
    primary = AppPalette.inkLight,                  // чернильная кнопка отправки
    onPrimary = AppPalette.bgLight,
    primaryContainer = AppPalette.sidebarLight,
    onPrimaryContainer = AppPalette.inkLight,
    secondary = AppPalette.accentLight,             // акцент
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7EEFD),         // активный диалог
    onSecondaryContainer = Color(0xFF1E4699),
    background = AppPalette.bgLight,
    onBackground = AppPalette.inkLight,
    surface = AppPalette.surfaceLight,
    onSurface = AppPalette.inkLight,
    surfaceVariant = AppPalette.sidebarLight,       // пузырь пользователя, чипы, hover
    onSurfaceVariant = AppPalette.mutedLight,
    surfaceContainerLowest = AppPalette.surfaceLight,
    surfaceContainerLow = AppPalette.bgLight,
    surfaceContainer = AppPalette.sidebarLight,     // сайдбар
    surfaceContainerHigh = AppPalette.winbarLight,  // полоса заголовка окна
    outline = AppPalette.outlineLight,
    outlineVariant = AppPalette.outlineVariantLight,
    scrim = AppPalette.inkLight.copy(alpha = 0.32f),
    error = Color(0xFFA33227),
    errorContainer = Color(0xFFF7E7E3),
    onErrorContainer = Color(0xFF6E2118)
)

private val DarkColors = darkColorScheme(
    primary = AppPalette.inkDark,
    onPrimary = AppPalette.bgDark,
    primaryContainer = Color(0xFF302F2B),
    onPrimaryContainer = AppPalette.inkDark,
    secondary = AppPalette.accentDark,
    onSecondary = Color(0xFF0C1B36),
    secondaryContainer = Color(0xFF1E2C4A),
    onSecondaryContainer = Color(0xFFBBD3FB),
    background = AppPalette.bgDark,
    onBackground = AppPalette.inkDark,
    surface = AppPalette.surfaceDark,
    onSurface = AppPalette.inkDark,
    surfaceVariant = Color(0xFF302F2B),
    onSurfaceVariant = AppPalette.mutedDark,
    surfaceContainerLowest = AppPalette.sidebarDark,
    surfaceContainerLow = AppPalette.bgDark,
    surfaceContainer = AppPalette.sidebarDark,
    surfaceContainerHigh = AppPalette.winbarDark,
    outline = AppPalette.outlineDark,
    outlineVariant = AppPalette.outlineVariantDark,
    scrim = Color(0x8C000000),
    error = Color(0xFFE8A79C),
    errorContainer = Color(0xFF4A211A),
    onErrorContainer = Color(0xFFF6DCD6)
)

/** Тёмная ли тема — чтобы токены вне Material-схемы умели переключаться. */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * Токены вне Material-схемы. Читаются как раньше: AppColors.accent.
 * Если нужно значение вне composable — берите AppPalette.
 */
object AppColors {
    val accent: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.secondary

    val accentHover: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalIsDarkTheme.current) AppPalette.accentHoverDark else AppPalette.accentHoverLight

    /** Основной текст. */
    val ink: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface

    /** Вторичный текст и иконки. */
    val muted: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

    /** Фон сайдбара. */
    val sidebar: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer

    /** Фон чипов (модель, память, токены). */
    val chip: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant

    /** Полоса заголовка окна. */
    val winbar: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh

    /** «Стол» вокруг окна. */
    val desk: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalIsDarkTheme.current) AppPalette.deskDark else AppPalette.deskLight

    /** Тёплый цвет тени — для Modifier.shadow(ambientColor/spotColor). */
    val shadow: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalIsDarkTheme.current) AppPalette.shadowDark else AppPalette.shadowLight
}

/** Цвета статусов документов чек-листа. */
object StatusColors {
    val needed: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurfaceVariant
    val uploaded: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.secondary
    val verified: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalIsDarkTheme.current) Color(0xFF79A863) else Color(0xFF4F7A3A)
    val missing: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalIsDarkTheme.current) Color(0xFFD08A2E) else Color(0xFFB26A00)

    /** Фон чипа статуса — тот же цвет с малой альфой. */
    fun chipBg(status: Color): Color = status.copy(alpha = 0.13f)
}

@Composable
fun AdventTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIsDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AdventTypography,
            content = content
        )
    }
}
