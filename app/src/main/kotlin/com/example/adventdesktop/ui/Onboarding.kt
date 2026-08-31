package com.example.adventdesktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.adventdesktop.domain.Account
import com.example.adventdesktop.domain.UserProfile
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Онбординг / экран входа (Day 12). Без активного аккаунта, но с сохранёнными — показываем выбор
 * (вход обратно после «Выйти»); иначе — форму создания профиля.
 */
@Composable
fun Onboarding(state: ChatState) {
    // Можно выбрать существующий аккаунт, только если ни один не активен, но они есть (после выхода).
    val canChoose = state.activeAccount == null && state.accountList.isNotEmpty()
    var creating by remember(canChoose) { mutableStateOf(!canChoose) }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(Radii.xl),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = Elevations.card,
            modifier = Modifier.widthIn(max = Sizes.onboardingMaxWidth).fillMaxHeight(0.94f)
        ) {
            Column(
                Modifier.padding(32.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                if (creating) {
                    CreateProfile(
                        state,
                        onCancel = when {
                            canChoose -> ({ creating = false })             // назад к выбору аккаунта
                            state.activeAccount != null -> ({ state.cancelOnboarding() }) // назад в чат
                            else -> null                                     // первый запуск — отменять некуда
                        }
                    )
                } else {
                    ChooseAccount(state, onCreateNew = { creating = true })
                }
            }
        }
    }
}

@Composable
private fun CreateProfile(state: ChatState, onCancel: (() -> Unit)?) {
    // §5.9: ЕДИНСТВЕННОЕ место в интерфейсе, где показывается название продукта.
    Text(
        "AdventAI · Визовый специалист",
        style = MaterialTheme.typography.labelMedium,
        color = AppColors.accent
    )
    Text(
        if (state.accountList.isEmpty()) "Добро пожаловать" else "Новый аккаунт",
        style = MaterialTheme.typography.displaySmall,
        color = AppColors.ink
    )
    Text(
        "Создайте профиль — визовый специалист подстроится под ваш стиль, формат ответов и ограничения. Профиль можно изменить в любой момент.",
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.4.sp),
        color = AppColors.muted
    )
    ProfileForm(
        initial = UserProfile(),
        submitLabel = "Создать профиль",
        onSubmit = { state.completeOnboarding(it) },
        onCancel = onCancel
    )
}

@Composable
private fun ChooseAccount(state: ChatState, onCreateNew: () -> Unit) {
    Text(
        "AdventAI · Визовый специалист",
        style = MaterialTheme.typography.labelMedium,
        color = AppColors.accent
    )
    Text("С возвращением", style = MaterialTheme.typography.displaySmall, color = AppColors.ink)
    Text(
        "Выберите аккаунт, чтобы продолжить, или создайте новый.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    state.accountList.forEach { acc -> AccountRow(acc) { state.switchAccount(acc.id) } }
    Surface(
        onClick = onCreateNew,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radii.md),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "+ Создать новый профиль",
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AccountRow(account: Account, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radii.md),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(AppColors.accent, CircleShape), contentAlignment = Alignment.Center) {
                Text(
                    (account.name.trim().firstOrNull() ?: 'П').uppercaseChar().toString(),
                    color = MaterialTheme.colorScheme.onSecondary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                account.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium
            )
        }
    }
}
