"""Somena AI proxy (тикет 04): единственная роль Бэкенда — прокси к ИИ.

Принципы: ключ proxyapi живёт только в .env; пересылаемые данные не сохраняются;
в логи не попадают ни ключи, ни содержимое запросов.
"""

import asyncio
import logging
import os
import hmac
import time
from pathlib import Path

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

load_dotenv(Path(__file__).parent / ".env")

PROXYAPI_BASE = os.environ.get("PROXYAPI_BASE", "https://api.proxyapi.ru/openai/v1")
PROXYAPI_KEY = os.environ.get("PROXYAPI_KEY", "")
# Белый список Ступень→модель (спека 0004): клиент передаёт Ступень, а не имя модели,
# поэтому смена модели не требует обновления приложения. Неизвестная Ступень - 400.
STEP_MODELS = {
    "fast": os.environ.get("MODEL_FAST", "gpt-4.1-mini"),
    "max": os.environ.get("MODEL_MAX", "gpt-5.1"),
}
APP_TOKEN = os.environ.get("APP_TOKEN", "")
# Суммарный дедлайн вызова провайдера: read-таймаут httpx порционный (между чтениями),
# а провайдер бывает «медленный, но живой» - тело капает и вечно держит Бэкенд в тишине
# (инцидент 25.09: клиент отвалился по своему таймауту, Бэкенд так и не ответил).
# Дедлайн гарантирует клиенту ответ Бэкенда за конечное время; приложение на пути
# разбора таблицы ждёт дольше (IMPORT_READ_TIMEOUT_MS в ChatClient).
PROVIDER_TIMEOUT_S = float(os.environ.get("PROVIDER_TIMEOUT_S", "150"))
# Единая точка раздачи APK: сюда его кладёт scripts/build-apk.sh (см. README «Скачать приложение»).
APK_PATH = Path(os.environ.get("APK_PATH", str(Path(__file__).parent / "apk" / "somena.apk")))

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
log = logging.getLogger("somena-ai")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


class ChatMessage(BaseModel):
    role: str = Field(pattern="^(user|assistant)$")
    content: str = Field(max_length=20000)


class ChatRequest(BaseModel):
    messages: list[ChatMessage] = Field(min_length=1)
    system: str | None = Field(default=None, max_length=4000)
    max_tokens: int = Field(default=2000, ge=1, le=16000)
    step: str | None = Field(default=None)
    # Вложение (спека 0004): текст таблицы отдельным полем, лимит шире обычных сообщений.
    attachment: str | None = Field(default=None, max_length=60000)


@app.get("/health")
def health() -> dict:
    """Живость сервиса: без ключей, без внешних запросов, без авторизации."""
    return {
        "status": "ok",
        "steps": STEP_MODELS,
        "configured": bool(PROXYAPI_KEY and APP_TOKEN),
    }


@app.get("/apk/somena.apk")
def apk() -> FileResponse:
    """Раздача приложения: одна постоянная ссылка для владелицы и друзей (без секретов внутри)."""
    if not APK_PATH.is_file():
        raise HTTPException(status_code=404, detail="APK ещё не собран: запусти scripts/build-apk.sh на сервере")
    return FileResponse(
        APK_PATH,
        media_type="application/vnd.android.package-archive",
        filename="somena.apk",
    )


@app.post("/v1/chat")
async def chat(req: ChatRequest, authorization: str = Header(default="")) -> dict:
    if not APP_TOKEN or not PROXYAPI_KEY:
        raise HTTPException(status_code=503, detail="Сервис не настроен: нет ключей в .env")
    if not hmac.compare_digest(authorization, f"Bearer {APP_TOKEN}"):
        raise HTTPException(status_code=401, detail="Неверный токен приложения")

    model = STEP_MODELS.get(req.step or "fast")
    if model is None:
        raise HTTPException(status_code=400, detail=f"Неизвестная ступень: {req.step}")

    messages = [m.model_dump() for m in req.messages]
    if req.attachment:
        # Таблица встаёт непосредственно перед вопросом: как контекст данных в обычном чате.
        attachment = {"role": "user", "content": "[Приложенная таблица]\n" + req.attachment}
        if messages and messages[-1]["role"] == "user":
            messages = messages[:-1] + [attachment, messages[-1]]
        else:
            messages.append(attachment)

    payload = {
        "model": model,
        "messages": ([{"role": "system", "content": req.system}] if req.system else [])
        + messages,
        "max_completion_tokens": req.max_tokens,
    }
    async with httpx.AsyncClient(timeout=httpx.Timeout(120.0, connect=10.0)) as client:
        try:
            started = time.monotonic()
            resp = await asyncio.wait_for(
                client.post(
                    f"{PROXYAPI_BASE}/chat/completions",
                    json=payload,
                    headers={"Authorization": f"Bearer {PROXYAPI_KEY}"},
                ),
                timeout=PROVIDER_TIMEOUT_S,
            )
        except asyncio.TimeoutError:
            log.warning("proxyapi exceeded total deadline of %ss", PROVIDER_TIMEOUT_S)
            raise HTTPException(status_code=502, detail="ИИ-провайдер не успел ответить")
        except httpx.HTTPError:
            log.warning("proxyapi unreachable")
            raise HTTPException(status_code=502, detail="ИИ-провайдер недоступен")
    log.info("proxyapi answered in %.1fs", time.monotonic() - started)

    if resp.status_code != 200:
        # Тело ошибки провайдера не логируем и не проксируем: там может быть что угодно.
        log.warning("proxyapi error status=%s", resp.status_code)
        raise HTTPException(status_code=502, detail=f"Ошибка ИИ-провайдера (HTTP {resp.status_code})")

    try:
        data = resp.json()
        reply = data["choices"][0]["message"]["content"] or ""
        # Фактическая модель отвечает - имя из ответа провайдера, если он его прислал.
        model = data.get("model") or model
    except (KeyError, IndexError, ValueError):
        log.warning("proxyapi unexpected response shape")
        raise HTTPException(status_code=502, detail="Неожиданный ответ ИИ-провайдера")

    return {"reply": reply, "model": model}
