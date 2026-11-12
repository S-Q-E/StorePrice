# Архитектура StorePrice

## 1. Статус документа

Описана целевая архитектура из ТЗ и фактически реализованные этапы 2–5: локальный каталог, сканер, серверная схема Supabase, Android-вход, каталог через RPC, инкрементальная синхронизация, Realtime-триггер, индикатор сети и офлайн-просмотр. Компоненты, отмеченные «план», ещё не реализованы.

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
│  Repository / AppContainer                                             │
│    ├── Room (локальный источник для UI, офлайн-кэш, поиск)             │
│    ├── supabase-kt (Auth, PostgREST, RPC каталога)                     │
│    ├── Realtime (триггер инкрементальной синхронизации в foreground)   │
│    ├── NetworkMonitor (сеть, автоповтор после reconnect)               │
│    └── WorkManager (план: фоновая синхронизация)                       │
│                                                                        │
│  CameraX + ML Kit Barcode Scanning (локальное распознавание)           │
│  Firebase Messaging (план: приём push, токен устройства)               │
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

## 3. Реализованный поток данных (этапы 2–5)

```text
Compose Screen
    │ user events
    ▼
ViewModel (StateFlow<UiState>)
    │
    ▼
ProductRepository
    ├── чтение/поиск ──────────────► Room: ProductDao → storeprice.db
    │                                  │ Flow<List<ProductEntity>>
    │                                  └──────────────► ViewModel → Compose
    │
    └── запись/синхронизация ─────► supabase-kt PostgREST/RPC
                                      │
                                      ▼
                               Supabase products + RLS/триггеры
                                      │
                                      └── upsert/замена снимка в Room
```

- UI не обращается к Room и Supabase напрямую.
- Все чтения — через `Flow`, поэтому список и карточка обновляются после записи автоматически.
- Запись идёт через `Repository`; проверка штрихкода и вставка выполняются под локальным `Mutex`, чтобы две операции приложения не создали дубликат.
- `SyncingProductRepository` оборачивает локальный репозиторий: UI-чтения делегируются Room, записи и снимок каталога — Supabase.
- Один модуль `:app`; дробление на Gradle-модули преждевременно.

### Синхронизация каталога (этапы 4–5)

- `ProductListViewModel` при создании запускает `syncCatalog()`; список сразу показывает Room и обновляется, когда транзакция Room завершится.
- Первый успешный sync (или sync после потери локальной таблицы) читает `products` keyset-пагинацией по `id` с `limit=1000`, включая мягко удалённые записи, и заменяет Room в одной транзакции. Курсор — максимальный серверный `updated_at`; если товаров нет, курсор удаляется.
- Последующие sync вызывают `fetchSince(cursor)` и получают только строки `updated_at >= cursor`. Фильтр inclusive, потому что курсор хранится в миллисекундах, а PostgreSQL — в микросекундах; повторное чтение границы безопасно из-за upsert. Курсор двигается только после успешной транзакции Room.
- `SharedPreferencesSyncMetadataStore` хранит курсор и время последней успешной синхронизации отдельно от Room, поэтому схема БД осталась v1. Если локальная таблица пуста, а курсор есть, выполняется полный sync для восстановления.
- Товары, существующие только в Room (созданные до подключения Supabase), один раз выгружаются через `create_product`; локальный UUID передаётся как `p_id`, поэтому повтор после потерянного ответа возвращает ту же строку. Если проект ещё не получил `003`, клиент по ошибке «function not found» повторяет create без `p_id`. Если штрихкод уже занят активным серверным товаром, серверная запись побеждает.
- Создание/изменение/удаление идут через `create_product`, `update_product`, `soft_delete_product`. Для update/delete передаётся `version` из Room; ответ RPC кэшируется в Room.
- `23505` → дубликат штрихкода, `40001` → конфликт версий, сетевые исключения → офлайн-сообщение. После конфликта форма запускает повторную синхронизацию и сохраняет введённые пользователем значения.
- `SyncRetryPolicy` повторяет только чтение каталога (`fetchAll`/`fetchSince`) при `NetworkUnavailable`/`ServerError`: до 3 попыток с задержками 0,5 с и 1 с (ограничение 4 с). Записи из UI автоматически не повторяются, потому что потерянный ответ сервера мог означать успешный commit; legacy-выгрузка повторяется с тем же `p_id`.
- `ProductRealtimeSource` подписывается на Postgres Changes таблицы `products`. Событие не применяется напрямую: оно только запускает `syncCatalog()`, поэтому RLS, soft delete и контроль версий остаются в одном пути. Подписка живёт в `ProcessLifecycleOwner` + `repeatOnLifecycle(STARTED)` и снимается при уходе в background.
- `NetworkMonitor` на `ConnectivityManager` отдаёт состояние сети в `AppContainer`; при переходе в онлайн выполняется тихая проверка сессии и инкрементальная синхронизация. `ProductListViewModel` показывает индикатор сети, время последней синхронизации и кнопку ручного обновления.
- `WorkManager` и очередь офлайн-записей пока не реализованы. При офлайне редактирование скрыто, введённые значения не теряются, а серверная запись не имитируется.

