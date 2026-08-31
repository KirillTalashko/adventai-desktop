package com.example.adventdesktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import com.example.adventdesktop.domain.Awaiting
import com.example.adventdesktop.domain.TaskContext
import com.example.adventdesktop.domain.TaskState
import kotlinx.coroutines.delay
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.interaction.collectIsHoveredAsState

/**
 * Статус-строка задачи (День 13) — как у Claude Code: эмблема · этап (думает/планирует/исполняет/
 * проверяет) · время · токены. Без панелей; рендерится в потоке диалога под последним сообщением.
 */
@Composable
fun TaskStatusLine(state: ChatState) {
    val ctx = state.task ?: return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.loading, state.opStartedAtMs) {
        while (state.loading) { now = System.currentTimeMillis(); delay(500) }
    }
    val verb = when {
        state.loading -> "думает"
        ctx.isDone -> "готово"
        ctx.awaiting == Awaiting.ANSWER -> "ждёт ответа"
        ctx.awaiting == Awaiting.CHOICE -> "ждёт выбор"
        ctx.awaiting == Awaiting.DOCUMENT -> "ждёт документ"
        else -> ctx.state.verb
    }
    val seconds = if (state.loading && state.opStartedAtMs > 0) ((now - state.opStartedAtMs) / 1000).coerceAtLeast(0)
    else state.lastOpSeconds
    Column(Modifier.fillMaxWidth()) {
        // §5.6: строка задачи отделена сверху ПУНКТИРНОЙ линией — она принадлежит композеру, а не ленте.
        val outline = MaterialTheme.colorScheme.outline
        Canvas(Modifier.fillMaxWidth().height(1.dp)) {
            drawLine(
                color = outline,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = size.height,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PassportEmblem(loading = state.loading, modifier = Modifier.size(16.dp), reducedMotion = state.config.reducedMotion)
            Text(verb, style = MaterialTheme.typography.bodySmall, color = AppColors.muted)
            if (seconds > 0) Text("· ${fmtElapsed(seconds)}", style = MaterialTheme.typography.bodySmall, color = AppColors.muted)
            if (state.lastPromptTokens > 0) Text("· ${state.lastPromptTokens} ток.", style = MaterialTheme.typography.bodySmall, color = AppColors.muted)
        }
    }
}


/** Статусы плашки загрузки (§5.5) — сменяются по кругу, пока идёт ответ. */
private val LOADER_STATUSES = listOf("Проверяю документы…", "Сверяю требования…", "Готовлю чек-лист…")

/** Как часто меняется статус (§5.5). */
private const val LOADER_STATUS_MS = 950L

/** Длительность цикла перелистывания и сдвиг фазы между страницами (§5.5). */
private const val FLIP_MS = 2100
private const val FLIP_PHASE_MS = 350
private const val FLIP_PAGES = 6

/**
 * Индикатор ожидания ответа (§5.5): плашка с «листающимся» паспортом и сменой статуса.
 * Заменяет три точки — паспорт здесь несёт смысл продукта, а не украшает.
 *
 * При «Меньше анимаций» паспорт статичен и статус не меняется: настройка обязана гасить и цикл
 * перелистывания, и подмену текста, иначе она выключает только часть движения.
 */
@Composable
fun PassportLoader(reducedMotion: Boolean) {
    var statusIndex by remember { mutableStateOf(0) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        while (true) {
            delay(LOADER_STATUS_MS)
            statusIndex = (statusIndex + 1) % LOADER_STATUSES.size
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            Modifier.padding(start = 15.dp, end = 20.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FlippingPassport(reducedMotion)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    LOADER_STATUSES[statusIndex],
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp),
                    color = AppColors.ink
                )
                Text(
                    "Визовый специалист изучает пакет",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.muted
                )
            }
        }
    }
}

/**
 * Паспорт 62 × 46 с перелистыванием (§5.5): обложка + блок страниц, поверх — шесть страниц,
 * каждая вращается вокруг ЛЕВОГО края (корешка) со сдвигом фазы. Обратная сторона страницы не
 * показывается (аналог `backface-visibility: hidden`) — иначе на второй половине оборота видно
 * «изнанку» и лист выглядит вывернутым.
 */
