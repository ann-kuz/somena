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
import ru.somena.core.IMPORT_SYSTEM_PROMPT
import ru.somena.core.MAX_ATTACHMENT_CHARS

/** Ступени (спека 0004): одно место на весь пакет data. */
const val STEP_FAST = "fast"
const val STEP_MAX = "max"

/** Адрес и токен запросов к Бэкенду: чистые данные, тестируются без Android. */
data class ChatEndpoint(val url: String, val token: String)

/**
 * Клиент Бэкенда-прокси (тикет 07, вложения - спека 0004): POST /v1/chat с историей
 * диалога, системным промптом и Ступенью модели. Контекст данных идёт первым user-
 * сообщением: у Бэкенда жёсткий лимит на system в 4000 символов, а срез за 30 дней
 * в него не помещается. askImport отправляет таблицу Вложения в поле attachment
 * (свой, более широкий лимит) и всегда на Быстрой Ступени.
 * О каждом запросе пишет две строки (запрос и исход) в [log] - это журнал на вкладке
 * «Отладка HC»; токен в журнал не попадает никогда.
 */
class ChatClient(
    private val endpoint: ChatEndpoint,
    private val log: (String) -> Unit = {},
    /** Сколько ждать ответа в обычном чате: ответы Бэкенда там секунды. */
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

    /** Разбор таблицы: ответ - строгий JSON, его валидирует core (TableImport). */
    suspend fun askImport(attachment: String): Result<String> = withContext(Dispatchers.IO) {
        // Спека 0004: длиннее лимита - отказ, никакой молчаливой обрезки.
        if (attachment.length > MAX_ATTACHMENT_CHARS) {
            fail(
                "Таблица слишком длинная (${attachment.length} симв.): разбей файл на части.",
                "вложение ${attachment.length} симв. длиннее лимита",
            )
        } else {
            exchange(requestImport(attachment), importReadTimeoutMs, describe = {
                "разбор таблицы, ${attachment.length} симв., ступень: fast"
            })
        }
    }

    /** Ступень→модель с Бэкенда (/health, без авторизации): подпись селектора не врёт после смены модели. */
    fun steps(): Map<String, String>? = try {
        val conn = (URL("${endpoint.url}/health").openConnection() as HttpURLConnection).apply {
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

    private fun exchange(req: WireRequest, readTimeoutMs: Long, describe: () -> String): Result<String> {
        val startedAt = System.currentTimeMillis()
        log("→ POST ${endpoint.url}/v1/chat (${describe()})")
        return try {
            val conn = (URL("${endpoint.url}/v1/chat").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = readTimeoutMs.toInt()
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${endpoint.token}")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use {
                it.write(json.encodeToString(req).toByteArray())
            }
            fun spent() = "${System.currentTimeMillis() - startedAt} мс"
            when (conn.responseCode) {
                200 -> {
                    val body = runCatching {
                        val wire = json.decodeFromString<WireReply>(conn.inputStream.bufferedReader().readText())
                        wire.reply to wire.model
                    }.getOrNull()
                    val (reply, model) = body ?: ("" to null)
                    if (reply.isNullOrBlank()) fail(
                        "Бэкенд вернул пустой ответ: попробуй ещё раз.",
                        "HTTP 200 за ${spent()}, пустой ответ",
                    )
                    else {
                        log("← HTTP 200 за ${spent()}, ответ ${reply.length} симв." +
                            (model?.let { ", модель $it" } ?: ""))
                        Result.success(reply)
                    }
                }
                401 -> fail(
                    "Токен приложения неверный. Нужна строка APP_TOKEN из backend/.env на сервере " +
                        "(это не ключ proxyapi): проверь вкладку «Ещё» и попробуй снова.",
                    "HTTP 401 (неверный токен) за ${spent()}",
                )
                400 -> fail(
                    backendDetail(conn) ?: "Бэкенд отклонил запрос (HTTP 400): обнови приложение и Бэкенд.",
                    "HTTP 400 за ${spent()}",
                )
                502, 503 -> fail(
                    "ИИ сейчас недоступен: попробуй ещё раз позже.",
                    "HTTP ${conn.responseCode} за ${spent()}",
                )
                else -> fail(
                    "Бэкенд ответил ошибкой (HTTP ${conn.responseCode}). Попробуй позже.",
                    "HTTP ${conn.responseCode} за ${spent()}",
                )
            }
        } catch (e: SSLException) {
            fail(
                "Адрес, похоже, начинается с https, а сервер работает по http: " +
                    "убери букву s в адресе Бэкенда на вкладке «Ещё».",
                e,
            )
        } catch (e: UnknownHostException) {
            fail("Адрес Бэкенда не разрешается: проверь его на вкладке «Ещё».", e)
        } catch (e: java.net.ConnectException) {
            fail(
                "Бэкенд отклонил соединение: проверь адрес Бэкенда на вкладке «Ещё».",
                e,
            )
        } catch (e: SocketTimeoutException) {
            fail("Бэкенд не отвечает: проверь адрес и сеть, попробуй ещё раз.", e)
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

    private fun requestImport(attachment: String): WireRequest = WireRequest(
        messages = listOf(WireMessage(ChatMessage.USER, IMPORT_QUESTION)),
        system = IMPORT_SYSTEM_PROMPT,
        // JSON разбора длинной таблицы - большой ответ: лимит почти на максимуме Бэкенда.
        maxTokens = 16000,
        step = STEP_FAST,
        attachment = attachment,
    )

    /** Читаемый текст ошибки из тела Бэкенда: {"detail": "..."}; без тела - null. */
    private fun backendDetail(conn: HttpURLConnection): String? = runCatching {
        val body = conn.errorStream?.bufferedReader()?.readText() ?: return null
        json.decodeFromString<WireError>(body).detail?.take(200)
    }.getOrNull()

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Сколько последних сообщений диалога уходит модели: компактность важнее глубины. */
        const val HISTORY_LIMIT = 12
        const val MAX_CONTENT = 20000
        const val CONTEXT_MARK = "[Данные пользователя на момент вопроса]"
        const val IMPORT_QUESTION = "Разбери приложенную таблицу и верни JSON."
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
)

@Serializable
private data class WireReply(val reply: String = "", val model: String? = null)

@Serializable
private data class WireError(val detail: String? = null)

@Serializable
private data class WireHealth(val steps: Map<String, String>? = null)