### Сканер штрихкодов

```text
CameraX Preview + ImageAnalysis
    │ ImageProxy (YUV)
    ▼
BarcodeAnalyzer (ML Kit, локально)
    │ String rawValue, throttle повторов
    ▼
ScannerViewModel
    │ findActiveByBarcode(barcode)
    ▼
ProductRepository → Room ProductDao (active only)
    │
    ├── найден → ScannerDestination.ExistingProduct → карточка
    └── не найден → ScannerDestination.NewProduct → форма с barcode
```

- `ScannerScreen` запрашивает `CAMERA`, обрабатывает отказ, отсутствие камеры и ошибку запуска.
- `BarcodeAnalyzer` использует CameraX `ImageAnalysis` с `STRATEGY_KEEP_ONLY_LATEST`; ML Kit работает на устройстве, модель входит в APK.
- `ScannerViewModel` блокирует сессию после первого кода: повторные кадры не запускают новый поиск и не заменяют результат. При ошибке поиска нужен явный повтор.
- CameraX привязан к `LocalLifecycleOwner`; `DisposableEffect` освобождает анализатор, executor и camera provider при уходе с экрана.
- Поиск по штрихкоду использует ту же нормализацию, что и проверка дубликатов: trim, lowercase (`Locale.ROOT`), ведущие нули сохраняются, `is_deleted = 1` исключается.
- Переходы выполняются через Navigation Compose; сканер удаляется из back stack после результата.

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

### Вход и сессия (этап 4)

```text
LoginScreen
    │ phone, password
    ▼
AuthViewModel
    │ нормализация телефона, валидация
    ▼
AuthRepository (SupabaseAuthRepository)
    │ signInWith(phone, password)
    ▼
supabase-kt Auth → GoTrue
    │ access/refresh token
    ▼
SessionManager (private SharedPreferences)
    │
    ▼
profiles (PostgREST, RLS) → Employee(role, is_active)
    │
    ├── активен → кэш профиля → каталог
    ├── сеть/5xx + свежий кэш (≤ 30 дней) → каталог + баннер «Нет связи»
    └── нет/неактивен/401/кэш устарел → сообщение и выход
```

- Телефон нормализуется к E.164: казахстанские/российские форматы `8...`, `+7...`, 10 цифр; международный `+` сохраняется.
- Вход — phone + password. Публичная регистрация в Supabase должна быть выключена; сотрудников создаёт администратор.
- `AuthViewModel` — единая точка состояния входа: `Initializing`, `SignedOut`, `SignedIn(isOffline)`, `Unavailable`.
- После входа и при восстановлении сессии клиент читает `public.profiles` текущего пользователя. Роль и `is_active` приходят с сервера, а не из клиентских настроек.
- Интерактивный вход не читает сессию после `signInWith`: коллектор и `signIn` обмениваются результатом через `Deferred` под `profileCheckMutex`, поэтому очистка сессии неактивного сотрудника не превращает `AccountInactive` в `ServerError`.
- Неактивный/отсутствующий профиль, ответ 401 и выход очищают сессию и кэш профиля.
- Активный профиль кэшируется в приватном `SharedPreferences` (`storeprice_profile_cache`) на 30 дней. Хранятся только id, имя, роль, `isActive` и время проверки; пароль и токены туда не попадают.
- При сетевой ошибке или 5xx свежий кэш того же пользователя переводит состояние в `SignedIn(isOffline = true)`: каталог и навигация сохраняются, над списком показывается баннер. `Unavailable` и экран повтора остаются только без кэша или после 30 дней.
- При возврате приложения на передний план выполняется тихая проверка сессии не чаще раза в 5 минут; успешная проверка снимает `isOffline`.
- Токены хранятся в приватных `SharedPreferences`; пароль не сохраняется. `allowBackup=false`, cloud backup и device transfer запрещены.
- `supabase-kt` 3.0.0 выбран из-за совместимости с Kotlin 2.0.20; более новые версии требуют более новый Kotlin.
- Каталог использует Supabase для начального снимка и записи через RPC, но Room остаётся источником данных для UI и офлайн-кэшем.

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
| `version` | INTEGER | версия записи; для серверных строк — `products.version`, используется в RPC update/delete |

