# Паттерн «Skill + CLI» — переносимый гайд

Как дать LLM-агенту доступ к локальному инструменту **без MCP-сервера**: через один `SKILL.md` (инструкция
по требованию) + локальный CLI, который агент вызывает текстовой строкой. Здесь — суть паттерна, его реализация
в проекте **AdventAI Desktop** (Kotlin/Compose Desktop, «День 20») и рецепт переноса в любой проект, вплоть до
превращения в настоящий **Claude Agent Skill**.

> Один абзац для памяти: MCP кладёт JSON-схемы ВСЕХ инструментов в КАЖДЫЙ запрос к модели. «Skill + CLI» кладёт
> ОДИН markdown-файл ТОЛЬКО когда навык реально нужен, а инструмент вызывается строкой `[CLI] tool …`. Дешевле
> по токенам, без сервера/сети, легко аудировать и безопасно (whitelist + запуск без shell).

---

## 1. Когда это уместно (Skill + CLI vs MCP)

| | **Skill + CLI** | **MCP** |
|---|---|---|
| Схемы в промпте | 1 `SKILL.md` **по требованию** (idle = 0 токенов) | схемы ВСЕХ тулзов в каждом sampling-call |
| Транспорт | локальный процесс (stdout), без сети | сервер (stdio/SSE), часто по сети |
| Данные | **локальные, на чтение** (файлы, БД, конфиги пользователя) | что угодно, в т.ч. удалённое/живое |
| Формат вызова | текст: модель пишет `[CLI] tool …` | нативный tool-call протокола |
| Безопасность | whitelist одного бинаря, **без shell** | зависит от сервера |
| Когда выбирать | локальная операция на чтение, экономия токенов, нет инфраструктуры | живые/сетевые данные, много инструментов, интеграции |

Практическое правило: **локальные пользовательские данные, которые не надо искать в интернете → Skill + CLI;
живые/сетевые/официальные данные → MCP.** В AdventAI они сосуществуют (MCP — актуальные требования со
ссылками; Skill+CLI — «какие документы пользователь уже приложил»).

---

## 2. Архитектура: пять частей

```
        ┌──────────────────────────────────────────────────────────────┐
        │  SkillEngine  (текстовый tool-loop — аналог MCP tool-loop)     │
        │                                                                │
 goal → │  system = SYSTEM_PREFIX + SKILL.md  (грузится ПО ТРЕБОВАНИЮ)    │
        │  loop:                                                         │
        │    ответ LLM ──parse──►  строки  [CLI] tool …                  │
        │       │                     │                                  │
        │       │ нет вызовов         ▼ есть вызовы                       │
        │       ▼                 SkillRunner.run(cmd)                    │
        │   финальный ответ           │ (порт)                           │
        └─────────────────────────────┼──────────────────────────────────┘
                                       ▼
                          CliRunner (data): ProcessBuilder, whitelist,
                          БЕЗ shell, UTF-8 → запускает наш CLI-бинарь
                                       │
                                       ▼  stdout
                          возвращаем модели строкой  [CLI_RESULT] cmd\n<вывод>
```

1. **`SKILL.md`** — короткая инструкция для модели: *когда* применять навык, *как* вызвать, *какие* команды
   есть, *правила*. Грузится в систему только когда навык включён и нужен.
2. **Порт `SkillRunner`** (домен) — интерфейс `suspend fun run(command): String`. Домен не знает про процессы.
3. **`CliRunner`** (data) — реализация порта: запускает CLI отдельным процессом, whitelist, без shell.
4. **CLI-бинарь** — обычная программа с `main()`, печатает результат в **stdout**. Только чтение, без сети/shell.
5. **`SkillEngine`** (домен) — цикл «ответ модели → выполнить `[CLI]` → вернуть `[CLI_RESULT]` → повтор».

Границы (Clean Architecture): `SkillEngine` и `SkillRunner` — в домене (зависят только от портов
`LlmGateway` + `SkillRunner`); запуск процесса/безопасность — в data.

---

## 3. Протокол вызова (текстовый tool-loop)

