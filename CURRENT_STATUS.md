# Текущий статус StorePrice

**Дата проверки:** 2026-10-29
**Этап:** 0 — подготовка среды и минимальный каркас. **Завершён.**
**Следующий этап:** локальный каталог на Room, CRUD и поиск (этап 2 ТЗ) — только по отдельному заданию.

## Итог одной строкой

Debug APK собирается из чистого состояния, 4 unit-теста проходят, lint не содержит ошибок. На Android-устройстве приложение **не запускалось**: телефон не подключён, эмулятор не установлен. Supabase, FCM, Room и сканер не реализованы намеренно.

## Среда (фактически проверено)

| Компонент | Фактическое значение | Расположение |
|---|---|---|
| ОС | LMDE 7 (gigi), Debian 13, kernel 6.12.48, x86_64 | — |
| JDK | Eclipse Temurin 17.0.21+9 | `~/.local/opt/jdk-17.0.21+9` |
| Gradle | 8.9 (установлен локально) | `~/.local/opt/gradle-8.9` |
| Gradle Wrapper | 8.9, `distributionUrl` services.gradle.org | `./gradlew` |
| Android SDK Platform | android-35 | `~/android-sdk/platforms/android-35` |
| Build Tools | 35.0.0 | `~/android-sdk/build-tools/35.0.0` |
| Platform Tools / ADB | 37.0.1 | `~/android-sdk/platform-tools` |
| cmdline-tools | latest (13114758) | `~/android-sdk/cmdline-tools/latest` |
| Git | 2.47.3 | системный |
| Android Studio | не установлена | — |

- `sudo` не использовался; системные пакеты не менялись.
- `ANDROID_HOME=/home/quanysh/android-sdk`, `JAVA_HOME` выставляются через `source scripts/android-env.sh`.
- `local.properties` создан локально и исключён из Git.
- ADB: `adb devices -l` вернул пустой список. Устройство не подключено.
- `/dev/kvm` присутствует, но пакет `emulator` и system image не устанавливались.

## Версии проекта

| Параметр | Значение |
|---|---|
| applicationId | `com.storeprice.app` |
| versionName / versionCode | `0.1.0` / `1` |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| Kotlin | 2.0.21 |
| AGP | 8.7.3 |
| Compose BOM | 2024.12.01 |
| Material 3 | из Compose BOM |
| JVM target | 17 |

Версии зафиксированы в `gradle/libs.versions.toml`. Lint сообщает о наличии более новых версий (см. предупреждения); обновление не выполнялось, чтобы сохранить проверенную совместимость. Это отдельная задача с повторной сборкой и тестами.

## Что работает

- Минимальное приложение на Kotlin + Jetpack Compose + Material 3.
- Один экран на русском: название, статус этапа, пример форматирования цены, следующий шаг.
- Светлая и тёмная темы Compose, адаптивная иконка приложения.
- Форматирование цены в целых тенге (`formatKzt`) с unit-тестами.
- Gradle Wrapper, version catalog, Kotlin DSL.
- `.gitignore` исключает `build/`, `.gradle/`, `local.properties`, секреты и keystore.

## Выполненные команды и результаты

| Команда | Результат |
|---|---|
| `java -version` | Temurin 17.0.21+9, exit 0 |
| `gradle --version` | Gradle 8.9, JVM 17.0.21, exit 0 |
| `sdkmanager --licenses` | все лицензии приняты, exit 0 |
| `sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"` | установлено, exit 0 |
| `gradle wrapper --gradle-version 8.9` | BUILD SUCCESSFUL (5 мин 08 с, первая загрузка плагинов) |
| `./gradlew assembleDebug` (первый раз) | BUILD SUCCESSFUL (9 мин 24 с) |
| `./gradlew testDebugUnitTest` | BUILD SUCCESSFUL, 4 теста, 0 падений |
| `./gradlew lintDebug` | BUILD SUCCESSFUL, 0 ошибок, 16 предупреждений |
| `./gradlew clean assembleDebug testDebugUnitTest` | BUILD SUCCESSFUL |
| `./gradlew clean assembleDebug testDebugUnitTest --no-build-cache` | BUILD SUCCESSFUL, 42 задачи выполнены, 29 с |
| `adb devices -l` | пустой список устройств |

