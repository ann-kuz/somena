package ru.somena.data

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Клиент Бэкенда-прокси (тикет 07): POST /v1/chat с историей диалога и системным промптом.
 * Контекст данных идёт первым user-сообщением: у Бэкенда жёсткий лимит на system в 4000
 * символов, а срез за 30 дней в него не помещается. Без сети — внятная ошибка, остальное
 * приложение продолжает работать.
 */
class ChatClient(private val settings: ChatSettings) {

    suspend fun ask(history: List<ChatMessage>, system: String, context: String): Result<String> =
        withContext<Result<String>>(Dispatchers.IO) {
            try {
                val conn = (URL("${settings.backendUrl}/v1/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 90_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${settings.appToken}")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use {
                    it.write(json.encodeToString(request(history, system, context)).toByteArray())
                }
                when (conn.responseCode) {
                    200 -> {
                        val body = runCatching {
                            json.decodeFromString<WireReply>(conn.inputStream.bufferedReader().readText()).reply
                        }.getOrNull()
                        if (body.isNullOrBlank()) Result.failure(IllegalStateException(
                            "Бэкенд вернул пустой ответ: попробуй ещё раз."
                        ))
                        else Result.success(body)
                    }
                    401 -> Result.failure(IllegalStateException(
                        "Неверный токен приложения: проверь адрес и токен на вкладке «Ещё»."
                    ))
                    502, 503 -> Result.failure(IllegalStateException(
                        "ИИ сейчас недоступен: попробуй ещё раз позже."
                    ))
                    else -> Result.failure(IllegalStateException(
                        "Бэкенд ответил ошибкой (HTTP ${conn.responseCode}). Попробуй позже."
                    ))
                }
            } catch (e: UnknownHostException) {
                offline()
            } catch (e: java.net.ConnectException) {
                offline()
            } catch (e: SocketTimeoutException) {
                Result.failure(IllegalStateException("ИИ думал слишком долго: попробуй ещё раз."))
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Чат не удался: ${e.message ?: "ошибка сети"}."))
            }
        }

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

        fun offline(): Result<String> = Result.failure(IllegalStateException(
            "Нет интернета: чат недоступен, остальные вкладки работают без сети."
        ))
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
