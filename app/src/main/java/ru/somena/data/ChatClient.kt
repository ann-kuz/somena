package ru.somena.data

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.somena.core.DATA_ENTRY_SYSTEM_PROMPT
import ru.somena.core.IMPORT_SYSTEM_PROMPT
import ru.somena.core.MAX_ATTACHMENT_CHARS
import ru.somena.core.MED_IMPORT_SYSTEM_PROMPT

/** Ступени (спека 0004): одно место на весь пакет data. */
const val STEP_FAST = "fast"
const val STEP_MAX = "max"

/**
 * Транспорт Чата по данным: чистые данные, тестируются без Android.
 * Server - Бэкенд-прокси владелицы (ключи ИИ живут на сервере); Direct - свой
 * ИИ-сервис: адрес, ключ и модели Ступеней выбираются на телефоне, сервер не нужен.
 */
sealed interface ChatTransport {
    /** Имя транспорта в строках ошибок: «Бэкенд» или «ИИ-сервис». */
    val label: String

    data class Server(val url: String, val appToken: String) : ChatTransport {
        override val label get() = "Бэкенд"
    }

    /** Куда бить запрос Ступени: модель, адрес и протокол у каждой модели свой. */
    data class ModelTarget(
        val model: String,
        val baseUrl: String,
        val protocol: String = PROTOCOL_OPENAI,
    )

    /**
     * Прямой транспорт: у Ступеней свои цели. «Популярные API» берут адрес и протокол
     * из каталога (Claude живёт на /anthropic/v1, GPT - на OpenAI-совместимом адресе),
     * «Свой API» ставит обе Ступени на одну вручную заданную модель.
     */
    data class Direct(
        val apiKey: String,
        val fast: ModelTarget,
        val max: ModelTarget,
    ) : ChatTransport {
        override val label get() = "ИИ-сервис"

        fun targetFor(step: String?): ModelTarget = if (step == STEP_MAX) max else fast
    }
}

/**
 * Клиент ИИ (тикет 07, вложения - спека 0004): одна точка входа для двух транспортов.
 * [ChatTransport.Server] шлёт POST /v1/chat с историей диалога, системным промптом и
 * Ступенью (модель выбирает сервер). [ChatTransport.Direct] сам собирает запрос и шлёт
 * его прямо в ИИ-сервис Пользователя в формате модели: OpenAI-совместимом
 * (POST {адрес}/chat/completions), родном Anthropic (POST {адрес}/messages,
 * Claude на proxyapi) или родном Google (POST {адрес}/models/{модель}:generateContent,
 * Gemini на proxyapi): сервер не нужен.
 * Контекст данных идёт первым user-сообщением: у Бэкенда жёсткий лимит на system в
 * 4000 символов, а срез за 30 дней в него не помещается. askImport отправляет таблицу
 * Вложения в поле attachment (свой, более широкий лимит) Ступенью, выбранной в чате;
 * в прямом режиме вложение сворачивается в user-сообщение теми же правилами, что на
 * Бэкенде. О каждом запросе пишет две строки (запрос и исход) в [log] - это журнал на
 * экране «Настройки → Отладка»; токен и ключ в журнал не попадают никогда.
 */