Логи: `/tmp/opencode/build.log`, `/tmp/opencode/test.log`, `/tmp/opencode/lint.log`, `/tmp/opencode/clean-nocache.log`.

## Тесты

`app/src/test/java/com/storeprice/app/core/PriceFormatterTest.kt`:

- `zero is formatted with currency suffix` — пройден.
- `thousands are grouped with spaces` — пройден.
- `large amounts are grouped in triples` — пройден.
- `negative amount is rejected` — пройден.

Итого: **4 запущено, 4 прошло, 0 пропущено, 0 ошибок.**

Инструментальные и Compose UI-тесты не добавлены: для них нужен эмулятор или телефон. Это ограничение, а не «пройденная» проверка.

## APK

- Путь: `app/build/outputs/apk/debug/app-debug.apk`
- Размер: **9 457 341 байт** (≈ 9,0 МиБ)
- SHA-256: `d4803a2d562e9d54e9524e381e34d2a16b0d9d0d256c0ce58afd3e02cc03f2bc`
- Package: `com.storeprice.app`, versionName `0.1.0`, versionCode `1`
- minSdk 26, targetSdk 35, compileSdk 35
- Label: `StorePrice`
- Launchable activity: `com.storeprice.app.MainActivity`
- Подпись: debug (автоматический debug keystore)

APK **не устанавливался** и **не запускался** на устройстве. Проверено только наличие и метаданные файла через `ls`, `sha256sum`, `aapt dump badging`.

## Lint

- Ошибок: **0**.
- Предупреждений: **16**:
  - 3 × `AndroidGradlePluginVersion` — доступен AGP 9.5.0;
  - 12 × `GradleDependency` — доступны более новые core-ktx 1.19.1, lifecycle 2.11.0, activity-compose 1.13.0, Compose BOM 2026.09.00;
  - 1 × `ObsoleteSdkInt` — папка `mipmap-anydpi-v26` при minSdk 26; оставлена, так как без qualifier `v26` AAPT не находит adaptive icon.

Предупреждения не блокируют сборку. Отчёт: `app/build/reports/lint-results-debug.html`.

## Git

- До начала работ Git-репозитория в папке не было; существовал только `docs/REQUIREMENTS.md`.
- Репозиторий инициализирован, ветка `main`.
- Существующий `docs/REQUIREMENTS.md` не перезаписывался.
- Начальный коммит содержит документацию и минимальный проект.
- Удалённый репозиторий (remote) не настроен.
- Секреты, `local.properties` и артефакты сборки в Git не попадают.

## Что намеренно не реализовано

- Room, модели товара, CRUD, поиск.
- Карточка товара, история цен, импорт/экспорт.
- CameraX и ML Kit, разрешения камеры.
- Supabase: проект, миграции, RLS, авторизация, Realtime.
- FCM, токены устройств, серверная отправка push.
- WorkManager и синхронизация.
- Release-подпись, minify, CI.
- `.env.example` и конфигурация Supabase/Firebase — появятся на соответствующих этапах.

## Остающиеся ограничения и риски

1. **Нет проверки на устройстве.** Не подтверждены запуск, отрисовка Compose, установка APK и поведение на реальном экране.
2. **Нет эмулятора.** Instrumented/UI-тесты не запускались.
3. **Нет release-сборки.** `assembleRelease` не проверялся, keystore отсутствует.
4. **Версии 2024 года.** Сборка воспроизводима, но зависимости не самые новые; обновление может потребовать compileSdk 36+ и миграции AGP/Kotlin.
5. **Первый запуск Gradle долгий** из-за загрузки дистрибутива и зависимостей. Последующие сборки быстрые.
6. **`local.properties` привязан к `/home/quanysh/android-sdk`.** На другой машине путь нужно изменить.
7. **Нет CI и remote.** Проверки выполняются только локально.
8. **Нет реальных проектов Supabase/Firebase** — сетевые и push-функции невозможно проверить на этом этапе.

## Следующий шаг

По отдельному заданию — этап 2 ТЗ: Room, CRUD товаров, локальный поиск, unit-тесты, повторная сборка APK. Перед этим желательно подключить телефон по USB (`adb devices -l`) или установить эмулятор, чтобы проверять приложение на устройстве.
