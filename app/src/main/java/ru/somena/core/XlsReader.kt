package ru.somena.core

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.TreeMap
import kotlin.math.abs

/**
 * Мини-ридер старого xls (Excel 97-2003, спека 0016): OLE2-контейнер + BIFF8, без
 * тяжёлых библиотек. Первый лист книги превращается в текст таблицы: строки через
 * \n, ячейки через \t - как у xlsx-ридера. Даты - числа со стилем даты - переводятся
 * в ISO. Затея - выгрузка Fitdays: старый бинарный xls под именем .csv, который
 * раньше читался запасной кодировкой со шлаком. null - файл не читается.
 */

fun decodeXlsText(bytes: ByteArray): String? = try {
    val file = Ole2File(bytes)
    val biff = file.stream("Workbook") ?: file.stream("Book") ?: return null
    readSheet(biff)
        .filter { row -> row.any { it.isNotBlank() } }
        .joinToString("\n") { row -> row.joinToString("\t") { it.trim() } }
        .ifEmpty { null }
} catch (e: Exception) {
    null
}

/** Магия OLE2 (общая обёртка старых xls и doc): ведёт в xls-ридер, а не в csv. */
internal fun isOle2(bytes: ByteArray): Boolean =
    bytes.size >= 8 &&
        bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() && bytes[2] == 0x11.toByte() &&
        bytes[3] == 0xE0.toByte() && bytes[4] == 0xA1.toByte() && bytes[5] == 0xB1.toByte() &&
        bytes[6] == 0x1A.toByte() && bytes[7] == 0xE1.toByte()

private const val SECT_FREE = -1
private const val SECT_END = -2
private const val SECT_FAT = -3

/**
 * Контейнер OLE2 (compound file): заголовок, FAT с DIFAT, каталог и мини-стрим для
 * коротких потоков. Поток «Workbook» - это и есть листы BIFF8. Каталог читается
 * обычной цепочкой секторов - так пишут Excel и POI, мини-стрим нужен только
 * именованным потокам.
 */
private class Ole2File(private val file: ByteArray) {
    private val sectorSize = 1 shl u16(0x1E)
    private val miniSectorSize = 1 shl u16(0x20)
    private val cutoff = u32(0x38).toLong() and 0xFFFFFFFFL
    private val sectorCount = (file.size - 512) / sectorSize
    private val fat: IntArray
    private val entries: List<DirEntry>
    private val miniStream: ByteArray
    private val miniFat: IntArray

    init {
        if (sectorSize !in 64..4096 || sectorCount < 0) throw IllegalStateException("чужой OLE2")
        val difat = ArrayList<Int>()
        for (i in 0 until 109) {
            val v = u32(0x4C + 4 * i)
            if (v in 0 until sectorCount) difat += v
        }
        // DIFAT-цепочка: сектора со списком FAT-секторов, последний инт - следующий сектор.
        var difatSector = u32(0x44)
        var hops = 0
        while (difatSector in 0 until sectorCount && hops++ < 4096) {
            val per = sectorSize / 4 - 1
            for (i in 0 until per) {
                val v = intAtSector(difatSector, i * 4)
                if (v in 0 until sectorCount) difat += v
            }
            difatSector = intAtSector(difatSector, sectorSize - 4)
        }
        val perSector = sectorSize / 4
        fat = IntArray(difat.size * perSector)
        difat.forEachIndexed { i, s ->
            for (e in 0 until perSector) fat[i * perSector + e] = intAtSector(s, e * 4)
        }
        val dir = readChain(u32(0x30))
        entries = (dir.size / 128)
            .let { n -> (0 until n).map { i -> dir.copyOfRange(i * 128, i * 128 + 128) } }
            .filter { it[0x42].toInt() != 0 }
            .map { DirEntry(it) }
        val root = entries.firstOrNull { it.type == 5 } ?: throw IllegalStateException("нет корня")
        miniStream = if (root.size > 0 && root.start >= 0) readChain(root.start, root.size) else ByteArray(0)
        val miniFatSectors = u32(0x40)
        miniFat = if (miniFatSectors > 0 && miniFatSectors < sectorCount) {
            readChain(u32(0x3C), miniFatSectors * sectorSize.toLong()).toIntArray()
        } else {
            IntArray(0)
        }
    }

