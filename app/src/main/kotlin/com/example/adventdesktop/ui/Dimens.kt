package com.example.adventdesktop.ui

import androidx.compose.ui.unit.dp

/**
 * Единый источник токенов радиусов (аудит Рамса, #3 aesthetic) — чтобы они не «дрейфовали» по коду.
 * Редизайн «бумага + терракота» (REDESIGN_CLAUDE_DESIGN.md §4) сместил шкалу к щедрым значениям:
 * самый скруглённый элемент интерфейса — композер (`xl`).
 */
object Radii {
    val xs = 8.dp   // мелкие пилюли/чипы/статусы
    val sm = 12.dp  // кнопки, айтемы списка, дропдауны
    val md = 14.dp  // карточки
    val lg = 18.dp  // бабл сообщения, карточки памяти/RAG
    val xl = 24.dp  // композер, онбординг, окна-диалоги
}

/**
 * Шкала отступов 4/8/12/16/24/32 (§4). Ленту сообщений держим в центрированной колонке
 * [Layout.readingWidth] с крупными полями — «воздух» страницы, а не плотный экран приложения.
 */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Размеры каркаса: ширина сайдбара и колонки чтения. */
object Layout {
    val sidebar = 272.dp
    val readingWidth = 740.dp
}
