package com.example.adventdesktop.mcp.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Фоновый планировщик дайджеста (День 18). Периодически (раз в [intervalMinutes]) обходит сохранённые
 * страны и для каждой собирает свежую визовую сводку через [collect] (внутри — `VisaResearchAgent`),
 * складывая снимок в [store]. Это и есть «агент 24/7»: на VPS живёт в systemd-сервисе и копит историю.
 *
 * Изоляция отказов: ошибка по одной стране ловится и не валит остальной обход; пустой результат сбора
 * (поиск недоступен) просто пропускается. Прогоны не накладываются — следующий стартует через [delay]
 * ПОСЛЕ полного обхода. Первый обход — сразу при [start] (чтобы данные появились без ожидания).
 *
 * @param collect собирает сводку по стране → (summary, sources) или null, если собрать не удалось.
 */
class DigestScheduler(
    private val store: DigestStore,
    private val intervalMinutes: Long,
    private val collect: suspend (DigestCountry) -> Pair<String, String>?,
) {
    /** Запустить фоновый цикл в переданном scope. Остановка — отменой возвращённого [Job]/scope. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            runCatching { collectAll() }
            delay(intervalMinutes.coerceAtLeast(1) * 60_000L)
        }
    }

    /** Один полный обход всех подписок: собрать и сохранить снимок по каждой стране. */
    suspend fun collectAll() {
        for (country in store.listCountries()) {
            runCatching { collectOne(country) }
        }
    }

    /** Собрать и сохранить снимок по одной стране (используется и тулом add_digest_country для первого сбора). */
    suspend fun collectOne(country: DigestCountry) {
        val result = collect(country) ?: return
        store.saveSnapshot(country.id, result.first, result.second)
    }
}
