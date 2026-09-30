package ru.somena.core

import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Мини-ридер xlsx (спека 0004): zip + XML, без тяжёлых библиотек.
 * Первый лист книги превращается в текст таблицы: строки через \n, ячейки через \t.
 * Даты - serial-числа со стилем даты - переводятся в ISO, иначе ИИ увидел бы «45662».
 * null значит «файл не xlsx или лист не читается».
 */
fun decodeXlsxText(bytes: ByteArray): String? = try {
    val parts = zipEntries(bytes)
    val sheetPath = firstSheetPath(parts) ?: return null
    val sheetXml = parts[sheetPath] ?: return null
    val shared = sharedStrings(parts["xl/sharedStrings.xml"])
    val dateStyles = dateStyleIndexes(parts["xl/styles.xml"])
    readRows(sheetXml, shared, dateStyles)
        .filter { row -> row.any { it.isNotBlank() } }
        .joinToString("\n") { row -> row.joinToString("\t") { it.trim() } }
        .ifEmpty { null }
} catch (e: Exception) {
    null
}

/** Текст таблицы из файла вложения: zip-магия ведёт в xlsx-ридер, остальное - csv/tsv. */
fun decodeTableBytes(bytes: ByteArray): String? =
    if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) decodeXlsxText(bytes)
    else decodeTableText(bytes)

/** Записи zip-архива целиком: общая у xlsx- и docx-ридеров. */
internal fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
    val out = mutableMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) out[entry.name] = zip.readBytes()
            zip.closeEntry()
        }
    }
    return out
}

/** Путь первого листа по workbook.xml и его rels; без них - лист по соглашению об имени. */
private fun firstSheetPath(parts: Map<String, ByteArray>): String? {
    val workbook = parts["xl/workbook.xml"] ?: return null
    val sheets = parseXml(workbook).getElementsByTagName("sheet")
    if (sheets.length == 0) return null
    val el = sheets.item(0) as Element
    val attrs = el.attributes
    val rid = (0 until attrs.length)
        .firstOrNull { k -> attrs.item(k).nodeName == "id" || attrs.item(k).nodeName.endsWith(":id") }
        ?.let { attrs.item(it).nodeValue }
    val relsXml = parts["xl/_rels/workbook.xml.rels"]
    if (rid != null && relsXml != null) {
        val relations = parseXml(relsXml).getElementsByTagName("Relationship")
        for (i in 0 until relations.length) {
            val rel = relations.item(i) as Element
            if (rel.getAttribute("Id") == rid) {
                val target = rel.getAttribute("Target").removePrefix("/")
                return if (target.startsWith("xl/")) target else "xl/$target"
            }
        }
    }
    return "xl/worksheets/sheet1.xml"
}

private fun sharedStrings(xml: ByteArray?): List<String> {
    if (xml == null) return emptyList()
    val sis = parseXml(xml).getElementsByTagName("si")
    return (0 until sis.length).map { i ->
        val ts = (sis.item(i) as Element).getElementsByTagName("t")
        (0 until ts.length).joinToString("") { j -> ts.item(j).textContent }
    }
}

/** Встроенные форматы дат Excel (14-22, 27-36, 45-47, 50-58); свои добавляются по «yy» в коде. */
private val BUILTIN_DATE_FORMATS = (14..22).toSet() + (27..36).toSet() + (45..47).toSet() + (50..58).toSet()

private fun dateStyleIndexes(xml: ByteArray?): Set<Int> {
    if (xml == null) return emptySet()
    val root = parseXml(xml)
    val customFormats = mutableSetOf<Int>()
    val formats = root.getElementsByTagName("numFmt")
    for (i in 0 until formats.length) {
        val el = formats.item(i) as Element
        val id = el.getAttribute("numFmtId").toIntOrNull() ?: continue
        if (el.getAttribute("formatCode").lowercase().contains("yy")) customFormats += id
    }
    val allDateIds = BUILTIN_DATE_FORMATS + customFormats
    val xfs = root.getElementsByTagName("xf")
    return (0 until xfs.length)
        .filter { i -> (xfs.item(i) as Element).getAttribute("numFmtId").toIntOrNull() in allDateIds }
        .toSet()
}

private fun readRows(sheetXml: ByteArray, shared: List<String>, dateStyles: Set<Int>): List<List<String>> {
    val rows = parseXml(sheetXml).getElementsByTagName("row")
    return (0 until rows.length).map { r ->
        val cells = (rows.item(r) as Element).getElementsByTagName("c")
        val out = mutableListOf<String>()
        for (i in 0 until cells.length) {
            val c = cells.item(i) as Element
            val col = columnIndex(c.getAttribute("r"))
            while (out.size < col) out.add("")
            out.add(cellText(c, shared, dateStyles))
        }
        out
    }
}

private fun cellText(c: Element, shared: List<String>, dateStyles: Set<Int>): String {
    val type = c.getAttribute("t")
    val v = c.getElementsByTagName("v").item(0)?.textContent
    val style = c.getAttribute("s").toIntOrNull()
    return when {
        type == "inlineStr" -> c.getElementsByTagName("t").item(0)?.textContent ?: ""
        type == "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
        v == null -> ""
        style != null && style in dateStyles -> v.toDoubleOrNull()?.let(::serialToDate) ?: v
        else -> v
    }
}

/** Serial Excel - дни с 1899-12-30 (учтён ложный високос 1900 года). */
private fun serialToDate(v: Double): String =
    LocalDate.of(1899, 12, 30).plusDays(Math.round(v)).toString()

/** «A1» -> 0, «BC12» -> 54: буквы колонки в индекс с нуля. */
private fun columnIndex(ref: String): Int {
    var idx = 0
    for (ch in ref) {
        if (!ch.isLetter()) break
        idx = idx * 26 + (ch.uppercaseChar() - 'A' + 1)
    }
    return (idx - 1).coerceAtLeast(0)
}

/** Разбор XML-записи: общая у xlsx- и docx-ридеров. */
internal fun parseXml(bytes: ByteArray): Element =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
