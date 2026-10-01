package ru.somena.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextDocReadersTest {

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // odt

    @Test
    fun `odt читается - абзацы строками, таб в абзаце`() {
        val bytes = zip(
            "mimetype" to "application/vnd.oasis.opendocument.text".toByteArray(),
            "content.xml" to """
                <?xml version="1.0"?>
                <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
                <office:body><office:text>
                <text:h>Биохимический анализ крови</text:h>
                <text:p>Гемоглобин<text:tab/>134 г/л</text:p>
                <text:p>АЛТ &amp; АСТ</text:p>
                </office:text></office:body></office:document-content>
            """.trimIndent().toByteArray(),
        )
        assertEquals(
            "Биохимический анализ крови\nГемоглобин\t134 г/л\nАЛТ & АСТ",
            decodeOdtText(bytes),
        )
    }

    @Test
    fun `odt без content xml и мусор дают null`() {
        assertNull(decodeOdtText(zip("mimetype" to byteArrayOf())))
        assertNull(decodeOdtText("не архив".toByteArray()))
    }

    // fb2

    @Test
    fun `fb2 читается - абзацы и стихи строками, сноски пропущены`() {
        val fb2 = """
            <?xml version="1.0"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <body>
              <section><title><p>Выписка</p></title>
                <p>Гемоглобин 134 г/л</p>
                <poem><v>строка стиха</v><v>вторая строка</v></poem>
              </section>
            </body>
            <body name="notes"><p>сноска не должна попасть</p></body>
            </FictionBook>
        """.trimIndent().toByteArray()
        val text = decodeFb2Text(fb2)!!
        assertEquals("Выписка\nГемоглобин 134 г/л\nстрока стиха\nвторая строка", text)
    }

    @Test
    fun `fb2 из битого xml даёт null`() {
        assertNull(decodeFb2Text("<FictionBook>".toByteArray()))
    }

    // html

    @Test
    fun `html читается - блоки строками, ячейки таблицы через таб`() {
        val html = """
            <html><head><title>Результаты</title>
            <style>body { color: red; }</style></head>
            <body>
            <script>var x = "не текст";</script>
            <h1>Общий анализ крови</h1>
            <p>Гемоглобин&nbsp;&amp; гематокрит</p>
            <table><tr><td>Гемоглобин</td><td>134 г/л</td></tr></table>
            <p>&laquo;копия&#187; &#169; лаборатория</p>
            </body></html>
        """.trimIndent().toByteArray()
        assertEquals(
            "Результаты\nОбщий анализ крови\nГемоглобин & гематокрит\n" +
                "Гемоглобин\t134 г/л\n«копия» © лаборатория",
            decodeHtmlText(html),
        )
    }

    @Test
    fun `html в cp1251 читается по запасной кодировке`() {
        val html = "<p>Тест кодировки</p>".toByteArray(charset("windows-1251"))
        assertEquals("Тест кодировки", decodeHtmlText(html))
    }

    // epub

    @Test
    fun `epub читается - главы по порядку spine, пути с точками`() {
        val chapter = """
            <html><body><p>Глава с анализом</p></body></html>
        """.trimIndent().toByteArray()
        val notes = "<html><body><p>Сноска</p></body></html>".toByteArray()
        val bytes = zip(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """
                <?xml version="1.0"?>
                <container><rootfiles>
                <rootfile full-path="OEBPS/content.opf"/>
                </rootfiles></container>
            """.trimIndent().toByteArray(),
            "OEBPS/content.opf" to """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf">
                <manifest>
                <item id="ch1" href="text/ch1.xhtml"/>
                <item id="notes" href="text/notes.xhtml"/>
                </manifest>
                <spine><itemref idref="ch1"/><itemref idref="notes"/></spine>
                </package>
            """.trimIndent().toByteArray(),
            "OEBPS/text/ch1.xhtml" to chapter,
            "OEBPS/text/notes.xhtml" to notes,
        )
        assertEquals("Глава с анализом\n\nСноска", decodeEpubText(bytes))
    }

    @Test
    fun `epub без контейнера и мусор дают null`() {
        assertNull(decodeEpubText(zip("OEBPS/content.opf" to "<package/>".toByteArray())))
        assertNull(decodeEpubText("не архив".toByteArray()))
        assertNull(decodeEpubText(zip("META-INF/container.xml" to "<container/>".toByteArray())))
    }

    @Test
    fun `пути href с точками нормализуются`() {
        assertEquals("a/c", normalizeZipPath("a/b/../c"))
        assertEquals("b/c", normalizeZipPath("./b/./c"))
        assertEquals("x", normalizeZipPath("x/../x"))
    }

    // rtf

    @Test
    fun `rtf читается - юникод-эскейпы, cp1251-эскейпы, абзацы и табы`() {
        // Кириллица в rtf живёт эскейпами \'hh (cp1251) и \uN; файл целиком ASCII.
        val rtf = ("{\\rtf1\\ansi\\ansicpg1251\\deff0{\\fonttbl{\\f0 Times New Roman;}}\n" +
            "\\uc1\\pard Plain text\\par\n" +
            "\\'d2\\'e5\\'f1\\'f2\\'20\\'ea\\'e8\\'f0\\'e8\\'eb\\'eb\\'e8\\'f6\\'e5\\'e9" +
            "\\tab 134\\par\n" +
            "\\u1043\\'c3\\'e5\\'ec\\'ee\\'e3\\'eb\\'ee\\'e1\\'e8\\'ed\\par\n" +
            "{\\*\\generator Word}{\\info{\\author \\'c0}}\n" +
            "ALT + AST\\par}").toByteArray(StandardCharsets.US_ASCII)
        assertEquals(
            "Plain text\nТест кириллицей\t134\nГемоглобин\nALT + AST",
            decodeRtfText(rtf),
        )
    }

    @Test
    fun `rtf группа шрифтов и картинка не протекают в текст`() {
        val rtf = ("{\\rtf1{\\fonttbl{\\f0 \\'f1\\'e2;}}{\\pict\\picw10 \\'00\\'ff}" +
            "\\'f2\\'e5\\'ea\\'f1\\'f2\\par}").toByteArray(StandardCharsets.US_ASCII)
        assertEquals("текст", decodeRtfText(rtf))
    }

    @Test
    fun `не rtf и пустой rtf`() {
        assertNull(decodeRtfText("просто текст".toByteArray()))
        assertNull(decodeRtfText(byteArrayOf()))
        assertEquals("", decodeRtfText("{\\rtf1}".toByteArray()))
    }
}
