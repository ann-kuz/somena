package ru.somena.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocxReaderTest {

    @Test
    fun `документ читается - абзацы строками, табуляция, перенос, сущности`() {
        val bytes = docx(
            """<?xml version="1.0"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>
<w:p><w:r><w:t>Биохимический анализ крови</w:t></w:r></w:p>
<w:p><w:r><w:t>Гемоглобин</w:t></w:r><w:r><w:tab/><w:t>134 г/л</w:t></w:r></w:p>
<w:p><w:r><w:t>АЛТ &amp; АСТ</w:t></w:r><w:r><w:br/><w:t>строка после переноса</w:t></w:r></w:p>
<w:p><w:r><w:t>приём </w:t></w:r><w:r><w:t>врача</w:t></w:r><w:r><w:t> сшит в один</w:t></w:r></w:p>
</w:body>
</w:document>""",
        )
        assertEquals(
            "Биохимический анализ крови\n" +
                "Гемоглобин\t134 г/л\n" +
                "АЛТ & АСТ\nстрока после переноса\n" +
                "приём врача сшит в один",
            decodeDocxText(bytes),
        )
    }

    @Test
    fun `таблица документа - те же абзацы строками`() {
        // Ячейки таблицы содержат свои w:p: список абзацев покрывает и их, порядок документа.
        val bytes = docx(
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body><w:tbl><w:tr>
<w:tc><w:p><w:r><w:t>Показатель</w:t></w:r></w:p></w:tc>
<w:tc><w:p><w:r><w:t>Значение</w:t></w:r></w:p></w:tc>
</w:tr></w:tbl></w:body></w:document>""",
        )
        assertEquals("Показатель\nЗначение", decodeDocxText(bytes))
    }

    @Test
    fun `не-docx и zip без document xml дают null`() {
        assertNull(decodeDocxText("не документ".toByteArray(Charsets.UTF_8)))
        assertNull(decodeDocxText(zipOf("readme.txt" to "привет".toByteArray())))
    }

    @Test
    fun `валидный но пустой документ даёт пустую строку а не null`() {
        // Пустой docx отличается от битого: вызывающий код скажет «текст не найден»,
        // а не «файл повреждён».
        val bytes = docx(
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body><w:p><w:r><w:t>   </w:t></w:r></w:p><w:p/></w:body></w:document>""",
        )
        assertEquals("", decodeDocxText(bytes))
    }

    /** Минимальный docx: zip с word/document.xml. */
    private fun docx(documentXml: String): ByteArray =
        zipOf("word/document.xml" to documentXml.toByteArray(Charsets.UTF_8))

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
