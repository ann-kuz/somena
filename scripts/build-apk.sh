#!/usr/bin/env bash
# Единая сборка и публикация APK (сервер vkbot). Результат всегда лежит по одной ссылке:
#   http://77.239.99.15:8787/apk/somena.apk
# Любая сборка — только через этот скрипт, чтобы ссылка никогда не врала о версии.
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE="${GRADLE:-$(command -v gradle || echo /opt/gradle-8.11.1/bin/gradle)}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

echo "== Сборка debug-APK =="
"$GRADLE" --no-daemon --console=plain :app:assembleDebug

APK_IN="app/build/outputs/apk/debug/app-debug.apk"
APK_OUT="backend/apk/somena.apk"
mkdir -p "$(dirname "$APK_OUT")"
cp "$APK_IN" "$APK_OUT"

VERSION="$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | cut -d'"' -f2)"
echo "== Готово: $APK_OUT (v$VERSION, $(du -h "$APK_OUT" | cut -f1)) =="
echo "== Ссылка для телефонов: http://77.239.99.15:8787/apk/somena.apk =="
