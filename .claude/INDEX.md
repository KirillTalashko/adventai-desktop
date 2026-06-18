# База знаний `.claude` — индекс

Документация дробится по темам: одна тема = один `.md`. Добавляя материал — клади в подходящий файл
или заводи новый тематический.

| Файл | Тема |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | архитектура агента: слои, карта файлов, поток данных, сборка, грабли |
| [CONTEXT_WINDOW.md](CONTEXT_WINDOW.md) | контекстное окно, стратегии контекста, токены |
| [MEMORY_MODEL.md](MEMORY_MODEL.md) | модель памяти (3 слоя), хранение, маршрутизация |
| [MEMORY_AGENT.md](MEMORY_AGENT.md) | фоновый агент авто-наполнения памяти (deepseek-chat) |
| [PROMPTING.md](PROMPTING.md) | промпт-инжиниринг, системный промпт, формат `[checklist]` |
| [STATE_MACHINE.md](STATE_MACHINE.md) | состояние задачи и инварианты (роадмап) |
| [ANTIPATTERNS.md](ANTIPATTERNS.md) | чего не делать |

Точка входа проекта — `../CLAUDE.md`. Мета-промпт по дизайну — `../DESIGN_BRIEF.md`.
