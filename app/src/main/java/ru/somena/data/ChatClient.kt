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

/** Адрес и токен запросов к Бэкенду: чистые данные, тестируются без Android. */
data class ChatEndpoint(val url: String, val token: String)

/**
 * Клиент Бэкенда-прокси (тикет 07): POST /v1/chat с историей диалога и системным промптом.
 * Контекст данных идёт первым user-сообщением: у Бэкенда жёсткий лимит на system в 4000
 * символов, а срез за 30 дней в него не помещается.
 * О каждом запросе пишет две строки (запрос и исход) в [log] — это журнал на вкладке
 * «Отладка HC»; токен в журнал не попадает никогда.
 */
class ChatClient(
    private val endpoint: ChatEndpoint,
    private val log: (String) -> Unit = {},
) {

    suspend fun ask(history: List<ChatMessage>, system: String, context: String): Result<String> =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            log("→ POST ${endpoint.url}/v1/chat (сообщений в истории: ${history.size})")
            try {
                val conn = (URL("${endpoint.url}/v1/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 90_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${endpoint.token}")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use {
                    it.write(json.encodeToString(request(history, system, context)).toByteArray())
                }
                fun spent() = "${System.currentTimeMillis() - startedAt} мс"
                when (conn.responseCode) {
                    200 -> {
                        val body = runCatching {
                            json.decodeFromString<WireReply>(conn.inputStream.bufferedReader().readText()).reply
                        }.getOrNull()
                        if (body.isNullOrBlank()) fail(
                            "Бэкенд вернул пустой ответ: попробуй ещё раз.",
                            "HTTP 200 за ${spent()}, пустой ответ",
                        )
                        else {
                            log("← HTTP 200 за ${spent()}, ответ ${body.length} симв.")
                            Result.success(body)
                        }
                    }
                    401 -> fail(
                        "Токен приложения неверный. Нужна строка APP_TOKEN из backend/.env на сервере " +
                            "(это не ключ proxyapi): проверь вкладку «Ещё» и попробуй снова.",
                        "HTTP 401 (неверный токен) за ${spent()}",
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

    private fun request(history: List<ChatMessage>, system: String, context: String): WireRequest {
        val recent = history.takeLast(HISTORY_LIMIT)
            .map { WireMessage(it.role, it.content.take(MAX_CONTENT)) }
        val contextMsg = WireMessage("user", "$CONTEXT_MARK\n$context".take(MAX_CONTENT))
        // Контекст встаёт непосредственно перед свежим вопросом: данные и вопрос рядом.
        val messages = if (recent.isNotEmpty() && recent.last().role == ChatMessage.USER) {
            recent.dropLast(1) + contextMsg + recent.takeLast(1)
        } else {
            listOf(contextMsg) + recent
        }
        return WireRequest(messages = messages, system = system, maxTokens = 3000)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Сколько последних сообщений диалога уходит модели: компактность важнее глубины. */
        const val HISTORY_LIMIT = 12
        const val MAX_CONTENT = 20000
        const val CONTEXT_MARK = "[Данные пользователя на момент вопроса]"
    }
}

@Serializable
private data class WireMessage(val role: String, val content: String)

@Serializable
private data class WireRequest(
    val messages: List<WireMessage>,
    val system: String,
    @SerialName("max_tokens") val maxTokens: Int,
)

@Serializable
private data class WireReply(val reply: String = "")
