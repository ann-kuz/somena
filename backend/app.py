"""Somena AI proxy (тикет 04): единственная роль Бэкенда — прокси к ИИ.

Принципы: ключ proxyapi живёт только в .env; пересылаемые данные не сохраняются;
в логи не попадают ни ключи, ни содержимое запросов.
"""

import logging
import os
import hmac
from pathlib import Path

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

load_dotenv(Path(__file__).parent / ".env")

PROXYAPI_BASE = os.environ.get("PROXYAPI_BASE", "https://api.proxyapi.ru/openai/v1")
PROXYAPI_KEY = os.environ.get("PROXYAPI_KEY", "")
MODEL_NAME = os.environ.get("MODEL_NAME", "gpt-4.1-mini")
APP_TOKEN = os.environ.get("APP_TOKEN", "")

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


@app.get("/health")
def health() -> dict:
    """Живость сервиса: без ключей, без внешних запросов, без авторизации."""
    return {
        "status": "ok",
        "model": MODEL_NAME,
        "configured": bool(PROXYAPI_KEY and APP_TOKEN),
    }


@app.post("/v1/chat")
async def chat(req: ChatRequest, authorization: str = Header(default="")) -> dict:
    if not APP_TOKEN or not PROXYAPI_KEY:
        raise HTTPException(status_code=503, detail="Сервис не настроен: нет ключей в .env")
    if not hmac.compare_digest(authorization, f"Bearer {APP_TOKEN}"):
        raise HTTPException(status_code=401, detail="Неверный токен приложения")

    payload = {
        "model": MODEL_NAME,
        "messages": ([{"role": "system", "content": req.system}] if req.system else [])
        + [m.model_dump() for m in req.messages],
        "max_completion_tokens": req.max_tokens,
    }
    async with httpx.AsyncClient(timeout=httpx.Timeout(120.0, connect=10.0)) as client:
        try:
            resp = await client.post(
                f"{PROXYAPI_BASE}/chat/completions",
                json=payload,
                headers={"Authorization": f"Bearer {PROXYAPI_KEY}"},
            )
        except httpx.HTTPError:
            log.warning("proxyapi unreachable")
            raise HTTPException(status_code=502, detail="ИИ-провайдер недоступен")

    if resp.status_code != 200:
        # Тело ошибки провайдера не логируем и не проксируем: там может быть что угодно.
        log.warning("proxyapi error status=%s", resp.status_code)
        raise HTTPException(status_code=502, detail=f"Ошибка ИИ-провайдера (HTTP {resp.status_code})")

    try:
        reply = resp.json()["choices"][0]["message"]["content"] or ""
    except (KeyError, IndexError, ValueError):
        log.warning("proxyapi unexpected response shape")
        raise HTTPException(status_code=502, detail="Неожиданный ответ ИИ-провайдера")

    return {"reply": reply, "model": MODEL_NAME}
