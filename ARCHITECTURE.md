# Архитектура StorePrice

## 1. Статус документа

Описана **целевая** архитектура из ТЗ и **фактически созданный** каркас этапа 0. Компоненты, отмеченные «план», ещё не реализованы.

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

## 3. Потоки данных

### Чтение каталога

```text
Room (Flow) → Repository → ViewModel → StateFlow → Compose UI
```

UI никогда не ждёт сеть, чтобы показать последнюю локальную копию. Сеть обновляет Room; Room автоматически уведомляет UI.

### Изменение цены (план)

```text
UI → ViewModel → Repository → Supabase RPC/таблица
                                  ├── проверка роли (RLS)
                                  ├── optimistic concurrency по version
                                  ├── запись price_history на сервере
                                  └── постановка push-события
→ Realtime/FCM → клиенты → Room → UI
```

Клиент не формирует доверенную историю цен и не назначает роли.

### Офлайн (план)

```text
Запуск → показать Room → проверить сеть → инкрементальная синхронизация
       → обновить Room → обновить время последней синхронизации
```

Изменения в офлайне в MVP запрещены: форма сохраняет ввод и объясняет, что нужно соединение. Архитектура оставляет место для очереди операций, но не реализует её преждевременно.

## 4. Целевая структура пакетов

```text
com.storeprice.app
├── MainActivity.kt
├── core/            # форматирование, общие утилиты, Result-типы
├── data/
│   ├── local/       # Room: entities, DAO, database, converters
│   ├── remote/      # Supabase/Retrofit DTO, API, auth
│   ├── repository/  # ProductsRepository, AuthRepository, SyncRepository
│   └── sync/        # WorkManager, инкрементальная синхронизация
├── domain/          # модели, use cases, бизнес-правила
└── ui/
    ├── theme/
    ├── components/
    ├── products/    # список, поиск
    ├── product/     # карточка, редактирование
    ├── scanner/
    ├── history/
    └── auth/
```

На этапе 0 созданы только `MainActivity`, `ui/StorePriceApp.kt`, `ui/theme` и `core/PriceFormatter.kt`. Пустые пакеты заранее не создавались.

## 5. Фактический каркас этапа 0

```text
StorePrice/
├── AGENTS.md
├── REQUIREMENTS.md
├── ARCHITECTURE.md
├── ROADMAP.md
├── CURRENT_STATUS.md
├── README.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── gradlew, gradlew.bat
├── scripts/android-env.sh
├── docs/
│   ├── REQUIREMENTS.md
│   ├── SETUP_LINUX_MINT.md
│   └── BUILD_AND_INSTALL.md
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/storeprice/app/
        │   │   ├── MainActivity.kt
        │   │   ├── core/PriceFormatter.kt
        │   │   └── ui/StorePriceApp.kt + ui/theme/
        │   └── res/ (strings, theme, adaptive icon)
        └── test/java/com/storeprice/app/core/PriceFormatterTest.kt
```

Один модуль `:app` достаточен для MVP. Дробление на Gradle-модули вводить только при реальной необходимости.

## 6. Технологические решения

| Область | Решение | Статус |
|---|---|---|
| Язык | Kotlin 2.0.21 | работает |
| UI | Jetpack Compose, Material 3, Compose BOM 2024.12.01 | работает |
| Сборка | AGP 8.7.3, Gradle 8.9, JDK 17, Kotlin DSL, version catalog | работает |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 | работает |
| Локальная БД | Room | план (этап 2) |
| Камера | CameraX + ML Kit Barcode Scanning | план (этап 3) |
| Backend | Supabase Auth/Postgres/Realtime/RLS | план (этап 4) |
| Синхронизация | Repository + WorkManager + инкрементальный sync | план (этап 5) |
| Push | FCM + серверная Edge Function | план (этап 6) |

Версии зафиксированы в `gradle/libs.versions.toml`. Обновление версий — отдельная задача с повторной сборкой и тестами; автоматически «на лету» зависимости не обновляются.

## 7. Границы безопасности

- В APK допустим только публичный Supabase anon/publishable key.
- Service Role key, Firebase service account, FCM server key — только в Supabase Secrets / защищённом окружении.
- RLS обязательна для всех таблиц; клиент не может выдать себе `admin`.
- История цен и проверка конкурентных изменений (`version`) выполняются на сервере.
- Локальная БД может содержать каталог, но не серверные секреты.
- Идентификатор устройства случаен и не является аппаратным идентификатором.

## 8. Открытые архитектурные решения

Будут приняты на соответствующих этапах после проверки совместимости:

1. Supabase Kotlin SDK или Retrofit + REST — этап 4.
2. Стратегия инкрементальной синхронизации: `updated_at` + `version` или отдельный журнал изменений — этап 5.
3. Схема очереди push-событий и защита от дублей — этап 6.
4. Формат CSV и правило сопоставления штрихкодов при импорте — этап 7.

## 9. Тестирование

- Unit-тесты бизнес-логики и форматирования — JVM, выполняются без устройства.
- Тесты Room — instrumented или Robolectric, решение на этапе 2.
- UI-тесты Compose — при появлении стабильного экрана и доступного эмулятора/устройства.
- Интеграционные проверки Supabase/FCM — только при наличии реального проекта и секретов; ограничения фиксировать явно.
