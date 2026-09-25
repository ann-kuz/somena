package ru.somena.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поведение клиента чата против локального стаб-сервера на сокетах: тот же путь кода,
 * что и на телефоне (HttpURLConnection), без внешней сети и без Android-зависимостей.
 * Регресс после инцидента «пишет нет связи»: сервер отвечал 401, а сообщение обязано
 * было сказать про токен, а не про интернет.
 */
class ChatClientTest {

    private val history = listOf(ChatMessage(ChatMessage.USER, "Почему вес встал?", 0L))
    private val lines = mutableListOf<String>()

    private fun client(url: String) = ChatClient(ChatEndpoint(url, "токен")) { lines.add(it) }

    /** Минимальный HTTP-стаб: отвечает заданным кодом и JSON-телом на любой POST. */
    private fun stubServer(code: Int, json: String): ServerSocket =
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply {
            thread(isDaemon = true) {
                while (!isClosed) {
                    val socket = try {
                        accept()
                    } catch (e: Exception) {
                        break
                    }
                    thread(isDaemon = true) { socket.serve(code, json) }
                }
            }
        }

    private fun Socket.serve(code: Int, json: String) {
        use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toInt()
                }
            }
            repeat(contentLength) { reader.read() } // тело дочитываем, чтобы клиент не получил RST
            val reason = mapOf(200 to "OK", 401 to "Unauthorized")[code] ?: "Status"
            val body = json.toByteArray(Charsets.UTF_8)
            sock.getOutputStream().apply {
                write(
                    ("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                )
                write(body)
                flush()
            }
        }
    }

    private fun ServerSocket.url() = "http://127.0.0.1:${localPort}"

    @Test
    fun `успешный ответ разбирается в reply`() {
        val s = stubServer(200, "{\"reply\": \"Вес стоит из-за воды.\"}")
        val r = runBlocking { client(s.url()).ask(history, "sys", "контекст") }
        assertEquals("Вес стоит из-за воды.", r.getOrNull())
        s.close()
    }

    @Test
    fun `401 от бэкенда дает внятную ошибку про APP_TOKEN а не про связь`() {
        val s = stubServer(401, "{\"detail\":\"Неверный токен приложения\"}")
        val r = runBlocking { client(s.url()).ask(history, "sys", "контекст") }
        val msg = r.exceptionOrNull()?.message ?: ""
        assertTrue("сообщение: $msg", msg.contains("APP_TOKEN"))
        assertTrue("сообщение: $msg", !msg.contains("интернет"))
        s.close()
    }

    @Test
    fun `закрытый порт дает ошибку про соединение а не про интернет`() {
        // Занимаем порт и освобождаем: подключение гарантированно отклоняется.
        val probe = stubServer(200, "{}")
        val url = probe.url()
        probe.close()
        val r = runBlocking { client(url).ask(history, "sys", "контекст") }
        val msg = r.exceptionOrNull()?.message ?: ""
        assertTrue("сообщение: $msg", msg.contains("отклонил соединение"))
        assertTrue("сообщение: $msg", !msg.contains("интернет"))
    }

    @Test
    fun `каждый запрос пишет в журнал строку запроса и строку исхода`() {
        val s = stubServer(200, "{\"reply\": \"ок\"}")
        lines.clear()
        runBlocking { client(s.url()).ask(history, "sys", "контекст") }
        assertTrue(lines.first().startsWith("→ POST http://"))
        assertTrue(lines.last().startsWith("← HTTP 200"))
        // Значение токена в журнал не попадает никогда (здесь токен = «токен»).
        assertTrue(lines.none { it.contains("токен") })
        s.close()
    }
}
