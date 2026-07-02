# 05 — План: REFINE UI «Визовый специалист» (Compose Desktop)

Источник: аудит Рамса 23/30 (`02-scorecard.md`, `03-verdict.md`). Проход **REFINE** — точечно, без структурного редизайна.
Ветка: `ui-refine-rams`. Перед финишем: `.\gradlew.bat compileKotlin --offline` зелёный. По-русски.

---

## Phase 0 — Discovery (выполнено, факты-опора)

**Существующие паттерны для КОПИРОВАНИЯ (не изобретать):**
- Конфиг-флаг = три синхронных места: `DesktopConfig` (`data/ConfigStore.kt:14`) + `AppConfigDto` (`data/Dto.kt:110`) + маппинг в `load()` (`ConfigStore.kt:43`) и `save()` (`ConfigStore.kt:60`). Копировать строку `mcpEnabled` для каждого нового булева поля.
- Тумблер-сеттер: `ChatState.fun setMcpEnabled` (`ui/ChatState.kt:730`) — `config.copy(...)` + `configStore.save(config)`. Для чисто-UI-флагов (devMode/theme/motion) `rebuildAgent()` НЕ вызывать (в отличие от MCP-флагов).
- UI-тумблер строкой: `ConnectorToggleRow` (`ui/App.kt:472`) — `Switch(checkedTrackColor=AppColors.accent)`.
- Тема: `AdventTheme` + `lightColorScheme(...)` (`ui/Theme.kt:9,46`). Material3 даёт `darkColorScheme(...)`.
- Иконки: `Icon(Icons.Filled.X, contentDescription, Modifier.size, tint)` — везде в `App.kt` (напр. `:162,234,429`). Бриф требует **Outlined** (`Icons.Outlined.*` из `androidx.compose.material.icons.outlined.*`).
- Чип-кнопка как образец подписи у иконки: `DropdownChip` (`App.kt:814`) — иконка+текст в `Surface`.

**Anti-patterns (проверить, что НЕ делаем):**
- `isSystemInDarkTheme()` на desktop ненадёжен/может отсутствовать — НЕ полагаться; тему переключаем явным флагом конфига.
- Не плодить новый «дизайн-фреймворк»: токены — один маленький `object`, не библиотека.
- Не трогать домен/раскладку/палитру акцента `#2F6BED`.

**Allowed APIs:** `RoundedCornerShape(Dp)`, `Arrangement.spacedBy(Dp)`, `Modifier.padding(Dp)`, `Icons.Outlined.*`, `androidx.compose.material3.darkColorScheme`, `TooltipBox`/`PlainTooltip` (Material3) ИЛИ видимый текст-ярлык, `LazyListState.scrollToItem` (без анимации) vs `animateScrollToItem`.

---

## Phase 1 — Единый источник токенов (#3 Aesthetic)

**Что сделать (создать новый файл, скопировать значения брифа):**
- Новый `ui/Dimens.kt`:
  ```kotlin
  object Radii { val sm = 10.dp; val md = 12.dp; val lg = 16.dp; val pill = 16.dp; val xl = 20.dp /* только онбординг-карта */ }
  object Space { val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 24.dp }
  ```
- Заменить литералы на токены в точках дрейфа: `RoundedCornerShape(6.dp)`→`Radii.sm`-семейство по смыслу: мелкие кнопки-боксы `App.kt:189` (6→ оставить как `Radii.sm`? нет: ввести `Radii.xs=6.dp` ТОЛЬКО если реально нужен; иначе округлить до 10), логотип `App.kt:143` (9→10), чипы/айтемы 10 → `Radii.sm`, карточки/пилюли 12/16 → `Radii.md/lg`.
- Spacing: заменить разнобой `padding(...)`/`spacedBy(...)` на `Space.*` там, где значение ∈ {4,8,12,16,24}; нестандартные (7,9,11,14,18) подтянуть к ближайшему токену, ЕСЛИ это не ломает выравнивание визуально (проверить запуском).

