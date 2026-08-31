package com.example.adventdesktop.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Как [runCatching], но корректно разделяет **нашу** отмену и **чужую**.
 *
 * Голый `runCatching` в suspend-коде глотает [CancellationException] — структурная отмена «поглощается»,
 * и родительский scope не завершается. Наивное «всегда пробрасывать» тоже неверно: библиотеки бросают
 * *свои* [CancellationException], не отменяя нас (например, таймаут запроса MCP Kotlin SDK —
 * `TimeoutCancellationException` из его внутреннего `withTimeout`). Пробросив такую, мы обрушим весь
 * ход агента вместо штатной деградации.
 *
 * Поэтому в `catch` спрашиваем **контекст**, а не тип исключения:
 * - наш scope отменён → [ensureActive] бросает → отмена уходит наверх (structured concurrency);
 * - наш scope жив → отмена пришла изнутри чужого scope → это обычный сбой → [Result.failure].
 *
 * ```
 * val r = runCatchingCancellable { gw.callToolJson("get_visa_requirements", args) }
 *     .getOrNull()          // таймаут SDK → null → деградируем; отмена хода → пробрасывается
 * ```
 */
suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()   // отменили НАС → пробросить; иначе — чужая отмена
        Result.failure(e)
    } catch (e: Throwable) {
        Result.failure(e)
    }
