#!/usr/bin/env bash
# Единая сборка и публикация APK (сервер vkbot). Каждая сборка автоматически
# попадает на тестовую ссылку:
#   http://77.239.99.15:8787/apk/somena-test.apk
# Стабильная ссылка (http://77.239.99.15:8787/apk/somena.apk) обновляется только
# scripts/promote-apk.sh по явной просьбе владелицы (спека 0012).
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE="${GRADLE:-$(command -v gradle || echo /opt/gradle-8.11.1/bin/gradle)}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

echo "== Сборка debug-APK =="
"$GRADLE" --no-daemon --console=plain :app:assembleDebug

APK_IN="app/build/outputs/apk/debug/app-debug.apk"
APK_OUT="backend/apk/somena-test.apk"
MANIFEST="site/version.json"
mkdir -p "$(dirname "$APK_OUT")" "$(dirname "$MANIFEST")"
cp "$APK_IN" "$APK_OUT"

VERSION="$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | cut -d'"' -f2)"
python3 - "$MANIFEST" "$VERSION" <<'PY'
import datetime, json, pathlib, sys

manifest_path, version = pathlib.Path(sys.argv[1]), sys.argv[2]
data = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
data["test"] = {"version": version, "date": datetime.date.today().isoformat()}
manifest_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
PY

echo "== Готово: $APK_OUT (v$VERSION, $(du -h "$APK_OUT" | cut -f1)) =="
echo "== Тестовая ссылка: http://77.239.99.15:8787/apk/somena-test.apk =="
echo "== В стабильные - только по просьбе: scripts/promote-apk.sh =="
