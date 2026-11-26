# IISHKO Android Selfhost

Android-порт **IISHKO Selfhost**: локальный IISHKO-сервер работает на телефоне, а интерфейс открывается в WebView. Приложение не требует root: Linux-окружение запускается через PRoot, внутри — Debian rootfs, Bun и сервер IISHKO.

- Репозиторий: https://github.com/ghostcoder-wq/iishko-android
- Releases: https://github.com/ghostcoder-wq/iishko-android/releases/tag/v2.0

## Что внутри

- Android-приложение `com.iishko.selfhost` (Java, minSdk 23, targetSdk 28, arm64-v8a).
- WebView, который открывает `http://127.0.0.1:8091/`.
- PRoot + Debian rootfs (~985 МБ в распакованном виде).
- Bun и IISHKO server (`iishko-app`, версия 1.0.5).
- Foreground service, wake lock, автозапуск после перезагрузки, восстановление сервера.
- Логи в `/sdcard/IISHKO/selfhost.log` и во внешнем каталоге приложения.

APK содержит сжатый rootfs, поэтому весит ~334 МБ. После первого запуска rootfs распаковывается в `/data/data/com.iishko.selfhost/files/rootfs`.

## Быстрый старт

1. Установи APK из раздела **Releases**.
2. Разреши установку из неизвестных источников.
3. При первом запуске разреши доступ к файлам и уведомления.
4. Дождись распаковки rootfs (может занять несколько минут) и запуска сервера.
5. Отключи оптимизацию батареи для IISHKO; на Xiaomi/Redmi/Poco включи автозапуск.

Проверка на телефоне:

```text
http://127.0.0.1:8091/
```

Сервер слушает только loopback. Снаружи телефона он недоступен.

## Архитектура

```text
MainActivity (WebView)
        |
        | HTTP 127.0.0.1:8091
        v
PRoot (arm64) -> Debian rootfs -> Bun -> IISHKO server
        ^
        |
ServerService / ServerRunner / BootReceiver
```

- `MainActivity` распаковывает `assets/rootfs.dat`, чинит права, копирует PRoot-бинарники, запускает сервер и открывает WebView.
- `ServerRunner` держит PRoot-процесс, перезапускает его при падении и пишет лог.
- `ServerService` — foreground service с wake lock, чтобы сервер не убивался в фоне.
- `BootReceiver` и `RestartReceiver` поднимают сервис после перезагрузки/остановки.
- `TarExtractor` распаковывает gzip-tar с поддержкой symlink/hardlink.
- Внутри rootfs запускается:

```bash
/lib/ld-linux-aarch64.so.1 /usr/local/bin/bun \
  /root/iishko-app/apps/server/dist/index.mjs \
  --port 8091 --host 127.0.0.1 --no-browser
```

## Требования

- Android 6.0+ (API 23), ARM64/aarch64.
- ~1,5 ГБ свободного места на первом запуске.
- Разрешение на доступ к файлам (для старых Android).
- Root не нужен.
- Для стабильной работы — отключение оптимизации батареи и автозапуск.

## Сборка APK

Нужны:

- JDK 17.
- Android SDK: `platforms/android-23`, `build-tools/35.0.0`.
- `aapt`, `d8`, `zipalign`, `apksigner`, `zip`.
- `assets/rootfs.dat` — сжатый rootfs из раздела Releases.
- Keystore для подписи.

```bash
export ANDROID_HOME=/usr/lib/android-sdk
export ROOTFS_DAT=/path/to/rootfs.dat

export KEYSTORE=/path/to/release.keystore
export KS_PASS='...'
export KEY_ALIAS='...'
export KEY_PASS='...'

./scripts/build-apk.sh
```

Результат: `build/IISHKO-Selfhost.apk`.

Без `KEYSTORE` скрипт соберёт `build/IISHKO-Selfhost-unsigned.apk`; его нужно подписать отдельно.

### Ручная сборка

```bash
SDK=/usr/lib/android-sdk
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-23/android.jar

mkdir -p build/classes build/dex
javac -source 1.8 -target 1.8 -cp "$JAR" -d build/classes \
  $(find app/src -name '*.java')

$BT/d8 --min-api 23 --lib "$JAR" --output build/dex \
  $(find build/classes -name '*.class')

$BT/aapt package -f -M app/AndroidManifest.xml -I "$JAR" \
  -A assets -F build/app-unsigned.apk

(cd build && zip -q -j app-unsigned.apk dex/classes.dex)
$BT/zipalign -f 4 build/app-unsigned.apk build/app-aligned.apk
$BT/apksigner sign --ks "$KEYSTORE" --ks-pass env:KS_PASS \
  --key-pass env:KEY_PASS --ks-key-alias "$KEY_ALIAS" \
  --out build/IISHKO-Selfhost.apk build/app-aligned.apk
```

## Структура репозитория

```text
app/
  AndroidManifest.xml
  src/com/iishko/selfhost/
    MainActivity.java
    ServerRunner.java
    ServerService.java
    StartUtils.java
    TarExtractor.java
    BootReceiver.java
    RestartReceiver.java
assets/
  native/            # PRoot, loader, libtalloc, libandroid-shmem
scripts/
  build-apk.sh
CHECKSUMS.sha256
```

`assets/rootfs.dat` и APK в git не хранятся: они слишком большие и выкладываются в **Releases**.

## Проверенные артефакты

```text
IISHKO-Selfhost.apk   SHA-256 a073f1d556406ecc4e43008fc77021bc2cd0ac894969654e02c80158c90c44b1
rootfs.dat            SHA-256 db62ad3d87e03dc1f0b54c147d1a880b8948410561bda374ef4f9e5ed4c9e031
```

Версия APK: `2.0` (`versionCode 11`), package `com.iishko.selfhost`.

## Безопасность и ограничения

- Сервер слушает только `127.0.0.1:8091`; это локальный HTTP без TLS.
- WebView имеет доступ к локальному серверу; не открывай в нём недоверенные ссылки.
- Rootfs и IISHKO server имеют доступ к `/sdcard` через PRoot.
- Это не sandbox для недоверенного кода: внутри rootfs всё работает от root в PRoot.
- APK не обновляет rootfs автоматически; для обновления нужен новый APK/rootfs.
- Нет x86, нет iOS, нет Google Play-сборки.
- На устройстве в текущем окружении сборка не проверялась: нет ADB. Проверь установку и первый запуск вручную.

## Лицензии

Код Android-обёртки — MIT (см. `LICENSE`).

В `assets/native` и rootfs входят сторонние компоненты (PRoot, Debian, Bun, IISHKO и зависимости) со своими лицензиями. Перед публикацией APK/rootfs убедись, что у тебя есть право распространять IISHKO и все компоненты rootfs. См. `THIRD_PARTY_NOTICES.md`.