Индексы: `barcode_normalized`, `name_normalized`.

Ограничения текущего этапа:

- уникальность штрихкода обеспечивается репозиторием, а не SQLite-констрейнтом, потому что уникальность нужна только среди неудалённых записей;
- миграций пока нет: схема v1. При любом изменении сущности нужна миграция и новая версия; destructive fallback запрещён;
- восстановление удалённого товара появится вместе с административными функциями.

### 4.1. Схема Supabase (этап 4)

Миграции: `supabase/migrations/202611100001_schema.sql` и `202611100002_rls_and_rpc.sql` применены. `202611100003_hardening.sql` и `202611120004_realtime.sql` подготовлены, но к живому проекту не применены. Инструкция: `docs/SUPABASE_SETUP.md`.

| Таблица | Назначение | Ключевые правила |
|---|---|---|
| `profiles` | сотрудники, связанные с `auth.users` | `role` admin/employee, `is_active`; новый профиль неактивен |
| `products` | общий каталог | `barcode` text, `price_kzt bigint`, soft delete, `version` |
| `price_history` | история изменений цены | неизменяемые строки, `operation_id`, `device_id` |
| `device_tokens` | FCM-токены | только владелец, уникальность активных токенов |
| `audit_log` | аудит товаров и сотрудников | чтение только администратором |

Решения:

- Вход — номер телефона + пароль через Supabase Auth; публичная регистрация отключена.
- Новый пользователь Auth получает профиль `employee` с `is_active = false`; администратор активирует его.
- Прямая запись в `products` из клиента запрещена. Используются RPC `create_product`, `update_product`, `soft_delete_product`, `restore_product`.
- `version` увеличивается BEFORE-триггером; RPC сравнивает ожидаемую версию и возвращает `40001` при конфликте.
- История цены пишется AFTER-триггером в той же транзакции; отдельного клиентского INSERT нет.
- Уникальность штрихкода — частичный уникальный индекс `lower(barcode)` среди `is_deleted = false`.
- RLS включена на всех таблицах; `anon` не имеет прав; чтение каталога доступно только активным сотрудникам.
- `audit_log` и `price_history` защищены от изменения обычными ролями; Service Role допустим только для обслуживания.

На 2026-11-12 миграции `001`/`002` применены к живому Supabase: таблицы, RLS, RPC и триггеры созданы, Phone provider включён, публичная регистрация отключена, первый администратор создан. Вход, общий каталог, запись через RPC и офлайн-режим проверены пользователем на телефоне. Android-клиент использует Supabase Auth, PostgREST для профиля и каталога, RPC для записи, Realtime как триггер синхронизации; Room остаётся источником данных для UI. Миграции `003_hardening.sql` и `004_realtime.sql` подготовлены, но к живому проекту ещё не применены.

## 5. UI и навигация

```text
StorePriceApp
└── auth gate
    ├── Loading
    ├── LoginScreen             → phone + password
    ├── SessionUnavailable      → повтор / выход (только без свежего кэша)
    └── products (каталог + поиск)
        ├── scanner                   → CameraX + ML Kit
        │   ├── найденный товар       → product/{id}
        │   └── неизвестный код       → product/new?barcode={barcode}
        ├── product/new[?barcode=…]   → форма создания / форма после сканера
        └── product/{id}              → карточка
            └── product/{id}/edit     → форма редактирования
```

Экраны:

- **Вход** — телефон, пароль, показ/скрытие пароля, локальная валидация, состояние загрузки, русские ошибки сервера.
- **Каталог** — поиск, счётчик, локальный бейдж, список, пустые состояния, FAB добавления, кнопка сканера, меню аккаунта с именем/ролью и выходом. При офлайн-входе над списком показывается узкий баннер «Нет связи: показаны сохранённые данные»; навигация и текущий экран не сбрасываются.
- **Карточка** — название, цена, штрихкод, время изменения, редактирование, удаление с подтверждением.
- **Форма** — валидация полей, предпросмотр цены, ошибка дубликата штрихкода, сохранение; для нового товара может быть предзаполнен штрихкод.
- **Сканер** — предпросмотр камеры, рамка, состояния разрешения/отсутствия камеры/ошибки, защита от повторного срабатывания.

