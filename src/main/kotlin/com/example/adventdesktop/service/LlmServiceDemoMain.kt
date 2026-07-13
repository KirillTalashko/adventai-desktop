package com.example.adventdesktop.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * День 30 — клиент-проверка приватного LLM-сервиса ([LocalLlmService]): доступ по сети, чат, базовые
 * ограничения (max context → 413, авторизация → 401) и стабильность под нагрузкой (параллельные запросы →
 * часть 200, часть 429 rate-limit / 503 busy).
 *
 * Запуск: `LLM_SERVICE_URL=http://127.0.0.1:3002 LLM_AUTH_TOKEN=<token> .\gradlew.bat runLlmServiceDemo`.
 * (Сервис поднять маленькими лимитами, чтобы наглядно сработали rate/parallelism, напр. LLM_RATE_PER_MIN=4 LLM_MAX_INFLIGHT=2.)
 */
fun main() = runBlocking {
    val base = System.getenv("LLM_SERVICE_URL")?.trim()?.ifBlank { null } ?: "http://127.0.0.1:3002"
    val token = System.getenv("LLM_AUTH_TOKEN")?.trim()?.ifBlank { null }
    val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 300_000; connectTimeoutMillis = 5_000 } }
    fun HttpRequestBuilder.auth() { if (token != null) header(HttpHeaders.Authorization, "Bearer $token") }
    suspend fun chat(body: String, withAuth: Boolean = true): HttpResponse =
        http.post("$base/chat") { if (withAuth) auth(); contentType(ContentType.Application.Json); setBody(body) }

    println("Сервис: $base · токен: ${if (token != null) "есть" else "нет"}\n")

    println("1) Доступ по сети — GET /health")
    val h = http.get("$base/health")
    println("   ${h.status} · ${h.bodyAsText()}\n")

    println("2) Чат — POST /chat")
    val c = chat("""{"prompt":"Одним предложением: что такое шенгенская виза?"}""")
    println("   ${c.status} · ${c.bodyAsText().take(280)}\n")

    println("3) Лимит контекста — очень длинный промпт → ожидаем 413")
    val big = chat("""{"prompt":"${"проверка ".repeat(3000)}"}""")
    println("   ${big.status} · ${big.bodyAsText().take(160)}\n")

    if (token != null) {
        println("4) Авторизация — /chat без токена → ожидаем 401")
        println("   ${chat("""{"prompt":"привет"}""", withAuth = false).status}\n")
    }

    println("5) Стабильность + лимиты — 6 параллельных запросов")
    val statuses = (1..6).map { i ->
        async { chat("""{"prompt":"Назови документ №$i на визу, одной строкой.","maxTokens":48}""").status }
    }.awaitAll()
    statuses.forEachIndexed { i, s -> println("   req${i + 1} → $s") }
    val tally = statuses.groupingBy { it.value }.eachCount()
    println("   Итог: $tally  (200 — ответ, 429 — rate limit, 503 — занят)\n")

    http.close()
}
