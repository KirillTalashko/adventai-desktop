package com.example.adventdesktop.ui

import androidx.compose.ui.unit.dp

/**
 * Радиусы редизайна. Имена шкалы (xs…xl) сохранены — существующие вызовы Radii.sm / Radii.md
 * компилируются как раньше и автоматически получают новые значения.
 */
object Radii {
    val xs = 8.dp    // мелкие чипы, строка токенов
    val sm = 12.dp   // поля, кнопки, элемент диалога в сайдбаре, меню аккаунта
    val md = 14.dp   // выпадающее меню модели
    val lg = 18.dp   // карточки, пузырь сообщения, блоки памяти и правил
    val xl = 24.dp   // композер, карточка онбординга

    val icon = 10.dp     // иконочные кнопки сайдбара
    val dialog = 22.dp   // модальные окна
    val window = 16.dp   // окно приложения
    val pill = 999.dp    // чипы-подсказки, чип статуса, переключатели
}

/** Размеры каркаса — чтобы не «дрейфовали» по коду. */
object Sizes {
    val sidebarWidth = 272.dp
    val winbarHeight = 42.dp
    val chatContentMaxWidth = 740.dp
    val composerMaxWidth = 760.dp
    val dialogMaxWidth = 560.dp
    val onboardingMaxWidth = 520.dp

    val sendButton = 38.dp
    val attachButton = 34.dp
    val avatar = 26.dp
    val emblem = 18.dp
    val statusDot = 9.dp
    val switchTrackWidth = 42.dp
    val switchTrackHeight = 22.dp
    val switchThumb = 18.dp

    val messageGap = 22.dp
    val activeIndicator = 3.dp
}

/** Elevation для Modifier.shadow — CSS-тени со смещением в Compose не воспроизводятся. */
object Elevations {
    val composer = 2.dp
    val card = 8.dp
    val menu = 8.dp
    val dialog = 16.dp
    val window = 24.dp
}