@Composable
private fun FlippingPassport(reducedMotion: Boolean) {
    val transition = rememberInfiniteTransition(label = "passport")
    val sway by if (reducedMotion) remember { mutableStateOf(0f) } else transition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(FLIP_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "sway"
    )
    Box(Modifier.size(width = 62.dp, height = 46.dp).graphicsLayer { translationY = sway }) {
        Row(Modifier.align(Alignment.Center)) {
            // Обложка: скругления «5 3 3 5» — со стороны корешка радиус меньше.
            Box(
                Modifier.size(width = 31.dp, height = 43.dp)
                    .background(
                        AppColors.accent,
                        RoundedCornerShape(topStart = 5.dp, topEnd = 3.dp, bottomEnd = 3.dp, bottomStart = 5.dp)
                    )
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    // Глобус — контурный круг 11.
                    drawCircle(
                        Color.White.copy(alpha = 0.92f),
                        radius = 5.5.dp.toPx(),
                        center = Offset(w / 2f, h * 0.40f),
                        style = Stroke(width = 1.1.dp.toPx())
                    )
                    // Полоска-надпись под ним.
                    drawLine(
                        Color.White.copy(alpha = 0.75f),
                        start = Offset(w / 2f - 7.5.dp.toPx(), h * 0.72f),
                        end = Offset(w / 2f + 7.5.dp.toPx(), h * 0.72f),
                        strokeWidth = 1.6.dp.toPx()
                    )
                    // Внутренняя тень у корешка — справа.
                    drawRect(
                        Color.Black.copy(alpha = 0.14f),
                        topLeft = Offset(w - 3.dp.toPx(), 0f),
                        size = Size(3.dp.toPx(), h)
                    )
                }
            }
            // Блок страниц: без левой границы — он «вложен» в обложку.
            Box(
                Modifier.padding(top = 2.dp).size(width = 28.dp, height = 39.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                    .border(
                        1.dp, MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)
                    )
            ) {
                if (!reducedMotion) {
                    repeat(FLIP_PAGES) { i ->
                        val angle by transition.animateFloat(
                            initialValue = 0f,
                            targetValue = -180f,
                            animationSpec = infiniteRepeatable(
                                tween(FLIP_MS, easing = CubicBezierEasing(0.55f, 0.06f, 0.4f, 1f)),
                                initialStartOffset = StartOffset(i * FLIP_PHASE_MS)
                            ),
                            label = "page$i"
                        )
                        Box(
                            Modifier.fillMaxSize()
                                .graphicsLayer {
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                    rotationY = angle
                                    cameraDistance = 12f * density
                                    // Аналог backface-visibility: после 90° лист скрываем.
                                    alpha = if (angle < -90f) 0f else 1f
                                }
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }
            }
        }
    }
}

/** Инлайн-действия в диалоге: завершено / продолжить / выбрать подход / приложить документ. Без панелей. */
@Composable
fun TaskInlineActions(state: ChatState) {
    val ctx = state.task ?: return
    if (state.loading) return
    if (ctx.isDone) { DoneInline(state, ctx); return }   // в DONE не прячем — показываем итог и что приложить
    when (ctx.awaiting) {
        Awaiting.CHOICE -> ChoiceInline(state, ctx)
        Awaiting.DOCUMENT -> DocInline(state, ctx)
        Awaiting.ANSWER -> Unit                 // ответ вводится в композере
        Awaiting.NONE -> ContinueInline(state, ctx)
    }
}

/** Финал задачи: если документы ещё не приложены — честно показываем «почти готово» + «Приложить» под каждый. */
@Composable
private fun DoneInline(state: ChatState, ctx: TaskContext) {
    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ctx.pending.isEmpty()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                Text(
                    "✓ Задача завершена",
                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            Text(
                "Почти готово. Осталось приложить документы (${ctx.pending.size}):",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            ctx.pending.forEach { label ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📎 $label", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SmallPrimary("Приложить", enabled = !state.loading) { pickDocFile()?.let { state.provideDocumentFor(label, it) } }
                }
            }
        }
        SmallGhost("Сброс") { state.resetTask() }
    }
}

@Composable
private fun ContinueInline(state: ChatState, ctx: TaskContext) {
    val atPlan = ctx.state == TaskState.EXECUTION && ctx.step == 0 && ctx.done.isEmpty()
    val label = when {
        ctx.state == TaskState.EXECUTION && atPlan -> "Начать выполнение"
        ctx.state == TaskState.EXECUTION -> "Выполнить шаг ${ctx.step + 1}"
        else -> "Продолжить"
    }
    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Предложение доп-активности от агента-разведчика (можно отказаться).
        if (ctx.offer.isNotBlank()) {
            OfferCard(ctx.offer, onAccept = { state.startInterview() }, onDecline = { state.declineOffer() })
        }
        // Информируем о следующем шаге плана.
        if (ctx.state == TaskState.EXECUTION && ctx.current.isNotBlank()) {
            Text(
                "Дальше — шаг ${ctx.step + 1} из ${ctx.total}: ${ctx.current}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (ctx.pending.isNotEmpty()) {
            Text(
                "📎 Позже приложите (кнопкой «+»): ${ctx.pending.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.accent
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallPrimary(label, enabled = state.hasKey) { state.advanceTask() }
            SmallGhost("Сброс") { state.resetTask() }
        }
    }
}

@Composable
private fun OfferCard(text: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    // Предложение агента: бумажная карточка с акцентной волосяной границей — заметно, но без «плашки».
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radii.md),
        border = BorderStroke(1.dp, AppColors.accent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("💬 $text", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallPrimary("Пройти") { onAccept() }
                SmallGhost("Не сейчас") { onDecline() }
            }
        }
    }
}

@Composable
private fun ChoiceInline(state: ChatState, ctx: TaskContext) {
    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Выберите подход (или впишите свой в поле ниже):",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        ctx.options.forEachIndexed { i, opt -> OptionRow("${i + 1}. $opt") { state.chooseApproach(opt) } }
    }
}