class ChatClient(
    private val transport: ChatTransport,
    private val log: (String) -> Unit = {},
    /** Сколько ждать ответа в обычном чате: ответы там секунды. */
    private val chatReadTimeoutMs: Long = 90_000,
    /**
     * Сколько ждать Разбор таблицы: ответ провайдера на длинную таблицу занимает до
     * пары минут (инцидент 25.09: 86 с успешные, дольше - Бэкенд сам обрывает по
     * PROVIDER_TIMEOUT_S). Больше дедлайна Бэкенда, чтобы дожить до его ответа или
     * 502, а не отвалиться по своему таймауту раньше.
     */
    private val importReadTimeoutMs: Long = 170_000,
) {

    suspend fun ask(history: List<ChatMessage>, system: String, context: String, step: String): Result<String> =
        withContext(Dispatchers.IO) {
            exchange(request(history, system, context, step), chatReadTimeoutMs, describe = {
                "сообщений в истории: ${history.size}, ступень: $step"
            })
        }

    /**
     * Разбор таблицы: ответ - строгий JSON, его валидирует core (TableImport).
     * Вопрос Пользователя доходит до модели (спека 0007): уточняет, какие столбцы
     * какими показателями являются; контракт «строгий JSON» живёт только в системном
     * промпте, поэтому вольный текст его не ломает. Пустой текст - фиксированная фраза.
     * Ступень - выбранная в чате (спека 0015): Быстрая путала день и месяц в датах
     * выгрузок вроде Fitdays, поэтому сила ИИ здесь та же, что и в вопросах.
     */
    suspend fun askImport(attachment: String, question: String = "", step: String = STEP_FAST): Result<String> =
        withContext(Dispatchers.IO) {
            // Спека 0004: длиннее лимита - отказ, никакой молчаливой обрезки.
            if (attachment.length > MAX_ATTACHMENT_CHARS) {
                fail(
                    "Таблица слишком длинная (${attachment.length} симв.): разбей файл на части.",
                    "вложение ${attachment.length} симв. длиннее лимита",
                )
            } else {
                exchange(requestImport(attachment, question, step), importReadTimeoutMs, describe = {
                    "разбор таблицы, ${attachment.length} симв., ступень: $step"
                })
            }
        }

    /**
     * Разбор документа в запись Медкарты (спека 0010): текст - по каналу вложения,
     * картинки (base64 без префиксов) - отдельным полем, Бэкенд соберёт из них
     * multimodal-сообщения (ADR-0009). Ступень выбирает Пользователь, по умолчанию
     * Быстрая. Ответ - строгий JSON, его валидирует core (MedImport).
     */
    suspend fun askDocumentImport(
        attachment: String?,
        images: List<String> = emptyList(),
        question: String = "",
        step: String = STEP_FAST,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (attachment != null && attachment.length > MAX_ATTACHMENT_CHARS) {
            fail(
                "Документ слишком длинный (${attachment.length} симв.): пришли его картинками или частями.",
                "вложение документа ${attachment.length} симв. длиннее лимита",
            )
        } else {
            exchange(requestDocumentImport(attachment, images, question, step), importReadTimeoutMs, describe = {
                "разбор документа, вложение ${attachment?.length ?: 0} симв., картинок ${images.size}, ступень: $step"
            })
        }
    }

    /**
     * Внесение данных с пометки «Внести данные» (кнопка чата): короткая фраза Пользователя
     * превращается в строгий JSON того же формата, что у Разбора таблицы, - ответ валидирует
     * core (parseImportReply). Выделенный вызов с выделенным промптом, а не чатовая модель:
     * та на пометку отвечала «Записываю...» без блока (инцидент 29.09). Ответ маленький -
     * обычный чатовый таймаут, Ступень всегда Быстрая.
     */
    suspend fun askDataEntry(text: String): Result<String> = withContext(Dispatchers.IO) {
        exchange(
            WireRequest(
                messages = listOf(WireMessage(ChatMessage.USER, text.take(MAX_CONTENT))),
                system = DATA_ENTRY_SYSTEM_PROMPT,
                maxTokens = 2000,
                step = STEP_FAST,
            ),
            chatReadTimeoutMs,
            describe = { "внесение данных, ${text.length} симв., ступень: fast" },
        )
    }

    /**
     * Подпись Ступень→модель для селектора чата. У Бэкенда - из /health без авторизации
     * (подпись не врёт после смены модели), у своего ИИ-сервиса - выбранные модели с
     * телефона, сети не нужно вовсе.
     */
    fun steps(): Map<String, String>? = when (transport) {
        is ChatTransport.Direct ->
            mapOf(STEP_FAST to transport.fast.model, STEP_MAX to transport.max.model)
        is ChatTransport.Server -> try {
            val conn = (URL("${transport.url}/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            runCatching {
                val body = conn.inputStream.bufferedReader().readText()
                json.decodeFromString<WireHealth>(body).steps
                    ?.filterKeys { it == STEP_FAST || it == STEP_MAX }
                    ?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun exchange(req: WireRequest, readTimeoutMs: Long, describe: () -> String): Result<String> =
        when (transport) {
            is ChatTransport.Server -> postJson(
                "${transport.url}/v1/chat",
                listOf(
                    "Authorization" to "Bearer ${transport.appToken}",
                    "Content-Type" to "application/json; charset=utf-8",
                ),
                json.encodeToString(req),
                readTimeoutMs,
                describe,
                parse200 = { raw ->
                    val wire = json.decodeFromString<WireReply>(raw)
                    wire.reply to wire.model
                },
                httpError = { code, detail ->
                    when (code) {
                        401 -> "Токен приложения неверный. Нужна строка APP_TOKEN из backend/.env на сервере " +
                            "(это не ключ proxyapi): проверь Настройки → Чат и попробуй снова."
                        400 -> detail ?: "Бэкенд отклонил запрос (HTTP 400): обнови приложение и Бэкенд."
                        else -> "Бэкенд ответил ошибкой (HTTP $code): ${detail ?: "попробуй позже"}."
                    }
                },
            )
            is ChatTransport.Direct -> when (transport.targetFor(req.step).protocol) {
                PROTOCOL_ANTHROPIC -> anthropicExchange(transport, req, readTimeoutMs, describe)
                PROTOCOL_GEMINI -> geminiExchange(transport, req, readTimeoutMs, describe)
                else -> openAiExchange(transport, req, readTimeoutMs, describe)
            }
        }

    /** OpenAI-совместимый формат: POST {адрес}/chat/completions, ответ в choices. */
    private fun openAiExchange(
        transport: ChatTransport.Direct,
        req: WireRequest,
        readTimeoutMs: Long,
        describe: () -> String,
    ): Result<String> {
        val t = transport.targetFor(req.step)
        return postJson(
            "${t.baseUrl}/chat/completions",
            listOf(
                "Authorization" to "Bearer ${transport.apiKey}",
                "Content-Type" to "application/json; charset=utf-8",
            ),
            openAiPayload(t, req),
            readTimeoutMs,
            describe,
            parse200 = { raw ->
                val wire = json.decodeFromString<WireProviderReply>(raw)
                (wire.choices.firstOrNull()?.message?.content ?: "") to wire.model
            },
            httpError = ::directHttpError,
        )
    }

    /**
     * Родной формат Anthropic (Claude на proxyapi живёт на /anthropic/v1): POST
     * {адрес}/messages, ключ в заголовке x-api-key, ответ - массив блоков content.
     */
    private fun anthropicExchange(
        transport: ChatTransport.Direct,
        req: WireRequest,
        readTimeoutMs: Long,
        describe: () -> String,
    ): Result<String> {
        val t = transport.targetFor(req.step)
        return postJson(
            "${t.baseUrl}/messages",
            listOf(
                "x-api-key" to transport.apiKey,
                "anthropic-version" to "2023-06-01",
                "Content-Type" to "application/json; charset=utf-8",
            ),
            anthropicPayload(t, req),
            readTimeoutMs,
            describe,
            parse200 = { raw ->
                val wire = json.decodeFromString<WireAnthropicReply>(raw)
                (wire.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")) to wire.model
            },
            httpError = ::directHttpError,
        )
    }

    private fun directHttpError(code: Int, detail: String?): String = when (code) {
        401 -> "Ключ API неверный: проверь его в Настройках → Чат и попробуй снова."
        429 -> "Сервис ИИ ограничивает частоту запросов: подожди минуту и попробуй снова."
        else -> "Сервис ИИ ответил ошибкой (HTTP $code)${detail?.let { ": $it" } ?: ". Попробуй позже."}"
    }

    /**
     * Родной формат Google (Gemini на proxyapi живёт на /google/v1beta): POST
     * {адрес}/models/{модель}:generateContent, ключ в заголовке x-goog-api-key -
     * не в URL, чтобы Журнал его не увидел. Ответ собирается из
     * candidates[].content.parts[].
     */
    private fun geminiExchange(
        transport: ChatTransport.Direct,
        req: WireRequest,
        readTimeoutMs: Long,
        describe: () -> String,
    ): Result<String> {
        val t = transport.targetFor(req.step)
        return postJson(
            "${t.baseUrl.trimEnd('/')}/models/${t.model}:generateContent",
            listOf(
                "x-goog-api-key" to transport.apiKey,
                "Content-Type" to "application/json; charset=utf-8",
            ),
            geminiPayload(t, req),
            readTimeoutMs,
            describe,
            parse200 = { raw ->
                val wire = json.decodeFromString<WireGeminiReply>(raw)
                (wire.candidates.firstOrNull()?.content?.parts
                    ?.mapNotNull { it.text }?.joinToString("\n") ?: "") to wire.model
            },
            httpError = ::directHttpError,
        )
    }

    /**
     * Тело запроса к ИИ-сервису в OpenAI-формате: то, что Бэкенд собирает на своей
     * стороне, здесь собирает приложение. Системный промпт - первым сообщением,
     * Вложение - user-сообщением перед вопросом, картинки - multimodal-частями вопроса
     * (спека 0010, ADR-0009).
     */
    private fun openAiPayload(t: ChatTransport.ModelTarget, req: WireRequest): String {
        val messages: MutableList<Pair<String, JsonElement>> =
            req.messages.map { it.role to JsonPrimitive(it.content) }.toMutableList()
        req.attachment?.let { text ->
            val attachment: Pair<String, JsonElement> =
                ChatMessage.USER to JsonPrimitive("[Приложенная таблица]\n$text")
            val lastUser = messages.indexOfLast { it.first == ChatMessage.USER }
            if (lastUser >= 0) messages.add(lastUser, attachment) else messages.add(attachment)
        }
        if (!req.images.isNullOrEmpty()) {
            val lastUser = messages.indexOfLast { it.first == ChatMessage.USER }
            val parts = buildJsonArray {
                if (lastUser >= 0) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", messages[lastUser].second.jsonPrimitive.content)
                        }
                    )
                }
                req.images.forEach { img ->
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            put(
                                "image_url",
                                buildJsonObject {
                                    put("url", if (img.startsWith("data:")) img else "data:image/jpeg;base64,$img")
                                }
                            )
                        }
                    )
                }
            }
            val merged: Pair<String, JsonElement> = ChatMessage.USER to parts
            if (lastUser >= 0) messages[lastUser] = merged else messages.add(merged)
        }
        return buildJsonObject {
            put("model", t.model)
            put("max_completion_tokens", req.maxTokens)
            put("messages", buildJsonArray {
                req.system?.let { add(buildJsonObject { put("role", "system"); put("content", it) }) }
                messages.forEach { (role, content) ->
                    add(buildJsonObject { put("role", role); put("content", content) })
                }
            })
        }.toString()
    }

    /**
     * Тело запроса в родном формате Anthropic: системный промпт - отдельное поле
     * system, картинки - частями {"type":"image","source":{"type":"base64"}} внутри
     * пользовательского сообщения, вложение и порядок - те же правила, что у OpenAI-формата.
     */
    private fun anthropicPayload(t: ChatTransport.ModelTarget, req: WireRequest): String {
        val messages: MutableList<Pair<String, JsonElement>> =
            req.messages.map { it.role to JsonPrimitive(it.content) }.toMutableList()
        req.attachment?.let { text ->
            val attachment: Pair<String, JsonElement> =
                ChatMessage.USER to JsonPrimitive("[Приложенная таблица]\n$text")
            val lastUser = messages.indexOfLast { it.first == ChatMessage.USER }
            if (lastUser >= 0) messages.add(lastUser, attachment) else messages.add(attachment)
        }
        if (!req.images.isNullOrEmpty()) {
            val lastUser = messages.indexOfLast { it.first == ChatMessage.USER }
            val parts = buildJsonArray {
                if (lastUser >= 0) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", messages[lastUser].second.jsonPrimitive.content)
                        }
                    )
                }
                req.images.forEach { img ->
                    val dataUri = if (img.startsWith("data:")) img else "data:image/jpeg;base64,$img"
                    val mime = dataUri.substringAfter("data:").substringBefore(";")
                    add(
                        buildJsonObject {
                            put("type", "image")
                            put(
                                "source",
                                buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", mime)
                                    put("data", dataUri.substringAfter("base64,"))
                                }
                            )
                        }
                    )
                }
            }
            val merged: Pair<String, JsonElement> = ChatMessage.USER to parts
            if (lastUser >= 0) messages[lastUser] = merged else messages.add(merged)
        }
        return buildJsonObject {
            put("model", t.model)
            put("max_tokens", req.maxTokens)
            req.system?.let { put("system", it) }
            put("messages", buildJsonArray {
                messages.forEach { (role, content) ->
                    add(buildJsonObject { put("role", role); put("content", content) })
                }
            })
        }.toString()
    }

    /**
     * Тело запроса в родном формате Google: роль assistant называется model,
     * системный промпт - полем systemInstruction, картинки - частями inlineData;
     * Вложение и порядок сообщений - те же правила, что у двух других форматов.
     */
    private fun geminiPayload(t: ChatTransport.ModelTarget, req: WireRequest): String {
        val messages: MutableList<Pair<String, JsonElement>> =
            req.messages.map { (if (it.role == ChatMessage.ASSISTANT) "model" else "user") to JsonPrimitive(it.content) }
                .toMutableList()
        req.attachment?.let { text ->
            val attachment: Pair<String, JsonElement> =
                ChatMessage.USER to JsonPrimitive("[Приложенная таблица]\n$text")
            val lastUser = messages.indexOfLast { it.first == ChatMessage.USER }
            if (lastUser >= 0) messages.add(lastUser, attachment) else messages.add(attachment)
        }
        if (!req.images.isNullOrEmpty()) {
            val lastUser = messages.indexOfLast { it.first == ChatMessage.USER }
            val parts = buildJsonArray {
                if (lastUser >= 0) {
                    add(buildJsonObject { put("text", messages[lastUser].second.jsonPrimitive.content) })
                }
                req.images.forEach { img ->
                    val dataUri = if (img.startsWith("data:")) img else "data:image/jpeg;base64,$img"
                    val mime = dataUri.substringAfter("data:").substringBefore(";")
                    add(
                        buildJsonObject {
                            put(
                                "inlineData",
                                buildJsonObject {
                                    put("mimeType", mime)
                                    put("data", dataUri.substringAfter("base64,"))
                                }
                            )
                        }
                    )
                }
            }
            val merged: Pair<String, JsonElement> = ChatMessage.USER to parts
            if (lastUser >= 0) messages[lastUser] = merged else messages.add(merged)
        }
        return buildJsonObject {
            req.system?.let {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", it) }) })
                })
            }
            put("contents", buildJsonArray {
                messages.forEach { (role, content) ->
                    add(buildJsonObject {
                        put("role", role)
                        put(
                            "parts",
                            if (content is JsonArray) content else buildJsonArray {
                                add(buildJsonObject { put("text", content.jsonPrimitive.content) })
                            },
                        )
                    })
                }
            })
            put("generationConfig", buildJsonObject { put("maxOutputTokens", req.maxTokens) })
        }.toString()
    }

    /**
     * Общий POST обоих форматов: соединение, журнал «запрос/исход», одинаковый перевод
     * сетевых ошибок в слова. Ответ 200 разбирает [parse200] (пара «ответ/модель»),
     * прочие коды - [httpError] (текст по коду и телу ошибки; 502/503/504 у обоих
     * транспортов означают одно - ИИ недоступен). Заголовки передаются списком:
     * у формата OpenAI ключ в Authorization, у Anthropic - в x-api-key.
     */
    private fun postJson(
        url: String,
        headers: List<Pair<String, String>>,
        body: String,
        readTimeoutMs: Long,
        describe: () -> String,
        parse200: (String) -> Pair<String?, String?>,
        httpError: (Int, String?) -> String,
    ): Result<String> {
        val startedAt = System.currentTimeMillis()
        log("→ POST $url (${describe()})")
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = readTimeoutMs.toInt()
                doOutput = true
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            fun spent() = "${System.currentTimeMillis() - startedAt} мс"
            when (conn.responseCode) {
                200 -> {
                    val raw = runCatching { conn.inputStream.bufferedReader().readText() }.getOrNull()
                    val parsed = raw?.let { runCatching { parse200(it) }.getOrNull() }
                    val (reply, model) = parsed ?: (null to null)
                    if (reply.isNullOrBlank()) fail(
                        "ИИ вернул пустой ответ: попробуй ещё раз.",
                        "HTTP 200 за ${spent()}, пустой ответ",
                    )
                    else {
                        log("← HTTP 200 за ${spent()}, ответ ${reply.length} симв." +
                            (model?.let { ", модель $it" } ?: ""))
                        Result.success(reply)
                    }
                }
                502, 503, 504 -> fail(
                    "ИИ сейчас недоступен: попробуй ещё раз позже.",
                    "HTTP ${conn.responseCode} за ${spent()}",
                )
                else -> fail(
                    httpError(conn.responseCode, errorDetail(conn)),
                    "HTTP ${conn.responseCode} за ${spent()}",
                )
            }
        } catch (e: SSLException) {
            fail(
                if (transport is ChatTransport.Server) {
                    "Адрес, похоже, начинается с https, а сервер работает по http: " +
                        "убери букву s в адресе Бэкенда в Настройках → Чат."
                } else {
                    "Соединение с ИИ-сервисом не удалось: проверь адрес и сеть в Настройках → Чат."
                },
                e,
            )
        } catch (e: UnknownHostException) {
            fail(
                if (transport is ChatTransport.Server) {
                    "Адрес Бэкенда не разрешается: проверь его в Настройках → Чат."
                } else {
                    "Адрес ИИ-сервиса не разрешается: проверь адрес и сеть."
                },
                e,
            )
        } catch (e: java.net.ConnectException) {
            fail(
                if (transport is ChatTransport.Server) {
                    "Бэкенд отклонил соединение: проверь адрес Бэкенда в Настройках → Чат."
                } else {
                    "ИИ-сервис отклонил соединение: проверь адрес в Настройках → Чат."
                },
                e,
            )
        } catch (e: SocketTimeoutException) {
            fail("${transport.label} не отвечает: проверь ${if (transport is ChatTransport.Server) "адрес и " else ""}сеть, попробуй ещё раз.", e)
        } catch (e: Exception) {
            fail("Чат не удался: ${e.message ?: "ошибка сети"}.", e)
        }
    }

    /** Одно место для пары «строка журнала + сообщение пользователю». */
    private fun fail(uiText: String, logLine: String): Result<String> {
        log("← $logLine")
        return Result.failure(IllegalStateException(uiText))
    }

    private fun fail(uiText: String, e: Exception): Result<String> = fail(
        uiText,
        "ошибка ${e.javaClass.simpleName}: ${e.message ?: "без подробностей"}",
    )

    private fun request(history: List<ChatMessage>, system: String, context: String, step: String): WireRequest {
        val recent = history.takeLast(HISTORY_LIMIT)
            .map { WireMessage(it.role, it.content.take(MAX_CONTENT)) }
        val contextMsg = WireMessage("user", "$CONTEXT_MARK\n$context".take(MAX_CONTENT))
        // Контекст встаёт непосредственно перед свежим вопросом: данные и вопрос рядом.
        val messages = if (recent.isNotEmpty() && recent.last().role == ChatMessage.USER) {
            recent.dropLast(1) + contextMsg + recent.takeLast(1)
        } else {
            listOf(contextMsg) + recent
        }
        return WireRequest(messages = messages, system = system, maxTokens = 3000, step = step)
    }

    private fun requestImport(attachment: String, question: String, step: String): WireRequest = WireRequest(
        // Вопрос Пользователя - пользовательское сообщение разбора; обрезка общим
        // лимитом сообщений, как в обычном чате (спека 0007).
        messages = listOf(
            WireMessage(ChatMessage.USER, question.take(MAX_CONTENT).ifBlank { IMPORT_QUESTION })
        ),
        system = IMPORT_SYSTEM_PROMPT,
        // JSON разбора длинной таблицы - большой ответ: лимит почти на максимуме Бэкенда.
        maxTokens = 16000,
        step = step,
        attachment = attachment,
    )

    private fun requestDocumentImport(
        attachment: String?,
        images: List<String>,
        question: String,
        step: String,
    ): WireRequest = WireRequest(
        messages = listOf(
            WireMessage(ChatMessage.USER, question.take(MAX_CONTENT).ifBlank { DOCUMENT_IMPORT_QUESTION })
        ),
        system = MED_IMPORT_SYSTEM_PROMPT,
        maxTokens = 16000,
        step = step,
        attachment = attachment,
        images = images.takeIf { it.isNotEmpty() },
    )

    /**
     * Читаемый текст ошибки из тела ответа: Бэкенд отвечает {"detail": "..."},
     * провайдер прямого режима - {"error": {"message": "..."}}. Без тела или без
     * знакомых полей - null.
     */
    private fun errorDetail(conn: HttpURLConnection): String? = runCatching {
        val body = conn.errorStream?.bufferedReader()?.readText() ?: return null
        val obj = json.parseToJsonElement(body).jsonObject
        (obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: obj["detail"]?.jsonPrimitive?.contentOrNull)?.take(200)
    }.getOrNull()

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Сколько последних сообщений диалога уходит модели: компактность важнее глубины. */
        const val HISTORY_LIMIT = 12
        const val MAX_CONTENT = 20000
        const val CONTEXT_MARK = "[Данные пользователя на момент вопроса]"
        const val IMPORT_QUESTION = "Разбери приложенную таблицу и верни JSON."
        const val DOCUMENT_IMPORT_QUESTION = "Разбери приложенный медицинский документ и верни JSON."
    }
}

