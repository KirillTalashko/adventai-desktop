# Приватный LLM-сервис (День 30)

HTTP-фасад вокруг локальной LLM (`LocalLlmClient` → Ollama): отдаёт чат по сети с токен-авторизацией и
базовыми ограничениями. Разворачивается на VPS/домашнем сервере тем же паттерном, что `VisaMcpServer`
(Ktor CIO + bearer-токен + бинд на `127.0.0.1` за reverse-proxy).

Код — `src/main/kotlin/com/example/adventdesktop/service/LocalLlmService.kt`.

## Эндпойнты

| Метод | Путь | Авторизация | Назначение |
|---|---|---|---|
| `GET` | `/health` | нет | liveness (для reverse-proxy/мониторинга): `{"status":"ok","model":"…"}` |
| `POST` | `/chat` | Bearer | чат: тело `{"prompt":"…"}` **или** `{"messages":[{"role","content"}], "temperature"?, "maxTokens"?}` |

Ответ `/chat`: `{"reply","model","promptTokens","completionTokens","totalTokens","ms"}`.

```bash
curl -s http://127.0.0.1:3002/health
curl -s -X POST http://127.0.0.1:3002/chat \
  -H "Authorization: Bearer <TOKEN>" -H "Content-Type: application/json" \
  -d '{"prompt":"Что такое шенгенская виза? Одним предложением."}'
```

## Настройка (только через env — секретов в коде нет)

| Переменная | Дефолт | Смысл |
|---|---|---|
| `LLM_PORT` | `3002` | порт сервиса |
| `LLM_HOST` | `127.0.0.1` | бинд; loopback за reverse-proxy (defense-in-depth). `0.0.0.0` — только осознанно |
| `LLM_AUTH_TOKEN` | — | bearer-токен. **Не задан → `/chat` открыт** (только для локальной отладки) |
| `LLM_MODEL` | `qwen2.5:7b` | модель Ollama (на слабом VPS — меньше, напр. `qwen2.5:3b`/`llama3.2:3b`) |
| `OLLAMA_URL` | `http://localhost:11434` | адрес Ollama |
| `LLM_RATE_PER_MIN` | `20` | rate limit, запросов/мин на токен → `429` |
| `LLM_MAX_CONTEXT_CHARS` | `8000` | предел длины запроса (символов) → `413` |
| `LLM_MAX_INFLIGHT` | `2` | максимум одновременных генераций → `503` |

## Базовые ограничения (ДЗ дня)

- **Rate limit** — скользящее окно 60 c на токен; сверх лимита → `429`.
- **Max context** — суммарная длина сообщений > `LLM_MAX_CONTEXT_CHARS` → `413`.
- **Параллелизм** — CPU-модель тянет мало запросов разом; сверх `LLM_MAX_INFLIGHT` → `503` (не копим очередь, отбиваем сразу).

## Локальный запуск и проверка

```powershell
# 1. Поднять сервис (нужна запущенная Ollama + ollama pull qwen2.5:7b):
$env:LLM_AUTH_TOKEN="<TOKEN>"; $env:LLM_RATE_PER_MIN="4"; $env:LLM_MAX_INFLIGHT="2"
.\gradlew.bat runLocalLlmService

# 2. В другом окне — клиент-проверка (сеть, чат, лимиты, параллелизм):
$env:LLM_SERVICE_URL="http://127.0.0.1:3002"; $env:LLM_AUTH_TOKEN="<TOKEN>"
.\gradlew.bat runLlmServiceDemo
```

Проверено на `qwen2.5:7b`: `/health` → ok; `/chat` → валидный ответ с токенами и задержкой; без токена → `401`;
длинный контекст → `413`; всплеск 6 параллельных при `rate=4, inflight=2` → **2×200 · 2×429 · 2×503**.

## Деплой на VPS / домашний сервер

Тот же подход, что и MCP-сервер (systemd-сервис + reverse-proxy + токен, бинд на loopback). **Реальные
домен/IP/токен/пути в репозиторий не коммитятся** — ниже только шаблоны с плейсхолдерами.

```bash
# 0. Предпосылки на сервере: Ollama запущена и модель скачана.
ollama serve &                      # или как systemd-сервис
ollama pull qwen2.5:7b              # на слабом VPS — модель поменьше

# 1. Собрать fat-jar и скопировать на сервер:
./gradlew llmServiceJar            # → build/libs/visa-llm-service-all.jar
scp build/libs/visa-llm-service-all.jar <user>@<host>:/opt/visa-llm/

# 2. Токен:
openssl rand -hex 32               # положить в env-файл сервиса, НЕ в git
```

**systemd-юнит** (`/etc/systemd/system/visa-llm.service`, плейсхолдеры замените):

```ini
[Unit]
Description=Private local LLM service
After=network.target ollama.service

[Service]
User=<user>
WorkingDirectory=/opt/visa-llm
Environment=LLM_HOST=127.0.0.1
Environment=LLM_PORT=3002
Environment=LLM_MODEL=qwen2.5:7b
Environment=LLM_AUTH_TOKEN=<TOKEN>
Environment=LLM_RATE_PER_MIN=30
Environment=LLM_MAX_INFLIGHT=2
ExecStart=/usr/bin/java -jar /opt/visa-llm/visa-llm-service-all.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

**Reverse-proxy (Caddy)** — HTTPS + маршрут на loopback-порт:

```
llm.<your-domain> {
    reverse_proxy 127.0.0.1:3002
}
```

Дальше: `systemctl enable --now visa-llm`, проверить `https://llm.<your-domain>/health`. Наружу открыт только
`443` (reverse-proxy); сам сервис слушает `127.0.0.1` — недоступен напрямую даже при сбое firewall.

## Заметки

- Один инстанс Ollama сериализует генерацию; кап `LLM_MAX_INFLIGHT` не даёт очереди расти и держит сервис отзывчивым.
- На слабом (CPU) VPS 7B-модель отвечает медленно — берите модель поменьше или домашний сервер с GPU.
- Приватность сохраняется: данные не покидают ваш сервер, наружу — только авторизованный HTTPS-эндпойнт.