Удаление — логическое; после него карточка закрывается, товар исчезает из списка и поиска, штрихкод можно использовать снова.

Auth gate стоит перед каталогом: без активного профиля экраны товаров недоступны. Каталог показывает метку «Общий каталог», потому что Room синхронизируется с Supabase. В офлайне рядом с меткой остаются сохранённые данные и предупреждение о возможной устарелости цен.

## 6. Фактическая структура

```text
com.storeprice.app
├── MainActivity.kt
├── StorePriceApplication.kt      # AppContainer, ручной DI
├── core/
│   ├── PriceFormatter.kt
│   ├── SearchText.kt
│   ├── PhoneNumber.kt            # E.164-нормализация телефона
│   └── DateTimeFormat.kt
├── data/
│   ├── local/
│   │   ├── ProductEntity.kt
│   │   ├── ProductDao.kt
│   │   └── StorePriceDatabase.kt
│   ├── auth/
│   │   ├── AuthRepository.kt
│   │   ├── AuthBackend.kt
│   │   ├── AuthSessionController.kt
│   │   ├── CachedProfileStore.kt
│   │   ├── SupabaseAuthRepository.kt
│   │   ├── MissingConfigAuthRepository.kt
│   │   ├── SessionManager.kt
│   │   └── SharedPreferencesSessionManager.kt
│   ├── remote/
│   │   ├── ProductRemote.kt      # PostgREST-пагинация, fetchSince
│   │   └── ProductRealtimeSource.kt
│   ├── sync/
│   │   ├── SyncMetadataStore.kt  # курсор и время последнего sync
│   │   └── NetworkMonitor.kt
│   ├── scanner/
│   │   └── BarcodeAnalyzer.kt    # CameraX ImageAnalysis + ML Kit
│   └── repository/
│       ├── ProductRepository.kt
│       ├── RoomProductRepository.kt
│       └── SyncingProductRepository.kt
├── domain/
│   ├── auth/AuthModels.kt
│   ├── model/Product.kt
│   ├── model/Employee.kt
│   └── validation/ProductValidator.kt
└── ui/
    ├── StorePriceApp.kt          # auth gate, NavHost, маршруты
    ├── AppRepository.kt
    ├── theme/
    ├── auth/                     # LoginScreen + AuthViewModel + SessionUnavailable
    ├── scanner/                  # ScannerScreen + ScannerViewModel
    ├── products/                 # ProductListScreen + ViewModel
    └── product/                  # detail/editor + ViewModel
```

Тесты:

