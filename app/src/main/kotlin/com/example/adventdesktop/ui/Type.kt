package com.example.adventdesktop.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as ResourceFont
import androidx.compose.ui.unit.sp

/**
 * Типографика редизайна: серифные заголовки (Newsreader), санс для интерфейса и текста (Inter),
 * моноширинный для токенов и счётчиков (JetBrains Mono).
 *
 * Файлы шрифтов положить в src/main/resources/fonts/ (имена — ниже). Если файла нет,
 * семейство молча падает на системный аналог: приложение соберётся и запустится в любом случае,
 * просто без нужного начертания.
 *
 * Правило: серифом — только заголовки и «шапки» карточек.
 * Никогда — тело сообщений и элементы управления.
 */

private fun resFont(path: String, weight: FontWeight): Font? =
    runCatching { ResourceFont(resource = path, weight = weight) }.getOrNull()

private fun family(vararg specs: Pair<String, FontWeight>, fallback: FontFamily): FontFamily {
    val fonts = specs.mapNotNull { (path, weight) -> resFont(path, weight) }
    return if (fonts.isEmpty()) fallback else FontFamily(fonts)
}

/** Заголовки и «документные» подписи. */
val Serif: FontFamily = family(
    "fonts/Newsreader-Regular.ttf" to FontWeight.Normal,
    "fonts/Newsreader-Medium.ttf" to FontWeight.Medium,
    "fonts/Newsreader-SemiBold.ttf" to FontWeight.SemiBold,
    fallback = FontFamily.Serif
)

/** Интерфейс и основной текст. */
val Sans: FontFamily = family(
    "fonts/Inter-Regular.ttf" to FontWeight.Normal,
    "fonts/Inter-Medium.ttf" to FontWeight.Medium,
    "fonts/Inter-SemiBold.ttf" to FontWeight.SemiBold,
    "fonts/Inter-Bold.ttf" to FontWeight.Bold,
    fallback = FontFamily.SansSerif
)

/** Токены, счётчики, стоимость. */
val Mono: FontFamily = family(
    "fonts/JetBrainsMono-Regular.ttf" to FontWeight.Normal,
    "fonts/JetBrainsMono-Medium.ttf" to FontWeight.Medium,
    fallback = FontFamily.Monospace
)

/**
 * Шкала. Существующий код уже читает MaterialTheme.typography, поэтому новая типографика
 * доезжает до всех экранов без правок App.kt и Dialogs.kt.
 *
 *   display, headline — серифные крупные заголовки (пустое состояние, онбординг)
 *   title            — серифные заголовки диалогов, панели, карточек
 *   body             — санс: 15 сообщения, 14 списки, 12 сноски
 *   label            — санс: 13 кнопки, 12 подписи, 11 микро-лейблы
 */
val AdventTypography = Typography(
    displayLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-0.6).sp),
    displayMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-0.5).sp),
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 37.sp, letterSpacing = (-0.4).sp),

    headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 37.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 33.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 29.sp),

    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp),

    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),

    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.6.sp)
)

/** Строка токенов и стоимости — моноширинный, вне Material-шкалы. */
val TokenStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp)