    /** Именованный поток: короче порога - из мини-стрима, длиннее - обычной цепочкой. */
    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name == name } ?: return null
        if (e.size == 0L) return ByteArray(0)
        return if (e.size < cutoff) readMiniChain(e.start, e.size) else readChain(e.start, e.size)
    }

    private fun u16(off: Int): Int =
        (file[off].toInt() and 0xFF) or ((file[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(off: Int): Int =
        u16(off) or (u16(off + 2) shl 16)

    private fun intAtSector(s: Int, off: Int): Int = u32At(sectorOffset(s) + off)

    private fun u32At(off: Int): Int =
        (file[off].toInt() and 0xFF) or ((file[off + 1].toInt() and 0xFF) shl 8) or
            ((file[off + 2].toInt() and 0xFF) shl 16) or ((file[off + 3].toInt() and 0xFF) shl 24)

    private fun sectorOffset(s: Int) = 512 + s * sectorSize

    /** Цепочка секторов до конца; size обрезает хвост (поток короче целых секторов). */
    private fun readChain(start: Int, size: Long = -1L): ByteArray {
        if (start < 0) throw IllegalStateException("пустая цепочка")
        val out = ByteArrayOutputStream()
        var s = start
        var hops = 0
        val seen = HashSet<Int>()
        while (s != SECT_END && s != SECT_FREE && s != SECT_FAT) {
            if (s !in 0 until sectorCount || !seen.add(s) || hops++ > sectorCount) {
                throw IllegalStateException("порванная цепочка")
            }
            val from = sectorOffset(s)
            if (from + sectorSize > file.size) throw IllegalStateException("сектор за файлом")
            out.write(file, from, sectorSize)
            s = if (s < fat.size) fat[s] else SECT_END
        }
        val all = out.toByteArray()
        val n = if (size >= 0) size.toInt() else all.size
        if (n > all.size) throw IllegalStateException("цепочка короче размера")
        return all.copyOf(n)
    }

    private fun readMiniChain(start: Int, size: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var s = start
        var hops = 0
        val seen = HashSet<Int>()
        while (s != SECT_END && s != SECT_FREE) {
            if (s < 0 || (s + 1) * miniSectorSize > miniStream.size || !seen.add(s) || hops++ > miniStream.size / miniSectorSize + 1) {
                throw IllegalStateException("порванная мини-цепочка")
            }
            out.write(miniStream, s * miniSectorSize, miniSectorSize)
            s = if (s < miniFat.size) miniFat[s] else SECT_END
        }
        val all = out.toByteArray()
        if (size > all.size) throw IllegalStateException("мини-цепочка короче размера")
        return all.copyOf(size.toInt())
    }

    private fun ByteArray.toIntArray(): IntArray = IntArray(size / 4) { i ->
        (this[4 * i].toInt() and 0xFF) or ((this[4 * i + 1].toInt() and 0xFF) shl 8) or
            ((this[4 * i + 2].toInt() and 0xFF) shl 16) or ((this[4 * i + 3].toInt() and 0xFF) shl 24)
    }
}

/** Запись каталога: имя в UTF-16, тип, стартовый сектор и размер потока. */
private class DirEntry(b: ByteArray) {
    val type = b[0x42].toInt()
    val name: String
    val start: Int
    val size: Long

    init {
        val nameBytes = ((b[0x40].toInt() and 0xFF) or ((b[0x41].toInt() and 0xFF) shl 8)) - 2
        name = if (nameBytes in 2..64) String(b, 0, nameBytes, Charsets.UTF_16LE) else ""
        start = (b[0x74].toInt() and 0xFF) or ((b[0x75].toInt() and 0xFF) shl 8) or
            ((b[0x76].toInt() and 0xFF) shl 16) or ((b[0x77].toInt() and 0xFF) shl 24)
        size = le32Long(b, 0x78) or (le32Long(b, 0x7C) shl 32)
    }
}

/** Четыре байта little-endian в Long без расширения знака байта. */
private fun le32Long(b: ByteArray, off: Int): Long =
    (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

// --- BIFF8: записи книги. Заголовок записи - идентификатор (2) и длина (2). ------

private const val R_BOF = 0x0809
private const val R_EOF = 0x000A
private const val R_SST = 0x00FC
private const val R_CONTINUE = 0x003C
private const val R_LABELSST = 0x00FD
private const val R_NUMBER = 0x0203
private const val R_RK = 0x027E
private const val R_MULRK = 0x00BD
private const val R_LABEL = 0x0204
private const val R_XF = 0x00E0

/** Встроенные форматы дат Excel (14-22, 27-36, 45-47, 50-58) - тот же набор, что у xlsx. */
private val BUILTIN_DATE_FORMATS = (14..22).toSet() + (27..36).toSet() + (45..47).toSet() + (50..58).toSet()

/**
 * Первый лист книги: строки и столбцы ячеек в текст. Глобальная секция даёт общие
 * строки (SST), лист - ячейки LABELSST и NUMBER. Второй и дальше листы не читаются
 * (как у xlsx-ридера).
 */
private fun readSheet(biff: ByteArray): List<List<String>> {
    val sst = mutableListOf<String>()
    val cells = TreeMap<Int, TreeMap<Int, String>>()
    val dateXfs = mutableSetOf<Int>()
    var xfIndex = 0
    var sheetNo = 0
    var inGlobals = false
    var inFirstSheet = false
    var pos = 0
    while (pos + 4 <= biff.size) {
        val id = le16(biff, pos)
        val len = le16(biff, pos + 2)
        var next = pos + 4 + len
        if (next > biff.size) break
        when (id) {
            R_BOF -> {
                when (le16(biff, pos + 6)) {
                    0x0005 -> inGlobals = true
                    0x0010 -> {
                        sheetNo++
                        inFirstSheet = sheetNo == 1
                    }
                }
            }
            R_EOF -> {
                inGlobals = false
                inFirstSheet = false
            }
            R_SST -> {
                val (chunks, end) = collectSstChunks(biff, pos)
                if (inGlobals) readSst(chunks, sst)
                next = end
            }
            R_XF ->
                if (inGlobals) {
                    // Индекс стиля = порядковый номер записи; формат дат - второй инт.
                    if (le16(biff, pos + 6) in BUILTIN_DATE_FORMATS) dateXfs += xfIndex
                    xfIndex++
                }
            R_LABELSST ->
                if (inFirstSheet) {
                    val isst = le32(biff, pos + 10)
                    sst.getOrNull(isst)?.let { s ->
                        put(cells, le16(biff, pos + 4), le16(biff, pos + 6), s)
                    }
                }
            R_NUMBER ->
                if (inFirstSheet) {
                    val v = doubleAt(biff, pos + 10)
                    val xf = le16(biff, pos + 8)
                    val text = if (xf in dateXfs) serialToDate(v) else numberText(v)
                    put(cells, le16(biff, pos + 4), le16(biff, pos + 6), text)
                }
            R_RK ->
                if (inFirstSheet) {
                    val xf = le16(biff, pos + 8)
                    val v = rkValue(le32(biff, pos + 10))
                    val text = if (xf in dateXfs) serialToDate(v) else numberText(v)
                    put(cells, le16(biff, pos + 4), le16(biff, pos + 6), text)
                }
            R_MULRK ->
                if (inFirstSheet) {
                    val row = le16(biff, pos + 4)
                    var col = le16(biff, pos + 6)
                    val count = (len - 6) / 6
                    for (i in 0 until count) {
                        val at = pos + 8 + i * 6
                        val xf = le16(biff, at)
                        val v = rkValue(le32(biff, at + 2))
                        val text = if (xf in dateXfs) serialToDate(v) else numberText(v)
                        put(cells, row, col, text)
                        col++
                    }
                }
            R_LABEL ->
                if (inFirstSheet) {
                    val cch = le16(biff, pos + 10)
                    val flags = biff[pos + 12].toInt()
                    put(cells, le16(biff, pos + 4), le16(biff, pos + 6), inlineString(biff, pos + 13, cch, flags))
                }
        }
        pos = next
    }
    return cells.values.map { cols -> (0..cols.lastKey()).map { cols[it] ?: "" } }
}

/** Строка в ячейке: 16 бит на знак или латиница одним байтом. */
private fun inlineString(b: ByteArray, off: Int, cch: Int, flags: Int): String {
    if (flags and 0x01 != 0) {
        val sb = StringBuilder(cch)
        for (i in 0 until cch) sb.append(le16(b, off + i * 2).toChar())
        return sb.toString()
    }
    return String(b, off, cch, Charsets.ISO_8859_1)
}

/**
 * RK - спрессованное число: бит 0 - делить на 100, бит 1 - целое в старших 30 битах,
 * иначе старшие 30 бит - верх половины double (низ нулевой).
 */
private fun rkValue(raw: Int): Double {
    val div100 = raw and 0x01 != 0
    val value = if (raw and 0x02 != 0) {
        (raw shr 2).toDouble()
    } else {
        Double.fromBits((raw.toLong() and 0xFFFFFFFCL) shl 32)
    }
    return if (div100) value / 100 else value
}

/** Serial Excel - дни с 1899-12-30 (учтён ложный високос 1900 года), как у xlsx-ридера. */
private fun serialToDate(v: Double): String =
    LocalDate.of(1899, 12, 30).plusDays(Math.round(v).toLong()).toString()

/** SST и его CONTINUE-записи подряд: границы записей важны для чтения строк. */
private fun collectSstChunks(biff: ByteArray, pos: Int): Pair<List<ByteArray>, Int> {
    val chunks = mutableListOf<ByteArray>()
    var p = pos
    while (p + 4 <= biff.size) {
        val id = le16(biff, p)
        val len = le16(biff, p + 2)
        val want = if (chunks.isEmpty()) R_SST else R_CONTINUE
        if (id != want || p + 4 + len > biff.size) break
        chunks += biff.copyOfRange(p + 4, p + 4 + len)
        p += 4 + len
    }
    return chunks to p
}

private fun readSst(chunks: List<ByteArray>, out: MutableList<String>) {
    val c = ChunkCursor(chunks)
    val unique = c.u32() // общее число ссылок не нужно, дальше - сами строки
    c.u32()
    repeat(unique.coerceAtLeast(0)) { out += c.string() }
}

/**
 * Курсор по цепочке записей SST: строка может рваться посередине - тогда следующая
 * запись (CONTINUE) начинается свежим байтом флага 8/16 бит, а мусор форматов
 * (runs, phonetic) переходит границу молча. Это правило - самое капризное место
 * формата.
 */
private class ChunkCursor(private val chunks: List<ByteArray>) {
    var part = 0
    var off = 0

    fun byte(): Int {
        if (off >= chunks[part].size) {
            part++
            off = 0
            if (part >= chunks.size) throw IllegalStateException("SST оборван")
        }
        return chunks[part][off++].toInt() and 0xFF
    }

    fun u16(): Int = byte() or (byte() shl 8)

    fun u32(): Int = byte() or (byte() shl 8) or (byte() shl 16) or (byte() shl 24)

    fun string(): String {
        val cch = u16()
        val flags = byte()
        var wide = flags and 0x01 != 0
        val runs = if (flags and 0x08 != 0) u16() else 0
        val ext = flags and 0x04 != 0
        val sb = StringBuilder(cch.coerceAtMost(1_000_000))
        for (i in 0 until cch) {
            if (off >= chunks[part].size && part + 1 < chunks.size) {
                part++
                off = 0
                wide = byte() and 0x01 != 0
            }
            sb.append(if (wide) u16().toChar() else byte().toChar())
        }
        repeat(runs * 4) { byte() }
        if (ext) {
            val extLen = u32()
            repeat(extLen.coerceAtMost(1_000_000)) { byte() }
        }
        return sb.toString()
    }
}

private fun put(cells: TreeMap<Int, TreeMap<Int, String>>, row: Int, col: Int, text: String) {
    cells.getOrPut(row) { TreeMap() }[col] = text
}

/** Число для таблицы: целое без «.0», дробь как есть (62.4, 1850). */
private fun numberText(v: Double): String =
    if (v == Math.floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()

private fun le16(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

private fun le32(b: ByteArray, off: Int): Int =
    le16(b, off) or (le16(b, off + 2) shl 16)

private fun doubleAt(b: ByteArray, off: Int): Double {
    var bits = 0L
    for (i in 7 downTo 0) bits = (bits shl 8) or (b[off + i].toLong() and 0xFF)
    return Double.fromBits(bits)
}