```text
app/src/test/java/com/storeprice/app
├── MainActivitySmokeTest.kt
├── core/ (PriceFormatter, SearchText, PhoneNumber, DateTimeFormat)
├── data/repository/ (Room CRUD/search/persistence, barcode lookup)
├── domain/validation/
├── ui/product/ (editor ViewModel)
├── ui/scanner/ (ScannerViewModel: result, duplicates, retry)
├── ui/auth/ (AuthViewModel: validation, sign-in, logout)
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
| Камера | CameraX 1.4.2 + ML Kit Barcode Scanning 17.3.0 | работает; основной сценарий проверен на телефоне |
| Backend | Supabase Auth/Postgres/Realtime/RLS | миграции `001`/`002` и RLS/RPC применены; вход и каталог проверены на телефоне; `003` и `004` ожидают отдельного применения |
| Android Auth | `supabase-kt` 3.0.0 (`auth-kt`, `postgrest-kt`), Ktor OkHttp 3.0.0-rc-1 | вход, сессия, профиль, офлайн-кэш и выход реализованы и проверены на телефоне |
| Синхронизация | Repository + Room-first sync, `updated_at`-курсор, Realtime, NetworkMonitor | реализовано локально; живая проверка двух устройств и Realtime впереди |
| Фоновая синхронизация | WorkManager | план; в текущем объёме повтор только в foreground |
| Push | FCM + серверная Edge Function | план (этап 6) |

Версии зафиксированы в `gradle/libs.versions.toml`. Lint сообщает о более новых версиях; обновление — отдельная задача с повторной сборкой и тестами.

## 8. Границы безопасности

- В APK допустим только публичный Supabase anon/publishable key.
- Service Role key, Firebase service account, FCM server key — только в Supabase Secrets / защищённом окружении.
- RLS обязательна для всех таблиц; клиент не может выдать себе `admin`.
- История цен и проверка конкурентных изменений (`version`) будут выполняться на сервере.
- Локальная БД может содержать каталог, но не серверные секреты.
- Идентификатор устройства будет случайным и не аппаратным.
- Access/refresh token хранятся в приватных `SharedPreferences`; пароль не сохраняется. `allowBackup=false`, cloud backup и device transfer запрещены. Отдельного шифрования токенов нет — это известное ограничение.
- Публичные `SUPABASE_URL` и publishable key попадают в `BuildConfig` из игнорируемого `local.properties`; `service_role` в Android-модуль не добавляется.
- Сканирование выполняется локальной моделью ML Kit. Зависимость ML Kit добавляет в объединённый manifest разрешения `INTERNET` и `ACCESS_NETWORK_STATE`; с этапа 4 приложение использует сеть для Supabase Auth и чтения профиля.

## 9. Открытые архитектурные решения

1. Миграции Room при изменении схемы; на этапе 5 схема не менялась, курсор вынесен в `SharedPreferences`.
2. Стратегия инкрементальной синхронизации: `updated_at`-курсор реализован; журнал изменений и периодический полный sync для восстановления после повреждения кэша — возможное развитие.
3. Разрешение конфликтов между локальной и серверной версией: серверный `version` и UI-сообщение реализованы; автоматическое слияние не делается.
4. Схема очереди push-событий и защита от дублей — этап 6.
5. Формат CSV и правило сопоставления штрихкодов при импорте — этап 7.
6. Отдельное шифрование сессии (EncryptedSharedPreferences/Keystore) — при повышении требований безопасности.

## 10. Тестирование

Фактически выполнено на этапах 2–5:

- unit-тесты валидации, нормализации, форматирования цены/даты;
- Robolectric-тесты Room: CRUD, дубликаты, мягкое удаление, поиск и приоритеты, 5 000 товаров, сохранение после закрытия/открытия БД, поиск активного товара по штрихкоду;
- ViewModel-тесты формы: валидация, сохранение, дубликат, загрузка, отсутствие товара, предзаполнение штрихкода;
- ViewModel-тесты сканера: найденный/неизвестный код, пустой код, блокировка повторных событий, повтор после ошибки;
- тесты анализатора штрихкодов: выбор кода ближе к центру кадра, `null`-границы, несколько далёких кодов, пустой список;
- тесты телефона: форматы `8...`, `+7...`, 10 цифр, международный номер, неверные и не-ASCII цифры;
- ViewModel-тесты входа: валидация, нормализация, успех, ошибки, сброс ошибки, выход;
- тесты синхронизации: полный снимок, инкрементальный upsert, движение курсора, сохранение кэша при ошибке, backoff чтения, отсутствие автоматических повторов записи из UI, состояние времени последней синхронизации;
- smoke-тест запуска `MainActivity`;
- ручная проверка на эмуляторе Android 15: каталог, карточка, поиск, дубликат, редактирование, удаление; экран сканера, разрешение/отказ, предпросмотр камеры, освобождение камеры при выходе;
- ручная проверка входа на эмуляторе до настройки живого проекта: экран, локальная ошибка формата, реальный ответ `phone_provider_disabled`;
- ручная проверка на физическом телефоне пользователем: распознавание штрихкодов, добавление товара и повторное открытие карточки (APK этапа 3);
- SQL-миграции Supabase: применены к живому проекту; проверены таблицы, 9 политик RLS, 8 функций, 5 триггеров, вход администратора и чтение `products`/`profiles` с реальным JWT;
- живой Supabase: Auth health 200, `external.phone: true`, `disable_signup: true`, анонимный доступ к `profiles` → `401`.

Не выполнено: instrumented-тесты на устройстве, Compose UI-тесты, полная матрица форматов и edge-case-сценарии камеры на телефоне, деактивация/смена роли/refresh через живой JWT, Realtime на двух устройствах, индикатор сети и возврат сети на APK `0.5.0`, FCM. Подробности — `docs/TESTING.md`.
