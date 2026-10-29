# Сборка и установка APK

## Требования

- JDK 17
- Android SDK 35
- Gradle Wrapper из проекта (скачает Gradle 8.9 автоматически)

Подготовка окружения: [`SETUP_LINUX_MINT.md`](SETUP_LINUX_MINT.md).

## Сборка debug APK

```bash
cd /path/to/StorePrice
source scripts/android-env.sh
./gradlew assembleDebug
```

Результат:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Проверить фактическое наличие файла:

```bash
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

## Тесты и lint

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Отчёты:

- тесты: `app/build/reports/tests/testDebugUnitTest/index.html`
- lint: `app/build/reports/lint-results-debug.html`

## Установка через USB и ADB

1. Включить на телефоне режим разработчика и «Отладка по USB».
2. Подключить телефон, подтвердить отпечаток ключа.
3. Проверить подключение:

```bash
adb devices -l
```

4. Установить:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

5. Запустить приложение:

```bash
adb shell am start -n com.storeprice.app/.MainActivity
```

Если установка отклонена из-за подписи, удалить старую debug-версию:

```bash
adb uninstall com.storeprice.app
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Запуск на эмуляторе

В текущем окружении установлены эмулятор 37.2.12 и system image Android 15 `system-images;android-35;default;x86_64`, создан AVD `storeprice_test`.

```bash
source scripts/android-env.sh
"$ANDROID_HOME/emulator/emulator" -avd storeprice_test -no-audio -gpu swiftshader_indirect &
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.storeprice.app/.MainActivity
```

Для сервера без экрана добавьте `-no-window`. Эмулятор использует программную графику и работает медленнее телефона; для проверки камеры он не подходит.

## Установка без ADB

1. Скопировать `app-debug.apk` на телефон (USB-хранилище, облако, мессенджер).
2. Открыть файл через файловый менеджер.
3. Разрешить установку из неизвестных источников для выбранного приложения.
4. Подтвердить установку.

APK подписан debug-ключом и предназначен только для тестирования, не для публикации.

## Ограничение текущего окружения

Реальный телефон не подключён (`adb devices` показывает только эмулятор `emulator-5554`). Финальный debug APK установлен и запущен на эмуляторе Android 15 (AVD `storeprice_test`), основные сценарии каталога проверены вручную. Камера, конкретный экран телефона и реальная производительность не проверены. Для этапа 3 нужен физический телефон.

## Release-сборка

Пока не настроена: нет keystore, подписи и правил minify. `assembleRelease` создаст непубликуемый артефакт. Настройка release — отдельная задача перед распространением.
