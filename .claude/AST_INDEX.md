# ast-index — структурный поиск по коду

Локальный CLI (`github.com/defendend/Claude-ast-index-search`, Rust + tree-sitter), строит
SQLite-индекс символов/ссылок/зависимостей. **Для агента — основной инструмент навигации по коду:**
точнее и дешевле по токенам, чем читать файлы целиком или сплошной grep.

## Когда использовать

- Найти класс/функцию/символ и его `file:line` → `ast-index symbol|class <Name>`.
- Найти все использования/ссылки → `ast-index usages|refs <Name>`.
- Понять иерархию/реализации → `ast-index hierarchy|implementations <Name>`.
- Обзор файла или проекта → `ast-index outline <file>`, `ast-index map`, `ast-index conventions`.
- Сначала `ast-index`, и только для чтения конкретного куска — `Read`/`Grep` по найденному `file:line`.

## Частые команды (запускать из корня проекта)

```
ast-index search <q>            # универсальный поиск (файлы + символы)
ast-index symbol <Name>         # классы/интерфейсы/функции
ast-index class <Name>          # класс/интерфейс
ast-index usages <Name>         # где используется символ
ast-index refs <Name>           # определения + импорты + использования
ast-index hierarchy <Class>     # иерархия классов
ast-index implementations <I>   # реализации интерфейса
ast-index outline <file>        # символы в файле
ast-index callers <fn>          # кто вызывает функцию
ast-index todo                  # TODO/FIXME/HACK
ast-index map                   # карта проекта (ключевые типы по каталогам)
ast-index conventions           # архитектура/фреймворки/нейминг
ast-index --format json <cmd>   # машинно-читаемый вывод
```

## Обслуживание индекса

- **Авто:** хук `PostToolUse` (`Edit|Write|MultiEdit`) и `SessionStart` в `.claude/settings.json`
  гоняют `ast-index update` — индекс держится свежим без ручных действий.
- **Вручную:** `ast-index rebuild` (полный), `ast-index update` (инкрементальный), `ast-index stats`.
- БД лежит вне репозитория (`%LOCALAPPDATA%\ast-index\<hash>\index.db`) — в git не коммитится.

## Установка (если на новой машине нет CLI)

`winget install --id defendend.ast-index` (Windows) · `brew install defendend/ast-index/ast-index`
(mac/Linux). После — `ast-index rebuild` в корне проекта один раз.
