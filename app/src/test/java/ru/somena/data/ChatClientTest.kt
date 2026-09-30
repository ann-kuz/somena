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

    private fun client(url: String) =
        ChatClient(ChatTransport.Server(url, "токен"), log = { lines.add(it) })

    /** Клиент прямого режима: свой ключ proxyapi, модели Ступеней с телефона, сервер не нужен. */
    private fun directClient(url: String, fast: String = "gpt-4.1-mini", max: String = "gpt-5.1") =
        ChatClient(
            ChatTransport.Direct("ключ", fast, max, baseUrl = url),
            log = { lines.add(it) },
        )

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
            ChatTransport.Server(s.url(), "токен"),
            chatReadTimeoutMs = 100,
            importReadTimeoutMs = 5_000,
        )
        runBlocking {
            assertTrue("чат: ${lines.lastOrNull()}", slow.ask(history, "sys", "контекст", "fast").isFailure)
            assertTrue("разбор: ${lines.lastOrNull()}", slow.askImport("Дата;Вес\n05.01.2025;62.4").isSuccess)
            // Разбор документа - тот же долгий ответ провайдера: ждёт как разбор таблицы.
            assertTrue(
                "разбор документа: ${lines.lastOrNull()}",
                slow.askDocumentImport(attachment = "Гемоглобин 134", question = "").isSuccess,
            )
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

    @Test
    fun `разбор документа идет текстом вложения на выбранной ступени`() {
        // Спека 0010: текст pdf уходит каналом вложения, картинки пусты - поля images в
        // запросе нет вовсе, Ступень передаётся выбранная.
        val bodies = mutableListOf<String>()
        val s = stubServer(200, "{\"reply\": \"{\\\"kind\\\":\\\"analysis\\\"}\"}", bodies)
        val r = runBlocking {
            client(s.url()).askDocumentImport(
                attachment = "Гемоглобин 134 г/л",
                question = "Разбери анализ",
                step = "max",
            )
        }
        assertTrue(r.isSuccess)
        val body = bodies.single()
        assertTrue("тело: $body", body.contains("\"attachment\""))
        // Пустых картинок нет вовсе (null-поля клиент кодирует, но пустым списком не врёт).
        assertTrue("тело: $body", !body.contains("\"images\":["))
        assertTrue("тело: $body", body.contains("\"step\":\"max\""))
        assertTrue("тело: $body", body.contains("\"max_tokens\":16000"))
        // Системный промпт - именно разбора документа: «разборщик медицинских» есть только в нём.
        assertTrue("тело: $body", body.contains(utf8AsIso("разборщик медицинских")))
        s.close()
    }

    @Test
    fun `картинки разбора документа уходят отдельным полем а вложения нет`() {
        val bodies = mutableListOf<String>()
        val s = stubServer(200, "{\"reply\": \"ок\"}", bodies)
        val r = runBlocking {
            client(s.url()).askDocumentImport(
                attachment = null,
                images = listOf("aGVsbG8=", "eHl6eg=="),
                question = "",
                step = "fast",
            )
        }
        assertTrue(r.isSuccess)
        val body = bodies.single()
        assertTrue("тело: $body", body.contains("\"images\":[\"aGVsbG8=\",\"eHl6eg==\"]"))
        assertTrue("тело: $body", !body.contains("\"attachment\":\""))
        // Пустой вопрос подменяется фиксированной фразой разбора документа.
        assertTrue("тело: $body", body.contains(utf8AsIso("приложенный")))
        s.close()
    }

    // Прямой режим: свой ключ proxyapi, OpenAI-совместимый запрос вместо формата Бэкенда.

    /** Ответ провайдера в OpenAI-форме: текст в choices[0].message.content. */
    private val providerReply =
        "{\"model\":\"gpt-5.1\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Вес стоит из-за воды.\"}}]}"

    @Test
    fun `прямой режим шлёт OpenAI-совместимый запрос с моделью ступени`() {
        val bodies = mutableListOf<String>()
        val s = stubServer(200, providerReply, bodies)
        lines.clear()
        val r = runBlocking {
            directClient(s.url()).ask(history, "sys-промпт", "контекст", "max")
        }
        assertEquals("Вес стоит из-за воды.", r.getOrNull())
        val body = bodies.single()
        assertTrue("тело: $body", body.contains("\"model\":\"gpt-5.1\""))
        assertTrue("тело: $body", body.contains("\"max_completion_tokens\":3000"))
        // Системный промпт - первым сообщением, полей формата Бэкенда в теле нет.
        assertTrue("тело: $body", body.startsWith("{\"model\":\"gpt-5.1\",\"max_completion_tokens\":3000,\"messages\":[{\"role\":\"system\""))
        assertTrue("тело: $body", !body.contains("\"step\""))
        assertTrue("тело: $body", !body.contains("\"attachment\""))
        // Запрос идёт по адресу сервиса (любому, не только proxyapi), хвост добавляет приложение.
        assertTrue("журнал: $lines", lines.first() == "→ POST ${s.url()}/chat/completions (сообщений в истории: 1, ступень: max)")
        s.close()
    }

    @Test
    fun `прямой режим шлёт быструю модель на быстрой ступени`() {
        val bodies = mutableListOf<String>()
        val s = stubServer(200, providerReply, bodies)
        runBlocking { directClient(s.url(), fast = "gpt-4o-mini").ask(history, "sys", "контекст", "fast") }
        assertTrue("тело: ${bodies.single()}", bodies.single().contains("\"model\":\"gpt-4o-mini\""))
        s.close()
    }

    @Test
    fun `прямой режим вкладывает таблицу user-сообщением перед вопросом`() {
        // Те же правила, что у Бэкенда: таблица доходит до модели сообщением, вопрос - после неё.
        val bodies = mutableListOf<String>()
        val s = stubServer(200, providerReply, bodies)
        val r = runBlocking {
            directClient(s.url()).askImport("Дата;Вес\n05.01.2025;62.4", "Внеси сожжённые калории из 2 столбца")
        }
        assertTrue(r.isSuccess)
        val body = bodies.single()
        assertTrue("тело: $body", !body.contains("\"attachment\""))
        val attachmentAt = body.indexOf(utf8AsIso("[Приложенная таблица]"))
        // «столбца» живёт только в вопросе: «сожжённые» есть и в системном промпте.
        val questionAt = body.indexOf(utf8AsIso("столбца"))
        assertTrue("тело: $body", attachmentAt >= 0)
        assertTrue("тело: $body", questionAt > attachmentAt)
        s.close()
    }

    @Test
    fun `прямой режим шлёт картинки multimodal-частями вопроса`() {
        val bodies = mutableListOf<String>()
        val s = stubServer(200, providerReply, bodies)
        val r = runBlocking {
            directClient(s.url()).askDocumentImport(
                attachment = null,
                images = listOf("aGVsbG8="),
                question = "Что на снимке?",
                step = "fast",
            )
        }
        assertTrue(r.isSuccess)
        val body = bodies.single()
        assertTrue("тело: $body", body.contains("\"type\":\"image_url\""))
        assertTrue("тело: $body", body.contains("\"url\":\"data:image/jpeg;base64,aGVsbG8=\""))
        // Текст вопроса остался рядом с картинками: multimodal-сообщение собрано верно.
        assertTrue("тело: $body", body.contains(utf8AsIso("Что на снимке?")))
        assertTrue("тело: $body", !body.contains("\"images\":["))
        s.close()
    }

    @Test
    fun `401 в прямом режиме объясняет про ключ API а не про токен приложения`() {
        val s = stubServer(401, "{\"error\":{\"message\":\"Incorrect API key\"}}")
        val r = runBlocking { directClient(s.url()).ask(history, "sys", "контекст", "fast") }
        val msg = r.exceptionOrNull()?.message ?: ""
        assertTrue("сообщение: $msg", msg.contains("Ключ API"))
        assertTrue("сообщение: $msg", !msg.contains("APP_TOKEN"))
        s.close()
    }

    @Test
    fun `подписи ступеней прямого режима берутся из настроек без сети`() {
        // Серверный /health не нужен: модели уже выбраны на телефоне, замкнутый порт не мешает.
        val probe = stubServer(200, "{}")
        val url = probe.url()
        probe.close()
        val steps = directClient(url, fast = "gpt-4o-mini", max = "gpt-4.1").steps()
        assertEquals(mapOf("fast" to "gpt-4o-mini", "max" to "gpt-4.1"), steps)
    }

    @Test
    fun `пустой ответ провайдера в прямом режиме дает внятную ошибку`() {
        val s = stubServer(200, "{\"model\":\"gpt-5.1\",\"choices\":[{\"message\":{\"content\":\"\"}}]}")
        val r = runBlocking { directClient(s.url()).ask(history, "sys", "контекст", "fast") }
        val msg = r.exceptionOrNull()?.message ?: ""
        assertTrue("сообщение: $msg", msg.contains("пустой ответ"))
        s.close()
    }
}
