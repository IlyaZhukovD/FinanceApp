# Finance

Десктоп-приложение для личного учёта финансов: расходы, доходы и активы
с гранулярностью «месяц + год», аналитика и графики, бэкап в папку Яндекс.Диска
и AI-разбор показателей через OpenRouter.

Офлайн, без сервера и без логина. macOS и Windows. Только светлая тема.

- Полное ТЗ: [`docs/SPEC.md`](docs/SPEC.md)
- Принятые решения и их причины: [`docs/DECISIONS.md`](docs/DECISIONS.md)

## Статус

| Этап | Содержание | Состояние |
|---|---|---|
| M1 | Каркас сборки, `core:model` + тесты, схема БД, окно приложения | **готов** |
| M2 | Раздел «Расходы» | **готов**: экран с навигацией по месяцам, вводом сумм, комментариями, историей правок и дублированием периода |
| M3 | Раздел «Доходы» | **готов**: та же `LedgerScreen` с `LedgerSection.INCOME`, копипасты UI нет (F2.2) |
| M4 | Раздел «Активы» | **готов частично**: счета, остатки за месяц, Net Worth с учётом кредитов, закрытие счёта; отдельный модуль `feature:assets` (F4) |
| M5–M9 | Аналитика, бэкап, импорт, AI, упаковка | не начаты |

Проверено: `./gradlew cleanTest build --no-build-cache` — 175 тестов, 0 падений
(`core:model` 21, `core:domain` 83, `core:data` 31, `core:ui` 11, `feature:ledger` 17,
`feature:assets` 11, `app:desktop` 1);
`./gradlew :app:desktop:run` открывает окно и раздел «Расходы» за текущий месяц.
`AppGraphTest` проверяет композиционный корень на реальной файловой SQLite: база
создаётся, категории сеются, доходы видят 5 своих категорий и не смешиваются с расходами,
контроллер активов получает 6 категорий активов и пустой список счетов.

В M2 сознательно отложены (касается обоих разделов потока): Undo по ⌘/Ctrl+Z (F1.8),
сортировка таблицы (F1.2), поиск и «недавние» категории (F1.4), запоминание последнего
открытого периода (F1.7). В M4 отложены: история счёта со спарклайном и прогноз по ставке
(F4.4), дублирование месяца для активов (F5.3), редактирование счёта, курсы валют.

Дорожная карта целиком — в `docs/SPEC.md`, раздел 12.

## Стек

Kotlin 2.4.20 · Compose Multiplatform 1.12.0 · SQLite через SQLDelight 2.3.2 · JVM target 21.

Локально данные лежат в SQLite (один файл), а не в PostgreSQL: причины — в
`docs/DECISIONS.md`, D-01. PostgreSQL остаётся целевой БД будущей серверной части.

В `app:desktop` подключён `kotlinx-coroutines-swing`: `Dispatchers.Main` на десктопе —
это Swing EDT, и без этого артефакта окно падает в рантайме с «Module with the Main
dispatcher is missing», хотя компиляция и все тесты проходят. Найдено дымовым прогоном.

## Требования к окружению

Проверено на машине разработки (macOS 26.6.2, arm64):

- **JDK 21** — `/opt/homebrew/opt/openjdk@21` (21.0.9). Системного `java` в PATH нет,
  поэтому в командах ниже `JAVA_HOME` задаётся явно.
- **Gradle 9.7.1** — скачан в `.tools/gradle/` (в систему не устанавливался, каталог в
  `.gitignore`). Контрольная сумма совпала с официальной
  `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`.
- **Gradle wrapper** — сгенерирован (`gradlew`, `gradle/wrapper/`), дистрибутив скачан в
  кэш `~/.gradle`. Дальше используется `./gradlew`.

Для сборки установщиков дополнительно: Xcode Command Line Tools (`.dmg`, только macOS),
WiX Toolset (`.msi`, только Windows).

## Команды

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

./gradlew :core:model:test     # тесты модуля моделей
./gradlew test                 # все тесты
./gradlew build                # полная сборка
./gradlew :app:desktop:run     # запуск окна приложения
./gradlew :app:desktop:packageDmg   # установщик macOS — только на macOS
./gradlew :app:desktop:packageMsi   # установщик Windows — только на Windows, нужен WiX
```

CI намеренно не используется: сборка установщиков выполняется локально на каждой ОС
(`docs/DECISIONS.md`, D-08).

## Структура

```
core/model        Money, Period, секции, Category, ячейка category_month, ревизии, активы
core/domain       use-case'ы и интерфейсы репозиториев (без SQL и Compose)
core/analytics    чистые расчёты серий и метрик
core/data         репозитории на SQLDelight, настройки, Clock, UuidGenerator
core/charts       графики на Canvas: StackedBar, GroupedBar, Line, Donut
core/ui           светлая тема, DataTable, панели, диалоги
core/network      OpenRouter и будущий backend API
core/backup       снапшоты, checksum, retention, восстановление
feature/*         ledger (общий движок расходов и доходов), expenses, income,
                  assets, analytics, ai, settings
app/desktop       точка входа, граф зависимостей, окно, упаковка
docs/             SPEC.md, DECISIONS.md
.tools/           локально скачанный Gradle (не коммитится)
```

Модули добавляются в `settings.gradle.kts` по мере реализации; сейчас подключены
`core:model`, `core:domain`, `core:data`, `core:ui`, `feature:ledger`,
`feature:expenses`, `feature:income`, `feature:assets`, `app:desktop`. Экраны расходов и
доходов — это один модуль `feature:ledger` с разными секциями (`docs/SPEC.md`, F2.2;
`docs/DECISIONS.md`, D-12), а `feature:expenses` и `feature:income` содержат только точки
входа разделов. Активы — отдельный `feature:assets` со своим контроллером: единица учёта там
снапшот счёта, а не ячейка «категория + месяц» (D-14).

## Где живут данные пользователя

- macOS: `~/Library/Application Support/app.finance/`
- Windows: `%APPDATA%\app.finance\`

Внутри: `finance.db`, `backups/`, `logs/`, `settings.json`. БД, бэкапы и логи
исключены из системы контроля версий — финансовые данные не должны попадать в репозиторий.

## Ключевые соглашения

- **Деньги** — целые копейки (`Money.minor: Long`), плавающая точка запрещена.
- **Период** — `year * 100 + month` (`202602`), только UTC-время в БД.
- **Ячейка «категория + месяц»** — единица учёта: повторный ввод суммы в ту же категорию
  того же месяца аккумулируется в существующую строку, а не создаёт новую. Каждое
  изменение пишется в журнал ревизий, поэтому ввод обратим.
- **Удаление** — soft delete (`deleted_at_utc`); для категорий это архивация.
- **SQLite** — WAL и контроль внешних ключей задаются свойствами соединения, а не разовым
  `PRAGMA` после открытия (`docs/DECISIONS.md`, D-11).
- **Секреты** (API-ключ OpenRouter) хранятся только в `SecretStore`, никогда в БД
  и в бэкапах.
