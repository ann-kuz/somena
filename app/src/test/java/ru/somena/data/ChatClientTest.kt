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

    private fun client(url: String) = ChatClient(ChatEndpoint(url, "токен"), log = { lines.add(it) })

    /** Консервированный ответ разбора: валидный JSON с пустым массивом дней. */
    private fun importStub(bodies: MutableList<String>) =
        stubServer(200, "{\"reply\": \"{\\\"days\\\":[]}\"}", bodies)

    /** Как кириллица видна в теле, пойманном стабом (ISO-8859-1 вместо UTF-8). */
    private fun utf8AsIso(s: String) = String(s.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

    /** Минимальный HTTP-стаб: отвечает заданным кодом и JSON-телом на любой POST, тело запроса ловится. */
    private fun stubServer(
        code: Int,
        json: String,
        bodies: MutableList<String>? = null,
        delayMs: Long = 0,
    ): ServerSocket =
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply {
            thread(isDaemon = true) {
                while (!isClosed) {
                    val socket = try {
                        accept()
                    } catch (e: Exception) {
                        break
                    }
                    thread(isDaemon = true) { socket.serve(code, json, bodies, delayMs) }
                }
            }
        }

    private fun Socket.serve(code: Int, json: String, bodies: MutableList<String>?, delayMs: Long) {
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
            val raw = CharArray(contentLength) { ' ' }
            var read = 0
            while (read < contentLength) {
                val n = reader.read(raw, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            bodies?.add(String(raw)) // тело дочитываем, чтобы клиент не получил RST
            if (delayMs > 0) Thread.sleep(delayMs)
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
        val r = runBlocking { client(s.url()).ask(history, "sys", "контекст", "fast") }
        assertEquals("Вес стоит из-за воды.", r.getOrNull())
        s.close()
    }

    @Test
    fun `выбранная ступень уходит в запросе`() {
        val bodies = mutableListOf<String>()
        val s = stubServer(200, "{\"reply\": \"ок\"}", bodies)
        runBlocking { client(s.url()).ask(history, "sys", "контекст", "max") }
        assertTrue("тело: ${bodies.firstOrNull()}", bodies.single().contains("\"step\":\"max\""))
        s.close()
    }

    @Test
    fun `вложение уходит отдельным полем на быстрой ступени с большим лимитом токенов`() {
        val bodies = mutableListOf<String>()
        val s = importStub(bodies)
        val r = runBlocking { client(s.url()).askImport("Дата;Вес\n05.01.2025;62.4") }
        assertTrue(r.isSuccess)
        val body = bodies.single()
        assertTrue("тело: $body", body.contains("\"attachment\""))
        // Стаб ловит тело в ISO-8859-1, кириллица там mojibake; проверяем ASCII-фрагмент значения.
        assertTrue("тело: $body", body.contains(";62.4"))
        assertTrue("тело: $body", body.contains("\"step\":\"fast\""))
        assertTrue("тело: $body", body.contains("\"max_tokens\":16000"))
        s.close()
    }

    @Test
    fun `401 от бэкенда дает внятную ошибку про APP_TOKEN а не про связь`() {
        val s = stubServer(401, "{\"detail\":\"Неверный токен приложения\"}")
        val r = runBlocking { client(s.url()).ask(history, "sys", "контекст", "fast") }
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
        val r = runBlocking { client(url).ask(history, "sys", "контекст", "fast") }
        val msg = r.exceptionOrNull()?.message ?: ""
        assertTrue("сообщение: $msg", msg.contains("отклонил соединение"))
        assertTrue("сообщение: $msg", !msg.contains("интернет"))
    }

    @Test
    fun `каждый запрос пишет в журнал строку запроса и строку исхода`() {
        val s = stubServer(200, "{\"reply\": \"ок\"}")
        lines.clear()
        runBlocking { client(s.url()).ask(history, "sys", "контекст", "fast") }
        assertTrue(lines.first().startsWith("→ POST http://"))
        assertTrue(lines.last().startsWith("← HTTP 200"))
        // Значение токена в журнал не попадает никогда (здесь токен = «токен»).
        assertTrue(lines.none { it.contains("токен") })
        s.close()
    }

    @Test
    fun `разбор таблицы ждёт дольше обычного чата`() {
        // Инцидент 25.09: провайдер отвечает на длинную таблицу десятки секунд - успешные
        // разборы шли 37-86 с. Обычный чат с коротким таймаутом роняет медленный ответ,
        // разбор таблицы обязан дожидаться тот же ответ.
        val s = stubServer(200, "{\"reply\": \"ок\"}", delayMs = 500)
        val slow = ChatClient(
            ChatEndpoint(s.url(), "токен"),
            chatReadTimeoutMs = 100,
            importReadTimeoutMs = 5_000,
        )
        runBlocking {
            assertTrue("чат: ${lines.lastOrNull()}", slow.ask(history, "sys", "контекст", "fast").isFailure)
            assertTrue("разбор: ${lines.lastOrNull()}", slow.askImport("Дата;Вес\n05.01.2025;62.4").isSuccess)
        }
        s.close()
    }

    @Test
    fun `вопрос Пользователя уходит в Разборе таблицы вместе с Вложением`() {
        // Спека 0007, инцидент «съедено вместо сожжённых»: текст вопроса должен доходить
        // до модели, иначе та угадывает показатель неоднозначной колонки.
        val bodies = mutableListOf<String>()
        val s = importStub(bodies)
        val r = runBlocking {
            client(s.url()).askImport("Дата;Вес\n05.01.2025;62.4", "Внеси сожжённые калории (не съеденные!) из 2 столбца")
        }
        assertTrue(r.isSuccess)
        // Фрагмент уникален вопросу Пользователя: в системном промпте «сожжённые» нет.
        assertTrue("тело: ${bodies.single()}", bodies.single().contains(utf8AsIso("сожжённые")))
        s.close()
    }

    @Test
    fun `пустой вопрос Разбора таблицы подменяется фиксированной фразой`() {
        val bodies = mutableListOf<String>()
        val s = importStub(bodies)
        val r = runBlocking { client(s.url()).askImport("Дата;Вес\n05.01.2025;62.4", "") }
        assertTrue(r.isSuccess)
        // «приложенную» есть только в запасной фразе вопроса: в системном промпте её нет,
        // так что тест ловит именно подмену, а не слово «JSON», живущее и в промпте.
        assertTrue("тело: ${bodies.single()}", bodies.single().contains(utf8AsIso("приложенную")))
        s.close()
    }
}