**Цель-набор радиусов:** {10,12,16} (+20 только онбординг). Было: 6,8,9,10,12,16,20.

**Verification:**
- `rg -n "RoundedCornerShape\((\d+)\.dp\)" src/main/kotlin/com/example/adventdesktop/ui` → остаются только обращения через `Radii.*` или значения из целевого набора.
- `compileKotlin --offline` зелёный.

**Anti-pattern guard:** не вводить десяток токенов «про запас» — только те, что реально используются; не менять смысл отступов на глаз без запуска.

---

## Phase 2 — Эмодзи-иконки → Material Outlined (#7, #3)

**Что сделать (заменить эмодзи в UI-строках на `Icon(Icons.Outlined.*)`):**
- `🟢` статус подключения (`App.kt:613,625`) → `Icons.Outlined.CheckCircle` tint=AppColors.accent.
- `⚠️` предупреждение DNS (`App.kt:619`) → `Icons.Outlined.WarningAmber` tint=error.
- `💡` предложение промта (`App.kt:556`) → `Icons.Outlined.Lightbulb`.
- `🤖`/`▶` кнопки пайплайна (`App.kt:747,750`) → `Icons.Outlined.SmartToy` / `Icons.Outlined.PlayArrow` (или просто текст без эмодзи).
- Стрелки «▼▲» в `ExpandableText` (`App.kt:805`) → `Icons.Outlined.KeyboardArrowDown/Up`.
- Проверить «✦»/«✓» (бриф/`App.kt:241`): галочку активного аккаунта оставить текстовой или `Icons.Outlined.Check`.

**Verification:**
- `rg -n "🟢|⚠️|💡|🤖|▶|▲|▼" src/main/kotlin/com/example/adventdesktop/ui` → пусто (кроме, возможно, не-UI комментариев).
- Сборка зелёная; иконки рендерятся (запуск).

**Anti-pattern guard:** не заменять эмодзи в `.claude/`-доках и комментариях кода — только пользовательские строки UI.

---

## Phase 3 — Понятность контролов (#4 Understandable)

**Что сделать:**
- Композерные иконки-кнопки снабдить подсказкой/ярлыком:
  - `McpButton` (`App.kt:437`) и `ConnectorsButton` (`App.kt:457`) обернуть в `TooltipBox{ PlainTooltip{ Text("Инструменты MCP") } }` / `Text("Коннекторы агента")`, ЛИБО (проще и нагляднее) добавить крошечную подпись под рядом. Скопировать формат иконка+текст из `DropdownChip` (`App.kt:814`).
- Переименовать пользовательский ярлык «Инварианты»→«Правила»: `SidebarButton("Инварианты"...)` (`App.kt:198`) и заголовок окна/тексты в `InvariantsDialog` (`Dialogs.kt:241,257`). Внутренние идентификаторы/доменные имена НЕ трогать — только видимый текст.

**Verification:**
- `rg -n "Инварианты" src/main/kotlin/.../ui` → не встречается в пользовательских строках (Text(...)); domain-код без изменений.
- Тултипы видны при наведении (запуск).

**Anti-pattern guard:** не переименовывать доменные классы (`Invariant`, `InvariantGuard`) — только UI-надписи.

---

## Phase 4 — Режим разработчика прячет dev-витрины (#4, #10)

