# Настройка окружения на Linux Mint без Android Studio

Проверено на LMDE 7 (gigi), Debian 13, x86_64. Android Studio не требуется.

## Что было установлено

Всё установлено **локально для пользователя**, без `sudo` и без изменения системных пакетов:

| Компонент | Версия | Путь |
|---|---|---|
| JDK (Temurin) | 17.0.21+9 | `~/.local/opt/jdk-17.0.21+9` |
| Android SDK | platform 35, build-tools 35.0.0, platform-tools 37.0.1 | `~/android-sdk` |
| cmdline-tools | latest (13114758) | `~/android-sdk/cmdline-tools/latest` |
| Gradle | 8.9 | `~/.local/opt/gradle-8.9` |
| Gradle Wrapper | 8.9 | `./gradlew` в проекте |

Проект использует `local.properties` с `sdk.dir=/home/quanysh/android-sdk`. Файл исключён из Git; при другом имени пользователя его нужно создать заново.

## Переменные окружения

В проекте есть `scripts/android-env.sh`. Перед любой командой:

```bash
source scripts/android-env.sh
```

Скрипт выставляет `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` и `PATH`, автоматически находя JDK 17 и Gradle в `~/.local/opt`.

Проверка:

```bash
source scripts/android-env.sh
java -version
gradle --version
sdkmanager --version
adb version
```

## Как установить с нуля

Команды ниже повторяют фактическую установку. Нужны `curl`, `unzip`, интернет и около 1,5 ГБ свободного места.

```bash
mkdir -p ~/.local/opt ~/android-sdk /tmp/storeprice-dl
cd /tmp/storeprice-dl
```

### 1. JDK 17

```bash
curl -fL -o jdk17.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf jdk17.tar.gz -C ~/.local/opt
```

### 2. Gradle 8.9

```bash
curl -fL -o gradle-8.9-bin.zip \
  "https://services.gradle.org/distributions/gradle-8.9-bin.zip"
unzip -q gradle-8.9-bin.zip -d ~/.local/opt
```

### 3. Android command-line tools

```bash
curl -fL -o cmdline-tools.zip \
  "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
mkdir -p ~/android-sdk/cmdline-tools
unzip -q cmdline-tools.zip -d ~/android-sdk/cmdline-tools
mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest
```

### 4. Пакеты SDK и лицензии

```bash
export JAVA_HOME=~/.local/opt/jdk-17.0.21+9
export ANDROID_HOME=~/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
yes | sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Проверка:

```bash
ls "$ANDROID_HOME/platforms" "$ANDROID_HOME/build-tools"
adb version
```

## Сборка проекта

```bash
cd /path/to/StorePrice
source scripts/android-env.sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Первый запуск скачивает Gradle 8.9 и зависимости, поэтому занимает несколько минут. Повторные сборки используют кэш.

## Подключение телефона

1. На телефоне включить «Для разработчиков» и отладку по USB.
2. Подключить кабель, подтвердить отпечаток ключа на телефоне.
3. Проверить:

```bash
adb devices -l
```

Если устройство отображается как `unauthorized`, подтвердите доступ на экране телефона. Если список пуст, проверьте кабель и режим USB (передача файлов / MTP).

На Linux для доступа без root может потребоваться udev-правило для конкретного производителя. Это единственное действие, которое требует `sudo`; в текущем окружении оно не выполнялось, так как телефон не подключён.

## Если окружение уже настроено системно

Если `java`, `gradle` и `ANDROID_HOME` доступны глобально, `scripts/android-env.sh` не обязателен: скрипт не перезаписывает уже заданные `JAVA_HOME`/`ANDROID_HOME`, но добавляет локальные пути. Можно использовать системные инструменты, если версии совместимы: JDK 17+, Android SDK 35, Gradle 8.9.

## Частые проблемы

| Симптом | Решение |
|---|---|
| `JAVA_HOME is not set` | `source scripts/android-env.sh` |
| `SDK location not found` | Создать `local.properties` с `sdk.dir=$HOME/android-sdk` |
| `Failed to install ... licenses` | Повторить `sdkmanager --licenses` |
| Долгая первая сборка | Скачиваются Gradle и зависимости; проверить интернет |
| `adb: no devices` | Включить отладку, подтвердить ключ, проверить кабель |
