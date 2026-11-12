# Сборка и установка APK

## Требования

- JDK 17
- Android SDK 35
- Gradle Wrapper из проекта (скачает Gradle 8.9 автоматически)

Подготовка окружения: [`SETUP_LINUX_MINT.md`](SETUP_LINUX_MINT.md).

## Параметры Supabase

Для работающего экрана входа нужны публичные параметры в `local.properties` (файл исключён из Git):

```properties
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_ANON_KEY=<publishable-key>
```

`SUPABASE_URL` — базовый адрес без `/rest/v1/`. Используйте только publishable/anon key. `service_role` в Android-модуль, `local.properties` и Git не добавляется. Без параметров APK собирается, но вход показывает сообщение о незавершённой настройке.

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

Для сервера без экрана добавьте `-no-window`. Эмулятор использует программную графику и работает медленнее телефона. Для проверки экрана сканера и разрешений можно добавить виртуальную камеру:

```bash
"$ANDROID_HOME/emulator/emulator" -avd storeprice_test \
  -no-window -no-audio -gpu swiftshader_indirect \
  -camera-back virtualscene -camera-front none &
```

Виртуальная сцена позволяет проверить предпросмотр и освобождение камеры, но не заменяет проверку реального распознавания штрихкодов, автофокуса и качества камеры телефона.

## Установка без ADB

1. Скопировать `app-debug.apk` на телефон (USB-хранилище, облако, мессенджер).
2. Открыть файл через файловый менеджер.
3. Разрешить установку из неизвестных источников для выбранного приложения.
4. Подтвердить установку.

APK подписан debug-ключом и предназначен только для тестирования, не для публикации.

Текущий APK `0.5.0` содержит встроенную модель ML Kit для офлайн-распознавания штрихкодов и сетевые библиотеки Supabase, поэтому его размер ≈ 36,1 МиБ. SHA-256 текущей сборки указан в `CURRENT_STATUS.md`.

## Ограничение текущего окружения

На машине разработчика при последней сборке `adb devices` не показывал подключённых устройств. Текущий APK `0.5.0` с инкрементальной синхронизацией, Realtime и индикатором сети на эмулятор не устанавливался. Ранее на эмуляторе Android 15 (AVD `storeprice_test`) проверялись экран входа, локальная валидация и ответ Supabase `phone_provider_disabled` до настройки живого проекта. Пользователь установил APK этапа 4 на физический телефон и подтвердил успешный вход, общий каталог, запись через RPC и офлайн-режим. Realtime на двух устройствах, индикатор сети, время синхронизации и перезапуск без сети на APK `0.5.0` ещё не проверены. Для Realtime нужно применить `202611120004_realtime.sql` к живому проекту.

## Release-сборка

Пока не настроена: нет keystore, подписи и правил minify. `assembleRelease` создаст непубликуемый артефакт. Настройка release — отдельная задача перед распространением.