**Что сделать (скопировать паттерн флага `mcpEnabled`):**
- Добавить `developerMode: Boolean = false` в `DesktopConfig` (`ConfigStore.kt:14`), `AppConfigDto` (`Dto.kt:110`), и в маппинг `load()`/`save()` (`ConfigStore.kt:43,60`) — построчно по образцу `mcpEnabled`.
- В `ChatState` добавить `fun setDeveloperMode(v: Boolean)` по образцу `setMcpEnabled` (`ChatState.kt:730`), но БЕЗ `rebuildAgent()`.
- В `SettingsDialog` (`Dialogs.kt:56`) добавить `ConnectorToggleRow("Режим разработчика", "показывать инструменты MCP/коннекторы и демо", state.config.developerMode){ state.setDeveloperMode(it) }`.
- Скрыть за флагом на ОСНОВНОМ экране: `McpButton(state)` и `ConnectorsButton(state)` в `Composer` (`App.kt:394,395`) — оборачивать в `if (state.config.developerMode) { ... }`.
- Dev-демо-СЕКЦИИ внутри диалогов оставить доступными, но точку входа (кнопки) скрыть; либо целиком гейтить открытие. Минимально-инвазивно: скрыть кнопки-входы в композере (выше) — этого достаточно, чтобы убрать витрины с глаз обычного пользователя.

**Verification:**
- С `developerMode=false` (дефолт) в композере нет иконок MCP/коннекторы; с `true` — появляются.
- `compileKotlin --offline` зелёный; `config.json` сохраняет/читает новое поле.

**Anti-pattern guard:** не удалять функциональность MCP/коннекторов — только прятать вход за флаг (обратимо).

---

## Phase 5 — Тёмная тема + reduced-motion (#9)

**Что сделать:**
- В `Theme.kt` добавить `private val DarkColors = darkColorScheme(...)` (зеркало `LightColors`: фон тёмный, surface тёмный, accent тот же `#2F6BED`, текст светлый). `AdventTheme` принимает `dark: Boolean` и выбирает схему. `AppColors.sidebar`/`StatusColors` — сделать функциями от схемы ИЛИ задать тёмные варианты (минимум: оставить accent, затемнить sidebar).
- Добавить флаги `darkTheme: Boolean=false`, `reducedMotion: Boolean=false` (те же три места конфига + сеттеры в `ChatState`, как Phase 4).
- В `App(state)` (`App.kt:94`) `AdventTheme(dark = state.config.darkTheme){ ... }`; в `Onboarding`/диалогах, где `AdventTheme{}` вызывается отдельно (`Dialogs.kt:130,163,247,308`; `Onboarding`), прокинуть тот же флаг.
- Reduced-motion: в `ChatPane` авто-скролл (`App.kt:311`) — `if (config.reducedMotion) listState.scrollToItem(...) else listState.animateScrollToItem(...)`. Аналогично `InterviewDialog` scroll (`Dialogs.kt:311`).
- В `SettingsDialog` — тумблеры «Тёмная тема» и «Меньше анимаций».

**Verification:**
- Переключение тёмной темы перекрашивает всё приложение и диалоги; accent остаётся `#2F6BED`.
- С `reducedMotion=true` лента прыгает без анимации.
- Сборка зелёная.

**Anti-pattern guard:** не полагаться на `isSystemInDarkTheme()`; не вводить третий «системный» режим в этом проходе (скоуп-крип).

---

## Phase 6 — Финальная верификация + регрессии Keep

**Что сделать:**
- `.\gradlew.bat compileKotlin --offline` — зелёный.
- (Опц.) `.\gradlew.bat run` — глазами проверить экраны.
- **Регрессия-чек Keep (не должны деградировать):**
  - #2 Useful: пустое состояние + чипы на месте, Enter отправляет (`App.kt:341,376`).
  - #5 Unobtrusive: хром тихий, без новых тяжёлых рамок (`App.kt:291`).
  - #6 Honest: предупреждение удаления, маскирование ключей, честные баннеры целы (`App.kt:261,619`; `Dialogs.kt:67`).
  - #10: основной чат без новых аффордансов (dev-вход скрыт, а не добавлен).
- Временные verify-харнессы (если заводились в `src/.../tools/`) — прогнать и УДАЛИТЬ.
- Коммит(ы) с описательным сообщением; ветка `ui-refine-rams`.

**Итог-критерий:** повторный мысленный прогон скорборда — #3,#4,#7,#9 поднимаются; #2,#5,#6,#10 не падают; целевой диапазон 27–29/30.
