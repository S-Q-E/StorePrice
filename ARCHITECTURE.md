# Архитектура StorePrice

## 1. Статус документа

Описана целевая архитектура из ТЗ и фактически реализованный локальный каталог этапа 2. Компоненты, отмеченные «план», ещё не реализованы.

## 2. Целевая схема

```text
┌──────────────────────────────── Android ────────────────────────────────┐
│                                                                        │
│  Compose UI (экраны, Material 3, русский язык)                         │
│        │ события                         ▲ StateFlow<UiState>          │
│        ▼                                │                              │
│  ViewModel (MVVM, Coroutines/Flow) ─────┘                              │
│        │                                                               │
│        ▼                                                               │
│  Repository                                                            │
│    ├── Room (локальный источник для UI, офлайн-кэш, поиск)             │
│    ├── Supabase SDK/Retrofit (auth, CRUD, инкрементальная синхронизация)│
│    ├── Realtime (обновления при активном приложении)                   │
│    └── WorkManager (повторы, фоновая синхронизация)                   │
│                                                                        │
│  CameraX + ML Kit Barcode Scanning (локальное распознавание)           │
│  Firebase Messaging (приём push, токен устройства)                     │
└────────────────────────────────────────────────────────────────────────┘
                 │ HTTPS / WebSocket / FCM
                 ▼
┌──────────────────────────────── Supabase ───────────────────────────────┐
│ Auth (сотрудники, сессии)                                              │
│ PostgreSQL: products, profiles, price_history, device_tokens, ...      │
│ RLS-политики + серверные функции/триггеры                              │
│ Realtime (публикация изменений каталога)                               │
│ Edge Function: проверка прав, запись истории, отправка FCM             │
└────────────────────────────────────────────────────────────────────────┘
                 │ FCM HTTP v1
                 ▼
        Firebase Cloud Messaging → устройства сотрудников
```

## 3. Реализованный поток данных (этап 2)

```text
Compose Screen
    │ user events
    ▼
ViewModel (StateFlow<UiState>)
    │
    ▼
ProductRepository
    │
    ▼
Room: ProductDao → storeprice.db
    │ Flow<List<ProductEntity>>
    └──────────────────────────► ViewModel → Compose
```

- UI не обращается к Room напрямую.
- Все чтения — через `Flow`, поэтому список и карточка обновляются после записи автоматически.
- Запись идёт через `Repository`; проверка штрихкода и вставка выполняются под локальным `Mutex`, чтобы две операции приложения не создали дубликат.
- Один модуль `:app`; дробление на Gradle-модули преждевременно.

### Поиск

`ProductRepository.observeProducts(query)`:

1. Нормализует запрос: trim, схлопывание пробелов, lowercase (`Locale.ROOT`).
2. Экранирует `%`, `_`, `\` для SQL `LIKE`.
3. Для пустого запроса вызывает `observeAll()`.
4. Иначе — `observeSearch()` с приоритетом:
   - точный штрихкод;
   - точное название;
   - штрихкод-префикс;
   - название-префикс;
   - вхождение;
   - стабильная сортировка по нормализованному названию и `id`.
5. Возвращает domain-модели `Product`, скрывая `is_deleted = 1`.

`ProductListViewModel` использует `collectLatest`: при быстром вводе предыдущий запрос отменяется. Поиск полностью локальный, сеть не участвует.

### Валидация

`ProductValidator`:

- имя обязательно, trim + схлопывание пробелов;
- цена — только целое неотрицательное число, пробелы и NBSP допускаются как разделители;
- переполнение `Long` отклоняется;
- пустой штрихкод → `null`, ведущие нули сохраняются;
- длина штрихкода ≤ 64;
- дубликат активного штрихкода проверяется репозиторием без учёта регистра.

## 4. Схема локальной БД (v1)

Файл: `app/schemas/com.storeprice.app.data.local.StorePriceDatabase/1.json`.

Таблица `products`:

| Поле | Тип | Правило |
|---|---|---|
| `id` | TEXT PK | UUID, генерируется при создании |
| `barcode` | TEXT NULL | отображаемое значение, ведущие нули сохраняются |
| `barcode_normalized` | TEXT NULL | lowercase для поиска и проверки дубликатов |
| `name` | TEXT | отображаемое название |
| `name_normalized` | TEXT | lowercase + схлопнутые пробелы для поиска |
| `price_kzt` | INTEGER | целые тенге, ≥ 0 |
| `is_deleted` | INTEGER | логическое удаление |
| `created_at` | INTEGER | epoch millis UTC |
| `updated_at` | INTEGER | epoch millis UTC |
| `version` | INTEGER | локальная версия записи, растёт при изменении/удалении |

Индексы: `barcode_normalized`, `name_normalized`.

Ограничения текущего этапа:

- уникальность штрихкода обеспечивается репозиторием, а не SQLite-констрейнтом, потому что уникальность нужна только среди неудалённых записей;
- миграций пока нет: схема v1. При любом изменении сущности нужна миграция и новая версия; destructive fallback запрещён;
- восстановление удалённого товара появится вместе с административными функциями.

## 5. UI и навигация

```text
products (каталог + поиск)
├── product/new              → форма создания
└── product/{id}             → карточка
    └── product/{id}/edit    → форма редактирования
