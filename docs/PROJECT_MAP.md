# Карта проекта Somena

Структура репозитория и модулей: по какой теме какие файлы править. Для точечных
правок — найди тему здесь, открой названные файлы, не читай код целиком. Термины —
из словаря домена ([CONTEXT.md](../CONTEXT.md)).

Волатильные факты (версия приложения, версия базы, имена моделей, лимиты) тут не
хранятся — читай их прямо из `app/build.gradle.kts`,
`app/src/main/java/ru/somena/data/SliceDb.kt` и `backend/app.py`.

## Поддержание карты

Правка, которая добавляет, удаляет или перемещает файл, меняет экран, маршрут
Бэкенда или назначение модуля, обновляет эту карту тем же коммитом (правило
закреплено в AGENTS.md). Что обновить: строку файла в своём разделе, строку в
«Маршрутах правок», при новой фиче — её спеку в разделе «Документы».

## Контур

| Часть | Путь | Роль |
|---|---|---|
| Приложение | `app/` | Android (Kotlin + Compose), единственный Gradle-модуль `:app`; данные только на телефоне |
| Бэкенд | `backend/app.py` | FastAPI: прокси к ИИ + раздача APK и Лендинга; после правки `systemctl restart somena-ai` |
| Лендинг | `site/` | статическая страница с `version.json` |
| Поддомены | `site-sub/` | страницы info. («Настройка») и items. («Разработчикам»); `style.css`/`i18n.js` — копии из `site/` |
| Сборка | `scripts/` | `build-apk.sh` (каждая сборка → тестовая ссылка), `promote-apk.sh` (стабильная) |
| CI | `.github/workflows/` | `release.yml` — релиз APK по тегу `v*`; `pages.yml` — зеркало Лендинга из `site/` |
| Спайк | `spike/fatsecret/` | проверка FatSecret API (запасной план еды, ADR-0003) |
| Документы | `CONTEXT.md`, `docs/` | словарь домена, ADR, спеки |
| Тикеты | `.scratch/<фича>/issues/NN-слаг.md` | трекер фич, в git не входит |

## Приложение

Три слоя в `app/src/main/java/ru/somena/`:

- **`core/`** — чистая логика без Android-зависимостей: домен, расчёты, промпты
  ИИ, ридеры форматов. Только её можно юнит-тестировать.
- **`data/`** — внешний мир: база SQLite, SharedPreferences, Health Connect, сеть.
- **корень + `ui/`** — Compose-экраны и дизайн-система; экраны не считают сами,
  а зовут core и читают data.

Пары с одинаковым именем (`AppLog`): в `core/` — формат и логика, в `data/` —
Android-обёртка. Так же зеркалятся тесты.

### core/ — чистая логика

Срез и агрегация:

- `DailyAggregator.kt` — записи Источников → `DaySlice` (Дневной срез) через
  `buildSlice`; дедупликация пересечений `dedupeSteps` и `dedupeSleep`
- `ImportWindow.kt` — окно чтения Health Connect: неделя долёживания, начальные
  14 дней
- `Sources.kt` — приоритет Источников: `applySourcePriority` (за день берётся
  первый в порядке), `mergeSources` (ADR-0010)
- `LatestValues.kt` — Последние известные значения для «Сегодня», суточные метрики
- `Metrics.kt` — `AiMetric` (все метрики с ключами), `metricSeries` — серии
  графиков, Дефицит
- `StepsBurn.kt` — «Расход от шагов»: kcal по Mifflin-St Jeor, `withStepsBurn`
- `EatenFloor.kt` — «Порог съеденного» (спека 0019): `withEatenFloor` — день
  с «Съедено» ниже порога считается днём без еды (калории и БЖУ скрываются)

Чат по данным:

- `ChatContext.kt` — `CHAT_SYSTEM_PROMPT` и `buildChatContext`: текстовый контекст
  (Дневные срезы, Профиль, цикл, сводка Медкарты)