@Composable
private fun DocInline(state: ChatState, ctx: TaskContext) {
    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Нужен документ: ${ctx.prompt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallPrimary("Приложить файл") { pickDocFile()?.let { state.provideDocument(it) } }
            SmallGhost("Приложу позже") { state.deferDocument() }
        }
    }
}

/** Кнопка «+» в композере — приложить Word/PDF, когда агент просит документ. */
@Composable
fun AttachButton(state: ChatState) {
    val enabled = state.task != null && !state.loading
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = { pickDocFile()?.let { state.provideDocument(it) } },
        enabled = enabled,
        interactionSource = interaction,
        shape = CircleShape,
        color = hoverColor(interaction, Color.Transparent, MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.size(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = "Приложить документ",
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OptionRow(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radii.sm),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SmallPrimary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(Radii.pill),
        color = when {
            !enabled -> MaterialTheme.colorScheme.outlineVariant
            hovered -> AppColors.accentHover
            else -> AppColors.accent
        }
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 15.dp, vertical = 7.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSecondary
        )
    }
}

@Composable
private fun SmallGhost(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Transparent) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Эмблема-паспорт: закрытый в покое, «листается» (переворот страницы у корешка) во время загрузки. */
@Composable
private fun PassportEmblem(loading: Boolean, modifier: Modifier = Modifier, reducedMotion: Boolean = false) {
    val cover = AppColors.accent
    val pageLight = MaterialTheme.colorScheme.surfaceVariant   // страницы — бумага темы, не холодный голубой
    // «Меньше анимаций» обязано гасить и этот цикл, иначе настройка выключает движение лишь частично.
    val angle by if (reducedMotion) remember { mutableStateOf(0f) }
    else rememberInfiniteTransition(label = "passport").animateFloat(
        initialValue = 0f,
        targetValue = Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(animation = tween(700, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "flip"
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (!loading) {
            // Закрытый паспорт: обложка + эмблема-крест + строки.
            val pw = w * 0.74f
            val left = (w - pw) / 2f
            val rad = w * 0.14f
            drawRoundRect(cover, topLeft = Offset(left, 0f), size = Size(pw, h), cornerRadius = CornerRadius(rad, rad))
            val cx = w / 2f
            val cy = h * 0.40f
            drawCircle(Color.White, radius = w * 0.13f, center = Offset(cx, cy), style = Stroke(width = w * 0.045f))
            drawCircle(Color.White, radius = w * 0.03f, center = Offset(cx, cy))
            drawLine(Color.White.copy(alpha = 0.85f), Offset(left + pw * 0.24f, h * 0.70f), Offset(left + pw * 0.76f, h * 0.70f), strokeWidth = w * 0.035f)
            drawLine(Color.White.copy(alpha = 0.6f), Offset(left + pw * 0.30f, h * 0.82f), Offset(left + pw * 0.70f, h * 0.82f), strokeWidth = w * 0.035f)
        } else {
            // Открытый паспорт: две страницы + переворачивающаяся страница у корешка.
            val top = h * 0.14f
            val bookH = h * 0.72f
            val r = w * 0.08f
            drawRoundRect(cover, topLeft = Offset(0f, top - h * 0.07f), size = Size(w, bookH + h * 0.14f), cornerRadius = CornerRadius(r, r))
            val spine = w / 2f
            val half = w * 0.43f
            drawRect(pageLight, topLeft = Offset(spine - half, top), size = Size(half, bookH))
            drawRect(pageLight, topLeft = Offset(spine, top), size = Size(half, bookH))
            // переворачивающаяся страница: ширина = half·|cos|, по знаку cos уходит вправо→к корешку→влево.
            val c = cos(angle)
            val pageW = half * abs(c)
            val pageLeft = if (c >= 0f) spine else spine - pageW
            drawRect(Color.White, topLeft = Offset(pageLeft, top), size = Size(pageW, bookH))
            val edge = if (c >= 0f) spine + pageW else spine - pageW
            drawLine(cover.copy(alpha = 0.5f), Offset(edge, top), Offset(edge, top + bookH), strokeWidth = w * 0.03f)
            drawLine(cover, Offset(spine, top), Offset(spine, top + bookH), strokeWidth = w * 0.05f)
        }
    }
}

private fun fmtElapsed(sec: Long): String = if (sec < 60) "${sec}с" else "${sec / 60}м ${sec % 60}с"

/** Системный диалог выбора файла, отфильтрованный по Word/PDF (Compose Desktop / AWT). */
private fun pickDocFile(): File? {
    val dialog = FileDialog(null as Frame?, "Выберите документ (Word/PDF)", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name ->
        name.lowercase().let { it.endsWith(".pdf") || it.endsWith(".doc") || it.endsWith(".docx") }
    }
    dialog.file = "*.pdf;*.doc;*.docx"
    dialog.isVisible = true
    val dir = dialog.directory
    val name = dialog.file
    return if (dir != null && name != null) File(dir, name) else null
}