- Модель, если нужен инструмент, выводит **отдельной строкой**: `[CLI] tool <args>`.
- Движок парсит такие строки, выполняет каждую через `SkillRunner`, возвращает модели:
  `[CLI_RESULT] <cmd>\n<stdout>` + подсказку «дай финальный ответ, новые `[CLI]` — только если данных не хватает».
- Повтор до `maxRounds`; когда модель отвечает без `[CLI]` — это финал. Служебные строки `[CLI]`/`[CLI_RESULT]`
  **вырезаются** из текста, который видит пользователь.
- Токены считаются отдельно (удобно сравнивать стоимость Skill vs MCP на одной задаче).

Системный префикс движка (задаёт контракт модели):

> «Ты — <роль> с доступом к ЛОКАЛЬНОМУ навыку (CLI). Если задача требует локальных данных, ВЫЗОВИ инструмент:
> выведи ОТДЕЛЬНОЙ строкой `[CLI] tool …` и дождись `[CLI_RESULT] …`. Не выдумывай данные — опирайся только на
> результат. Если навык не нужен — просто ответь.»

---

## 4. Как это сделано в AdventAI Desktop (реальные файлы)

| Часть | Файл |
|---|---|
| `SKILL.md` | `src/main/resources/skills/visa-cli.md` |
| Загрузчик по требованию | `data/SkillDocs.kt` (`getResourceAsStream("skills/$name.md")`) |
| Порт | `domain/Ports.kt` → `interface SkillRunner { suspend fun run(command): String }` |
| Раннер CLI | `data/CliSkillRunner.kt` (ProcessBuilder, whitelist `visa-cli`, без shell, UTF-8) |
| CLI-бинарь | `cli/VisaCliMain.kt` (`docs`, `docs check`, `prompt-tune …`, `version`) |
| Движок | `domain/SkillEngine.kt` (парсинг `[CLI]`, цикл, счёт токенов) |
| Fat-jar CLI | `build.gradle.kts` → задача `visaCliJar` (Main-Class `…cli.VisaCliMainKt`) |
| Проводка/демо | `ui/ChatState.kt` (`CliSkillRunner`, `SkillEngine`, `commentOnDocsViaSkill`) |

Движок (сердце паттерна), сокращённо из `SkillEngine.kt`:

```kotlin
suspend fun run(skillDoc: String, history: List<Message>, goal: String): SkillRun {
    val messages = mutableListOf(Message(Role.System, "$SYSTEM_PREFIX\n\n$skillDoc"))
    messages += history.takeLast(8); messages += Message(Role.User, goal)
    repeat(maxRounds) {
        val resp = gateway.complete(messages)
        val commands = parseCli(resp.text)                 // строки [CLI] …
        if (commands.isEmpty()) return SkillRun(clean(resp.text), calls, usage)
        messages += Message(Role.Assistant, resp.text)
        val feedback = buildString {
            for (cmd in commands) { val out = runner.run(cmd); calls += SkillCall(cmd, out)
                append("[CLI_RESULT] ").append(cmd).append('\n').append(out).append("\n\n") }
            append("Теперь дай финальный ответ. Новые [CLI] — только если данных не хватает.")
        }
        messages += Message(Role.User, feedback)
    }
    // лимит раундов → просим финал без вызовов
}
```

Раннер с безопасностью, сокращённо из `CliSkillRunner.kt`:

