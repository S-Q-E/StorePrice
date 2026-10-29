# StorePrice

Android-приложение для небольшого продуктового магазина: общий каталог товаров, цены в тенге, поиск, сканирование штрихкодов, история цен, офлайн-доступ и синхронизация между сотрудниками.

## Текущее состояние

**Этап 0 завершён.** Создан минимальный нативный проект на Kotlin и Jetpack Compose. Debug APK собирается, unit-тесты и lint проходят. Каталог товаров, сканер, Supabase и FCM ещё не реализованы — это следующие этапы.

Подробности: [`CURRENT_STATUS.md`](CURRENT_STATUS.md).

## Стек

- Kotlin 2.0.21, Jetpack Compose, Material 3
- MVVM, Coroutines/Flow (по мере развития)
- Gradle 8.9, AGP 8.7.3, JDK 17
- minSdk 26, targetSdk 35, compileSdk 35

## Быстрый старт

```bash
source scripts/android-env.sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Установка на подключённый телефон:

```bash
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Документация

| Документ | Содержание |
|---|---|
| [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md) | Полное ТЗ |
| [`REQUIREMENTS.md`](REQUIREMENTS.md) | Краткие требования и границы этапа |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Архитектура |
| [`ROADMAP.md`](ROADMAP.md) | Этапы разработки |
| [`CURRENT_STATUS.md`](CURRENT_STATUS.md) | Фактический статус и проверки |
| [`AGENTS.md`](AGENTS.md) | Правила работы над проектом |
| [`docs/SETUP_LINUX_MINT.md`](docs/SETUP_LINUX_MINT.md) | Настройка окружения |
| [`docs/BUILD_AND_INSTALL.md`](docs/BUILD_AND_INSTALL.md) | Сборка и установка |

## Ограничения этапа 0

- Нет подключённого Android-устройства или эмулятора: APK не устанавливался, UI на устройстве не проверялся.
- Supabase и Firebase не настроены; секреты не требуются и не добавлены.
- Release-подпись и minify не настроены.