```

Экраны:

- **Каталог** — поиск, счётчик, локальный бейдж, список, пустые состояния, FAB добавления.
- **Карточка** — название, цена, штрихкод, время изменения, редактирование, удаление с подтверждением.
- **Форма** — валидация полей, предпросмотр цены, ошибка дубликата штрихкода, сохранение.

Удаление — логическое; после него карточка закрывается, товар исчезает из списка и поиска, штрихкод можно использовать снова.

Интерфейс не показывает фиктивные сетевые статусы: на этапе 2 отображается честная метка «Локальный каталог».

## 6. Фактическая структура

```text
com.storeprice.app
├── MainActivity.kt
├── StorePriceApplication.kt      # AppContainer, ручной DI
├── core/
│   ├── PriceFormatter.kt
│   ├── SearchText.kt
│   └── DateTimeFormat.kt
├── data/
│   ├── local/
│   │   ├── ProductEntity.kt
│   │   ├── ProductDao.kt
│   │   └── StorePriceDatabase.kt
│   └── repository/
│       └── ProductRepository.kt
├── domain/
│   ├── model/Product.kt
│   └── validation/ProductValidator.kt
└── ui/
    ├── StorePriceApp.kt          # NavHost
    ├── AppRepository.kt
    ├── theme/
    ├── products/                 # ProductListScreen + ViewModel
    └── product/                  # detail/editor + ViewModel
```

Тесты:

```text
app/src/test/java/com/storeprice/app
├── MainActivitySmokeTest.kt
├── core/ (PriceFormatter, SearchText, DateTimeFormat)
├── data/repository/ (Room CRUD/search/persistence)
├── domain/validation/
├── ui/product/ (editor ViewModel)
└── testutil/MainDispatcherRule.kt
```

## 7. Технологические решения

| Область | Решение | Статус |
|---|---|---|
| Язык | Kotlin 2.0.21 | работает |
| UI | Jetpack Compose, Material 3, Compose BOM 2024.12.01 | работает |
| Навигация | Navigation Compose 2.8.5 | работает |
| Сборка | AGP 8.7.3, Gradle 8.9, JDK 17, Kotlin DSL, version catalog | работает |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 | работает |
| Локальная БД | Room 2.6.1 + KSP 2.0.21-1.0.28 | работает |
| Тесты | JUnit 4, Robolectric 4.14.1, coroutines-test | работает |
| Камера | CameraX + ML Kit Barcode Scanning | план (этап 3) |
| Backend | Supabase Auth/Postgres/Realtime/RLS | план (этап 4) |
| Синхронизация | Repository + WorkManager + инкрементальный sync | план (этап 5) |
| Push | FCM + серверная Edge Function | план (этап 6) |

Версии зафиксированы в `gradle/libs.versions.toml`. Lint сообщает о более новых версиях; обновление — отдельная задача с повторной сборкой и тестами.

## 8. Границы безопасности

- В APK допустим только публичный Supabase anon/publishable key.
- Service Role key, Firebase service account, FCM server key — только в Supabase Secrets / защищённом окружении.
- RLS обязательна для всех таблиц; клиент не может выдать себе `admin`.
- История цен и проверка конкурентных изменений (`version`) будут выполняться на сервере.
- Локальная БД может содержать каталог, но не серверные секреты.
- Идентификатор устройства будет случайным и не аппаратным.

## 9. Открытые архитектурные решения

1. Миграции Room при изменении схемы (этап 3+).
2. Supabase Kotlin SDK или Retrofit + REST — этап 4.
3. Стратегия инкрементальной синхронизации: `updated_at` + `version` или журнал изменений — этап 5.
4. Разрешение конфликтов между локальной и серверной версией — этап 5.
5. Схема очереди push-событий и защита от дублей — этап 6.
6. Формат CSV и правило сопоставления штрихкодов при импорте — этап 7.

## 10. Тестирование

Фактически выполнено на этапе 2:

- unit-тесты валидации, нормализации, форматирования цены/даты;
- Robolectric-тесты Room: CRUD, дубликаты, мягкое удаление, поиск и приоритеты, 5 000 товаров, сохранение после закрытия/открытия БД;
- ViewModel-тест формы: валидация, сохранение, дубликат, загрузка и отсутствие товара;
- smoke-тест запуска `MainActivity`;
- ручная проверка основных сценариев на эмуляторе Android 15 (установка, каталог, карточка, поиск, дубликат, редактирование, удаление).

Не выполнено: instrumented-тесты на устройстве, Compose UI-тесты, проверка на реальном телефоне, проверка камеры, Supabase и FCM. Подробности — `docs/TESTING.md`.