@Serializable
private data class WireMessage(val role: String, val content: String)

@Serializable
private data class WireRequest(
    val messages: List<WireMessage>,
    val system: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val step: String,
    val attachment: String? = null,
    /** Картинки Разбора документа (спека 0010, ADR-0009): base64 без префиксов. */
    val images: List<String>? = null,
)

@Serializable
private data class WireReply(val reply: String = "", val model: String? = null)

/** Ответ провайдера прямого режима (OpenAI-совместимый): текст в choices[0].message.content. */
@Serializable
private data class WireProviderReply(
    val model: String? = null,
    val choices: List<WireProviderChoice> = emptyList(),
)

@Serializable
private data class WireProviderChoice(val message: WireProviderMessage = WireProviderMessage())

@Serializable
private data class WireProviderMessage(val content: String? = null)

/** Ответ провайдера в формате Anthropic: текст собирается из блоков content[]. */
@Serializable
private data class WireAnthropicReply(
    val model: String? = null,
    val content: List<WireAnthropicBlock> = emptyList(),
)

@Serializable
private data class WireAnthropicBlock(val type: String? = null, val text: String? = null)

/** Ответ провайдера в формате Google: текст собирается из parts[] ответа-кандидата. */
@Serializable
private data class WireGeminiReply(
    @SerialName("modelVersion") val model: String? = null,
    val candidates: List<WireGeminiCandidate> = emptyList(),
)

@Serializable
private data class WireGeminiCandidate(val content: WireGeminiContent = WireGeminiContent())

@Serializable
private data class WireGeminiContent(val parts: List<WireGeminiPart> = emptyList())

@Serializable
private data class WireGeminiPart(val text: String? = null)

@Serializable
private data class WireHealth(val steps: Map<String, String>? = null)
