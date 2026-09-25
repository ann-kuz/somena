package ru.somena.core

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsxReaderTest {

    @Test
    fun `лист читается - строки и даты на месте`() {
        val serial = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), LocalDate.of(2025, 1, 5))
        val bytes = xlsx(
            sheet1 = """<?xml version="1.0"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetData>
<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
<row r="2"><c r="A2" s="1"><v>$serial</v></c><c r="B2"><v>62.4</v></c><c r="D2" t="inlineStr"><is><t>замечание</t></is></c></row>
<row r="3"></row>
<row r="4"><c r="A4"><v>63.1</v></c></row>
</sheetData>
</worksheet>""",
        )
        val text = decodeXlsxText(bytes)
        assertNotNull(text)
        val lines = text!!.split("\n")
        assertEquals(3, lines.size)
        assertEquals("Дата\tВес", lines[0])
        assertEquals("2025-01-05\t62.4\t\tзамечание", lines[1])
        assertEquals("63.1", lines[2])
    }

    @Test
    fun `первый лист определяется по rels а не по имени файла`() {
        // rels ведёт rId1 на sheet2.xml: читать нужно его, а не sheet1 по соглашению об имени.
        val bytes = xlsx(
            sheet1 = """<worksheet><sheetData><row r="1"><c r="A1"><v>2</v></c></row></sheetData></worksheet>""",
            sheet2 = """<worksheet><sheetData><row r="1"><c r="A1"><v>1</v></c></row></sheetData></worksheet>""",
            firstSheetTarget = "worksheets/sheet2.xml",
        )
        val lines = decodeXlsxText(bytes)!!.split("\n")
        assertEquals("1", lines[0])
    }

    @Test
    fun `не-zip и zip без листа дают null`() {
        assertNull(decodeXlsxText("не таблица".toByteArray(Charsets.UTF_8)))
        assertNull(decodeXlsxText(zipOf("readme.txt" to "привет".toByteArray())))
    }

    @Test
    fun `csv проходит через sniffing без изменений`() {
        val csv = "Дата;Вес\n05.01.2025;62.4".toByteArray(Charsets.UTF_8)
        assertEquals("Дата;Вес\n05.01.2025;62.4", decodeTableBytes(csv))
    }

    /** Собирает минимальный xlsx: workbook, rels, стили с датовым форматом, sharedStrings, лист(ы). */
    private fun xlsx(
        sheet1: String,
        sheet2: String? = null,
        firstSheetTarget: String = "worksheets/sheet1.xml",
    ): ByteArray {
        fun workbook(second: Boolean): String = """<?xml version="1.0"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets>
<sheet name="Лист1" sheetId="1" r:id="rId1"/>${if (second) """
<sheet name="Лист2" sheetId="2" r:id="rId2"/>""" else ""}
</sheets>
</workbook>"""

        fun rels(firstTarget: String, second: Boolean): String = """<?xml version="1.0"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="$firstTarget"/>${if (second) """
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>""" else ""}
</Relationships>"""

        val entries = mutableListOf(
            "xl/workbook.xml" to workbook(sheet2 != null).toByteArray(),
            "xl/_rels/workbook.xml.rels" to rels(firstSheetTarget, sheet2 != null).toByteArray(),
            "xl/styles.xml" to """<?xml version="1.0"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="0"/>
<cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14" applyNumberFormat="1"/></cellXfs>
</styleSheet>""".toByteArray(),
            "xl/sharedStrings.xml" to """<?xml version="1.0"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
<si><t>Дата</t></si><si><t>Вес</t></si>
</sst>""".toByteArray(),
            "xl/worksheets/sheet1.xml" to sheet1.toByteArray(),
        )
        sheet2?.let { entries += "xl/worksheets/sheet2.xml" to it.toByteArray() }
        return zipOf(*entries.map { it.first to it.second }.toTypedArray())
    }

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
