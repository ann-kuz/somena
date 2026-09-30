#!/usr/bin/env bash
# Перевод текущей тестовой сборки в стабильную: копирует тестовый APK на
# стабильную постоянную ссылку и фиксирует версию с датой в манифесте сайта.
# Запускать только по явной просьбе владелицы (спека 0012).
set -euo pipefail
cd "$(dirname "$0")/.."

APK_TEST="backend/apk/somena-test.apk"
APK_STABLE="backend/apk/somena.apk"
MANIFEST="site/version.json"

if [[ ! -f "$APK_TEST" ]]; then
    echo "Нет тестовой сборки: $APK_TEST (сначала scripts/build-apk.sh)" >&2
    exit 1
fi
if [[ ! -f "$MANIFEST" ]]; then
    echo "Нет манифеста версий: $MANIFEST" >&2
    exit 1
fi

cp "$APK_TEST" "$APK_STABLE"

python3 - "$MANIFEST" <<'PY'
import datetime, json, pathlib, sys

manifest = pathlib.Path(sys.argv[1])
data = json.loads(manifest.read_text())
test = data.get("test") or {"version": "unknown"}
data["stable"] = {"version": test["version"], "date": datetime.date.today().isoformat()}
manifest.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
stable = data["stable"]
print(f"Стабильная: v{stable['version']} от {stable['date']}")
PY

echo "== Стабильная ссылка обновлена: http://77.239.99.15:8787/apk/somena.apk =="
