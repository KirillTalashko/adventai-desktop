package com.example.adventdesktop.ui

import androidx.compose.ui.unit.dp

/**
 * Отступы раскладки из REDESIGN.md §4, которых нет в `Sizes` (там только размеры каркаса).
 * Отдельный файл, потому что `Dimens.kt` пришёл из хендофф-пакета и правится только вместе с ним.
 */
/** Шкала отступов 4/8/12/16/24/32 — общий ритм; конкретные значения раскладки ниже, в [Layout]. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Layout {
    // Сайдбар
    val sidebarPaddingV = 14.dp
    val sidebarPaddingH = 12.dp
    val sidebarBlockGap = 10.dp
    val sidebarItemGap = 2.dp

    // Шапка чат-панели
    val paneHeaderV = 15.dp
    val paneHeaderH = 26.dp

    // Лента
    val feedPaddingTop = 28.dp
    val feedPaddingH = 24.dp
    val feedPaddingBottom = 10.dp

    // Композер
    val composerOuterTop = 8.dp
    val composerOuterH = 24.dp
    val composerOuterBottom = 18.dp
    val composerInnerV = 10.dp
    val composerInnerH = 12.dp
    val composerFieldMin = 26.dp
    val composerFieldMax = 140.dp

    // Сообщения
    const val userBubbleMaxFraction = 0.78f
    val userBubblePaddingV = 11.dp
    val userBubblePaddingH = 15.dp
    val assistantGutter = 34.dp
    val assistantParagraphGap = 9.dp

    // Модальное окно
    const val dialogMaxHeightFraction = 0.88f
    val dialogPaddingV = 20.dp
    val dialogPaddingH = 22.dp
}
