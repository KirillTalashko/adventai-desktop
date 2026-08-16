package com.example.adventdesktop.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Типографика редизайна (REDESIGN_CLAUDE_DESIGN.md §3): засечки в заголовках задают «редакторское»
 * ощущение claude.ai, спокойный sans — в теле, моноширинный — для чисел/токенов/id.
 *
 * Шрифты берём системные (`FontFamily.Serif/SansSerif/Monospace`), а не встроенные `.ttf`:
 * бренд-шрифты Claude (Styrene/Tiempos) несвободны, а OFL-аналоги (Newsreader/Inter) пришлось бы
 * скачивать и класть в `resources/fonts` — отдельный шаг с загрузкой файлов. Системные семейства
 * гарантированно покрывают кириллицу и не тянут вес в дистрибутив.
 */
object AppFonts {
    val serif: FontFamily = FontFamily.Serif       // заголовки, приветствие, пустое состояние
    val sans: FontFamily = FontFamily.SansSerif    // тело, UI, кнопки, списки
    val mono: FontFamily = FontFamily.Monospace    // токены, метрики, chunk_id, код
}

/** Заголовочный стиль: засечки + слегка отрицательный трекинг (крупный текст «стягивается»). */
private fun serifHead(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = AppFonts.serif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = (-0.5).sp,
)

/** Текстовый стиль тела: sans, высота строки ≈1.5 — длинные ответы агента читаются как страница. */
private fun sans(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) = TextStyle(
    fontFamily = AppFonts.sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
)

val AppTypography = Typography(
    displayLarge = serifHead(52, 60),
    displayMedium = serifHead(42, 50),
    displaySmall = serifHead(34, 42),           // приветствие пустого состояния
    headlineLarge = serifHead(30, 38),
    headlineMedium = serifHead(26, 34),
    headlineSmall = serifHead(22, 30),          // заголовки окон-диалогов
    titleLarge = serifHead(20, 28, FontWeight.Medium),
    titleMedium = serifHead(17, 24, FontWeight.Medium),  // заголовок диалога/секции
    titleSmall = sans(14, 20, FontWeight.SemiBold, 0.1),
    bodyLarge = sans(16, 24),
    bodyMedium = sans(14, 21),
    bodySmall = sans(13, 19),
    labelLarge = sans(14, 20, FontWeight.Medium, 0.1),
    labelMedium = sans(12, 16, FontWeight.Medium, 0.2),
    labelSmall = sans(11, 15, FontWeight.Medium, 0.2),
)