```kotlin
override suspend fun run(command: String): String = withContext(Dispatchers.IO) {
    val trimmed = command.trim().removeSurrounding("`").trim()
    val rest = when {                                    // WHITELIST: только наш CLI
        trimmed == "visa-cli"          -> ""
        trimmed.startsWith("visa-cli ")-> trimmed.removePrefix("visa-cli ").trim()
        else -> return@withContext "⛔ Отказано: разрешён только visa-cli"
    }
    val args = splitArgs(rest)                            // свой сплиттер с кавычками, БЕЗ shell-семантики
    val cmd = listOf(javaBin, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                     "-cp", System.getProperty("java.class.path"), MAIN_CLASS) + args
    val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()  // напрямую, без /bin/sh
    proc.inputStream.bufferedReader(Charsets.UTF_8).readText().also { proc.waitFor() }
}
```

Формат `SKILL.md` (из `resources/skills/visa-cli.md`) — секции: **# SKILL: имя**, «Когда применять», «Как
вызвать» (`[CLI] tool …`), «Доступные команды», «Правила» (whitelist, «не выдумывай, отвечай по `[CLI_RESULT]`»).

---

## 5. Модель безопасности (важно)

Именно это делает «дать модели shell» — безопасным:

- **Whitelist одного бинаря.** Раннер исполняет ТОЛЬКО `visa-cli`; всё остальное → отказ. Модель не может
  запустить `rm`, `curl`, `bash -c …`.
- **Запуск без shell.** `ProcessBuilder(listOf(...))` напрямую, БЕЗ `/bin/sh -c`. Нет интерполяции, пайпов,
  `&&`, `$()` — «логическую бомбу» в аргументах исполнить нельзя.
- **Свой сплиттер аргументов** (учитывает кавычки), а не shell-парсинг.
- **CLI — только чтение и только локально.** Без сети, без записи опасного, без произвольного FS-доступа.
- **Инъекция контекста, не команд.** Даже если модель «попросит» опасное — раннер это не выполнит.

(В AdventAI сверху ещё `ToolCallGuard` досматривает исходящие вызовы — но безопасность держится на whitelist+без-shell.)

---

## 6. Как переиспользовать в ЛЮБОМ проекте (рецепт)

Замени `visa-cli` на имя своего CLI, `Message/LlmGateway` — на свои типы. Пять шагов:

1. **Напиши CLI** — обычная программа `mytool <cmd> [--opt val]`, печатает результат в stdout. Только нужные
   операции чтения. (Может быть на любом языке: node-скрипт, python, go-бинарь, bash-обёртка над `jq` и т.п.)
2. **Опиши `SKILL.md`** — когда применять, как вызвать (`[CLI] mytool …`), команды, правила. Держи коротким.
3. **Порт** `interface SkillRunner { suspend fun run(cmd): String }`.
4. **Раннер** — `ProcessBuilder` с **whitelist** на `mytool` и **без shell**; верни stdout. (На Node — `execFile`
   с массивом аргументов, НЕ `exec`; на Python — `subprocess.run([...], shell=False)`.)
5. **Движок** — цикл из §3: подать `SYSTEM_PREFIX + SKILL.md`, парсить `[CLI]`, возвращать `[CLI_RESULT]`, повтор.

Псевдокод движка (язык-нейтрально):

```
run(skillDoc, history, goal):
  msgs = [system(SYSTEM_PREFIX + skillDoc), ...history, user(goal)]
  repeat maxRounds:
    resp = llm(msgs)
    cmds = lines_matching(resp, /^\[CLI]\s*(.+)$/)
    if cmds empty: return strip_control_lines(resp)
    msgs += assistant(resp)
    fb = ""
    for c in cmds: fb += "[CLI_RESULT] " + c + "\n" + runner.run(c) + "\n\n"
    msgs += user(fb + "Дай финальный ответ; новые [CLI] — если данных не хватает")
  return llm(msgs + user("Финал без [CLI]"))
```

**Минимальный whitelist-раннер на Node (пример для не-Kotlin проекта):**

```js
import { execFileSync } from "node:child_process";
export async function run(command) {                 // command напр. "mytool docs list"
  const t = command.trim().replace(/^`|`$/g, "").trim();
  if (t !== "mytool" && !t.startsWith("mytool ")) return "⛔ Отказано: разрешён только mytool";
  const args = t.replace(/^mytool\s*/, "").match(/"[^"]*"|\S+/g)?.map(a => a.replace(/"/g,"")) ?? [];
  try { return execFileSync("node", ["mytool.js", ...args], { encoding: "utf8" }); }  // execFile, не exec (без shell)
  catch (e) { return "Ошибка: " + e.message; }
}
```

---

## 7. Как подключить это к Claude (превратить в Agent Skill)

Хорошая новость: ваш «Skill + CLI» — это **самодельная версия того, что у Anthropic называется Agent Skills**.
Тот же принцип: *progressive disclosure* (грузим `SKILL.md` по требованию) + бандл скрипта/CLI. Чтобы навык
подхватывал **Claude Code / Claude**, оформи его как настоящий Skill:

```
.claude/skills/visa-cli/
  ├─ SKILL.md            ← ваш файл + YAML-фронтматтер (см. ниже)
  └─ bin/visa-cli(.jar)  ← бандл вашего CLI (или скрипт)
