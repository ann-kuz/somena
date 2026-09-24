# Somena

Личный трекер здоровья: шаги/сон/пульс (Mi Band 10 → Mi Fitness → Health Connect),
вес и состав тела (Fitdays → Health Connect), еда дневными итогами (FatSecret → бэкенд),
самочувствие (3 шкалы + заметка), ИИ-чат по данным (proxyapi, ключи на бэкенде).

- Словарь проекта: [CONTEXT.md](CONTEXT.md)
- Решения: [docs/adr/](docs/adr/) — Health Connect как единственная точка чтения (0001),
  локальные данные без аккаунтов (0002), еда из FatSecret через бэкенд (0003).

## Сборка (сервер vkbot, 1 ядро / 2 ГБ + swap)

```
export ANDROID_HOME=/opt/android-sdk
/opt/gradle-8.11.1/bin/gradle --no-daemon -p /root/somena assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Первая сборка медленная (зависимости + swap). `gradle.properties` уже ограничивает память,
демон выключен намеренно — не включать без причины, сервер маленький.

## Спайк FatSecret

`spike/fatsecret/` — проверка доступности дневника ru-аккаунта через официальный API.
Проводится до строительства ветки еды (см. ADR-0003).
