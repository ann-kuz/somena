#!/usr/bin/env python3
"""
Спайк FatSecret (ADR-0003): проверяем, читается ли дневник питания РУ-аккаунта
через официальное API FatSecret (OAuth 1.0a three-legged), бесплатный уровень.

Что проверяем:
  1. Работает ли связка ключей + ru-аккаунта (главное белое пятно).
  2. Возвращает ли food_entries.get за сегодня и вчерашний день реальные калории/БЖУ.

Запуск: см. README.md рядом.
"""
import hashlib
import hmac
import os
import time
import urllib.parse
import urllib.request
from datetime import date, timedelta

API_BASE = "https://platform.fatsecret.com/rest/server.api"
REQUEST_TOKEN_URL = "https://authentication.fatsecret.com/oauth/request_token"
AUTHORIZE_URL = "https://authentication.fatsecret.com/oauth/authorize"
ACCESS_TOKEN_URL = "https://authentication.fatsecret.com/oauth/access_token"

# Читаем ключи из переменных окружения (не хардкодим секреты в файл)
KEY = os.environ.get("FS_KEY", "")
SECRET = os.environ.get("FS_SECRET", "")


def pct_encode(s: str) -> str:
    return urllib.parse.quote(s, safe="")


def sign(method: str, url: str, params: dict, token_secret: str = "") -> str:
    """OAuth 1.0a HMAC-SHA1 подпись (RFC 5849)."""
    normalized = "&".join(
        f"{pct_encode(k)}={pct_encode(v)}" for k, v in sorted(params.items())
    )
    base = "&".join([method.upper(), pct_encode(url), pct_encode(normalized)])
    key = f"{pct_encode(SECRET)}&{pct_encode(token_secret)}".encode()
    digest = hmac.new(key, base.encode(), hashlib.sha1).digest()
    import base64
    return base64.b64encode(digest).decode()


def oauth_params(token: str = "") -> dict:
    import uuid
    p = {
        "oauth_consumer_key": KEY,
        "oauth_nonce": uuid.uuid4().hex,
        "oauth_signature_method": "HMAC-SHA1",
        "oauth_timestamp": str(int(time.time())),
        "oauth_version": "1.0",
    }
    if token:
        p["oauth_token"] = token
    return p


def call(method: str, url: str, extra: dict, token: str = "", token_secret: str = "") -> str:
    params = oauth_params(token) | extra
    params["oauth_signature"] = sign(method, url, params, token_secret)
    data = urllib.parse.urlencode(params).encode()
    req = urllib.request.Request(url, data=data)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.read().decode()
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")[:500]
        raise SystemExit(f"HTTP {e.code} от {url}\nТело ответа: {body}")


def get_token_pair(url: str, extra: dict = {}, token: str = "", secret: str = "") -> tuple:
    body = call("POST", url, extra, token, secret)
    parsed = dict(urllib.parse.parse_qsl(body))
    return parsed["oauth_token"], parsed["oauth_token_secret"]


def food_day(oauth_token: str, oauth_secret: str, day: date) -> str:
    return call(
        "POST", API_BASE,
        {"method": "food_entries.get", "date": day.strftime("%Y-%m-%d"), "format": json_format},
        oauth_token, oauth_secret,
    )


json_format = "json"

if __name__ == "__main__":
    if not KEY or not SECRET:
        raise SystemExit("Сначала: export FS_KEY=... FS_SECRET=... (ключи из platform.fatsecret.com)")

    print("Шаг 1: request token…")
    # oauth_callback обязателен по спецификации OAuth 1.0a; "oob" = верификатор покажут на экране
    rt, rts = get_token_pair(REQUEST_TOKEN_URL, {"oauth_callback": "oob"})

    print("\nШаг 2: открой в браузере и разреши доступ своему АККАУНТУ FatSecret (ru):")
    print(f"{AUTHORIZE_URL}?oauth_token={rt}")
    verifier = input("\nВведи код подтверждения (oauth_verifier) со страницы: ").strip()

    print("\nШаг 3: access token…")
    at, ats = get_token_pair(ACCESS_TOKEN_URL, {"oauth_verifier": verifier}, rt, rts)
    print(f"access token: {at}\naccess secret: {ats}")
    print("Сохрани их — живут долго, повторять шаги 1–3 не нужно.")

    for label, day in [("вчера", date.today() - timedelta(days=1)), ("сегодня", date.today())]:
        print(f"\n=== Дневник за {label} ({day}) ===")
        try:
            print(food_day(at, ats, day))
        except Exception as e:
            print(f"ОШИБКА: {e}")
    print("\nЕсли выше calories/белки/жиры/углеводы — спайк УСПЕШЕН, ветку еды строим на API.")
    print("Если ошибка про регион/доступ — включаем план Б из ADR-0003 (Lifemum пишет еду в HC).")