```

`SKILL.md` для Claude = ваш markdown + **фронтматтер**, по которому Claude решает, когда его подгрузить:

```markdown
---
name: visa-cli
description: Локальные данные заявителя по визе — какие документы приложены и их сверка (ФИО/даты). Использовать, когда вопрос про приложенные файлы пользователя, а не про требования из интернета.
---

# (далее — ваш текст: когда применять, команды, правила)
```

Ключевые отличия «самодельного» движка от Claude-скилла:

- В своём приложении **вы** пишете движок (`SkillEngine`) и раннер — потому что там своя LLM (DeepSeek/OpenRouter)
  и своя песочница. Контракт вызова — ваш (`[CLI]`/`[CLI_RESULT]`).
- В **Claude Code** движок уже встроен: Claude сам читает `SKILL.md` по `description`, а CLI вызывает через свой
  инструмент Bash (там свой sandbox/allowlist). Вам нужно лишь: (1) фронтматтер с точным `description`
  (по нему срабатывает автоподгрузка), (2) чёткие команды, (3) бандл бинаря и путь до него в инструкции.
- `description` — самое важное: это единственное, что Claude видит «всегда», и по нему решает загрузить навык.
  Пишите его как «**что делает + КОГДА применять**», конкретно.

Итог: один и тот же `SKILL.md` (тело) переиспользуется и вашим движком, и Claude — меняется только обёртка
(фронтматтер для Claude / `SYSTEM_PREFIX` для своего движка).

---

## 8. Грабли и уроки (из реализации)

- **UTF-8 у дочернего процесса.** Прокидывай `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8`, читай stdout как
  UTF-8 — иначе кириллица превращается в кракозябры и попадает в LLM.
- **Шум в stdout ломает ответ.** Заглуши логи библиотек (в AdventAI — PDFBox/commons-logging через `NoOpLog`),
  иначе WARNING'и уедут в модель как «данные».
- **`redirectErrorStream(true)`** — чтобы поймать и stderr (сообщения об ошибках) единым текстом для модели.
- **Класспас для запуска в dev.** Мы запускаем CLI на текущем `java.class.path` тем же JVM — не нужен отдельный
  бинарь при разработке; для дистрибуции есть fat-jar (`visaCliJar`).
- **Считай токены отдельно** — так наглядно видно экономию Skill vs MCP на одинаковой задаче.
- **Парси устойчиво.** `[CLI]` может прийти в ```-обёртке — снимай бэктики; служебные строки вырезай из
  финального текста для пользователя.
- **Роль движка — не только «выполнить».** Между раундами подсказывай модели «дай финал, новые вызовы — только
  если не хватает данных», иначе она зацикливается на вызовах.

---

## 9. Чеклист переноса

- [ ] CLI печатает результат в **stdout**, только чтение, без сети/shell.
- [ ] `SKILL.md`: когда применять · как вызвать (`[CLI] tool …`) · команды · правила («не выдумывай»).
- [ ] Порт `SkillRunner` (домен) + раннер (data) с **whitelist** и **без shell**.
- [ ] Движок: `SYSTEM_PREFIX + SKILL.md`, парсинг `[CLI]`, возврат `[CLI_RESULT]`, лимит раундов, счёт токенов.
- [ ] UTF-8 на дочернем процессе; заглушены логи библиотек; `redirectErrorStream(true)`.
- [ ] (Для Claude) `SKILL.md` с фронтматтером `name` + точный `description` (что + когда), бандл CLI в `.claude/skills/<name>/`.

---

*Источник паттерна: проект AdventAI Desktop, «День 20 — Skill + CLI как альтернатива MCP». Файлы:
`domain/SkillEngine.kt`, `domain/Ports.kt` (`SkillRunner`), `data/CliSkillRunner.kt`, `data/SkillDocs.kt`,
`cli/VisaCliMain.kt`, `resources/skills/visa-cli.md`, задача `visaCliJar` в `build.gradle.kts`.*