- `ChatEntry.kt` — ввод данных словами: маркер «Внести данные», `parseAiDataEntries`
- `AiChart.kt` — график из ответа ИИ: `parseAiCharts` (```chart-блоки)
- `SentenceCase.kt` — автозаглавие предложений в поле ввода чата

Разбор таблицы и ручной ввод:

- `TableImport.kt` — промпт Разбора таблицы, `parseImportReply` → `ImportPreview`
  с валидацией диапазонов и дат
- `ManualEntry.kt` — ручной ввод: `ManualMetric` (категории), превью → `ImportPreview`

Медкарта:

- `MedCard.kt` — домен: `MedKind` (Анализ/Обследование/Протокол), `MedRecord`,
  `MedValidator`
- `MedImport.kt` — Разбор документа: промпт, `parseMedReply` → черновик записи
  с предупреждениями
- `MedFiles.kt` — индекс Хранилища и ленивое дерево файлов: `MedFileIndex`, `treeRows`

Прочее:

- `Cycle.kt` — цикл целиком: `buildPeriods`, `predictCycle`, `cyclePhase`, `cycleStats`
- `Wellbeing.kt` — `Wellbeing`: три шкалы 0–10, заметка, слот первой/второй отметки
- `Profile.kt` — `Profile` (пол, рост, дата рождения, цель, ручной базовый расход),
  валидация
- `Charts.kt` — математика графиков: `weightTrend`, `lastDays`, `PanAccumulator`,
  `clampWindowEnd`
- `CardLayout.kt` — раскладка: `TodayPlate` (плашки «Сегодня»), `ChartCard`
  (графики), порядок и скрытость
- `AppLog.kt` — `AppLogCore`: формат строк Журнала «epoch|тег|сообщение», трим по дням

Мини-ридеры форматов (свои, без библиотек):

- `XlsxReader.kt` — xlsx (zip+XML) и диспетчер таблиц `decodeTableBytes`
- `XlsReader.kt` — старый xls: OLE2-контейнер + BIFF8 (спека 0016)
- `DocxReader.kt`, `DocReader.kt` — docx и старый .doc (OLE2)
- `TextDocReaders.kt` — odt, fb2, html, epub, rtf
- `TableFiles.kt` — текст таблиц: UTF-8 иначе cp1251, лимиты вложений

### data/ — хранение и внешний мир

- `SliceDb.kt` — вся база `somena.db`: голый `SQLiteOpenHelper`, **не Room**.
  Таблицы: `day_slices` (срез JSON-ом в колонке), `wellbeing`, `chat_messages`,
  `cycle_days`, `med_records`. Миграции вручную в `onUpgrade` — при смене схемы
  поднимай версию. Центральный класс, его используют все экраны
- `HcImporter.kt` — главный импорт Health Connect: `importRecent` — окно из
  `ImportWindow`, чтение всех типов записей, приоритет Источников, сборка
  Дневного среза, `upsert`
- `HcSourceScan.kt` — какие пакеты писали в Health Connect за окно и когда
  писали последний раз (Настройки → Данные)
- `ChatClient.kt` — ИИ-транспорт на HttpURLConnection: Server (Бэкенд `/v1/chat`
  по Ступени) и Direct («Свой ИИ-сервис»: OpenAI/Claude/Gemini), сборка тел,
  картинки, переводы ошибок на русский
- `ChatSettings.kt` — настройки Чата в prefs: режимы «Популярные API»/«Свой API»,
  `transport()`
- `ProxyModels.kt` — каталог моделей «Популярных API» с ценами
- `MedStorage.kt` — Хранилище через SAF: выбранная папка, ленивое дерево,
  копирование и открытие файлов
- `MedDocReader.kt` — развилка чтения Вложения: pdf-текст, иначе рендер страниц,
  картинка, текстовые форматы (ADR-0009 «текст прежде зрения»)
- `PdfText.kt`, `PdfPages.kt` — текстовый слой pdf (pdfbox) и рендер страниц/фото
  в JPEG для зрения
- `ProfileStore.kt`, `SourceStore.kt`, `StepsBurnStore.kt`, `EatenFloorStore.kt`,
  `CardLayoutStore.kt` — prefs-обёртки Профиля, приоритета Источников, «Расхода
  от шагов», «Порога съеденного», раскладки плашек и графиков
- `AppLog.kt` — Журнал в SharedPreferences: теги CHAT/DB/HC/ERROR, обёртка над
  `AppLogCore`
- `WellbeingReminder.kt` — вечернее напоминание отметить Самочувствие (WorkManager)

### Экраны (корень ru/somena)

- `MainActivity.kt` — единственная Activity: нижний бар с 5 вкладками (Сегодня,
  Графики, Чат, Медкарта, Настройки), онбординг, фулскрин-слой Календаря цикла,
  подэкраны Настроек; `ProfileSection`; `refresh()` зовёт `HcImporter`.
  ViewModel'ей нет — состояние в `remember`, данные перечитываются из базы
- `TodayUi.kt` — инструментарий плашек «Сегодня»: меню плашки, ручной ввод с
  плашки через Предпросмотр, перестановка drag-n-drop, скрытые плашки
- `ChartsUi.kt` — экран «Графики»: окна 7/31/всё, тап по точке, самописный
  `LineChart` на Canvas
- `ChatUi.kt` — самый большой экран: история и Ступени Чата по данным, Вложения
  (таблица → Разбор, документ → Разбор документа, фраза → ввод данных), всё через
  Предпросмотр; настройки Чата; ручной ввод без ИИ
- `MedCardUi.kt` — Медкарта: список с поиском и фильтром, редактор записей трёх
  видов, файлы Хранилища с Разбором
- `CycleUi.kt` — карточка цикла на «Сегодня» и экран Календаря цикла с Прогнозом
- `WellbeingUi.kt` — плашка и редактор Самочувствия (слайдеры, заметка, слот, дата)
- `SourcesUi.kt` — Настройки → Данные: список показателей Источников со сводкой
  «кто пишет» и экран показателя (иконки приложений, свежесть «Последняя
  запись», перетаскивание + стрелки; маршрут `sourceGroupPage`), переключатель
  «Расход от шагов», поле «Порог съеденного»
- `SettingsUi.kt` — каркас Настроек (Профиль/Данные/Чат/Отладка), Журнал,
  диагностика Health Connect
- `HealthProbe.kt` — отладочный зонд Health Connect: текстовый отчёт, кто какие
  записи писал
- `Onboarding.kt` — каталог Источников с инструкциями, проверка онбординга;
  `OnboardingScreen.kt` — экран онбординга

### ui/ — дизайн-система

- `Theme.kt` — палитра Темы-туманность, `SomenaTheme` (всегда тёмная),
  `NebulaBackground`
- `Components.kt` — общие компоненты: `GlassCard`, `MetricCard` (свежая/устаревшая
  плашка), `GlowButton`, `SomenaNavBar`, `ImportPreviewCard` (общий Предпросмотр)
- `Icons.kt` — самодельные векторные иконки, включая иконки показателей
  Источников (material-icons-extended не подключён)
- `DragHold.kt` — общий жест перестановки удержанием: перенос плашек «Сегодня»
  и порядок Источников на экране показателя

### Тесты и прочее в app/

- `app/src/test/java/ru/somena/{core,data}/` — по файлу на модуль, имя зеркалит
  (`XlsReaderTest` ↔ `core/XlsReader.kt`); только логика без Android.
  Прогон: `gradle :app:testDebugUnitTest`
- `app/build.gradle.kts` — версия (фича — минор, правка — патч); зависимости:
  Compose, health-connect, WorkManager, kotlinx-serialization, pdfbox.
  Сеть, база и ридеры форматов — самописные (нет Retrofit, Room, Apache POI)
- `AndroidManifest.xml` — разрешения Health Connect READ_*, POST_NOTIFICATIONS,
  INTERNET; одна Activity
- `res/xml/network_security_config.xml` — cleartext только для старого адреса
  77.239.99.15

## Бэкенд

`backend/app.py` — единственный файл FastAPI. Маршруты: `GET /health` (живость),
`GET /apk/somena.apk` и `/apk/somena-test.apk` (раздача), `POST /v1/chat`
(прокси к proxyapi, Ступень fast/max, Bearer `APP_TOKEN`), `POST /feedback`
(багрепорты Лендинга: файлы + письмо). Корень — статика Лендинга (`../site`).
Ключи только в `backend/.env` (шаблон `.env.example`). Подробности: systemd-юнит
`somena-ai`, nginx-правила, `/lang.js` и немецкий язык по IP —
[backend/README.md](../backend/README.md).

## Лендинг и поддомены

- `site/index.html` — Лендинг: скачивание, фичи, форма багрепорта; словари ru/de
  инлайн
- `site/i18n.js` — выбор языка: печенье → подсказка сервера `/lang.js` → язык
  браузера → ru
- `site/style.css` — Тема-туманность для веба; та же копия в `site-sub/`,
  обновлять синхронно
- `site/version.json` — манифест каналов stable/test, обновляют скрипты сборки;
  правку коммитить
- `site-sub/setup/`, `site-sub/fork/` — страницы info. и items.; деплой
  копированием в `/var/www/somena`

## Маршруты правок: задача → файлы

| Хочу… | Где править |
|---|---|
| Новая метрика, плашка или график | `core/CardLayout.kt` (`TodayPlate`, `ChartCard`), `core/Metrics.kt`, `core/DailyAggregator.kt` (`DaySlice`), `data/SliceDb.kt` (схема), `TodayUi.kt` / `ChartsUi.kt` |
| Контекст или промпт Чата | `core/ChatContext.kt` (контекст), `core/ChatEntry.kt` (ввод словами), `core/TableImport.kt` (Разбор таблицы), `core/MedImport.kt` (Разбор документа) |
| Запросы к ИИ, ошибки, форматы | `data/ChatClient.kt`; серверная сторона — `backend/app.py` + рестарт `somena-ai` |
| Чтение Health Connect | `data/HcImporter.kt`, `core/ImportWindow.kt` (окно), `core/Sources.kt` (приоритет), `core/DailyAggregator.kt` (агрегация, дедупликация) |
| Новое поле в базе | `data/SliceDb.kt` (поднять версию, миграция в `onUpgrade`), `core/DailyAggregator.kt` (`DaySlice`), читатели поля |
| Новый формат Вложения | таблица — `core/XlsxReader.kt` (`decodeTableBytes`); меддокумент — `data/MedDocReader.kt` + мини-ридеры в `core/` |
| Дизайн, цвета, компоненты | `ui/Theme.kt`, `ui/Components.kt` по спеке 0002; цвета метрик — Правило цветов метрик в CONTEXT.md |
| Новая секция Настроек | `SettingsUi.kt` (каркас) + prefs-обёртка в `data/` |
| Уведомления, напоминания | `data/WellbeingReminder.kt` |
| Цикл и Прогноз | `core/Cycle.kt` + `CycleUi.kt` |
| Самочувствие | `core/Wellbeing.kt`, `WellbeingUi.kt`, таблица `wellbeing` в `data/SliceDb.kt` |
| Лендинг | `site/index.html`; общие `style.css`/`i18n.js` — копию в `site-sub/` |
| Версия и сборка | `app/build.gradle.kts`, затем `scripts/build-apk.sh`; коммитить `site/version.json` |

## Документы

- [CONTEXT.md](../CONTEXT.md) — словарь домена, обязателен к употреблению
- `docs/adr/` — решения: 0001 Health Connect единственная точка чтения, 0002
  данные локально без аккаунтов, 0003/0004 еда дневными итогами из FatSecret
  через Health Connect, 0005 цикл локально, 0006/0007 Разбор таблицы мимо HC и
  задним числом, 0008 оригиналы Медкарты в Хранилище, 0009 текст прежде зрения,
  0010 выбор Источника
- `docs/spec/` — спеки по номерам: 0001 MVP, 0002 дизайн-система, 0003 цикл,
  0004 Ступени и вложения-таблицы, 0005 дефицит и графики калорий, 0006 Разбор
  таблицы задним числом, 0007 вопрос Пользователя доходит до ИИ, 0008 Самочувствие
  дважды в день, 0009 Дефицит с BMR, 0010 Медкарта, 0011 раскладка плашек и
  графиков, 0012 Лендинг, 0013 багрепорты и форматы, 0014 пульс/расход/приоритет
  Источников, 0015 Разбор таблицы выбранной Ступенью, 0016 читатель xls,
  0017 Расход от шагов, 0018 экран Источников списком показателей,
  0019 Порог съеденного
- `.scratch/<фича>/issues/NN-слаг.md` — тикеты; формат и статусы — в AGENTS.md
