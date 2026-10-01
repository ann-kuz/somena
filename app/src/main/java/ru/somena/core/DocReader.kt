package ru.somena.core

import java.nio.charset.StandardCharsets

/**
 * Мини-ридер старого .doc (спека 0013): составной файл OLE2 и таблица кусков
 * Word 97, без тяжёлых библиотек - Apache POI утяжелил бы APK на мегабайты.
 * Читает заголовок, FAT, мини-FAT, директорию и потоки WordDocument + 0/1Table,
 * адрес таблицы кусков берёт из FIB (fcClx/lcbClx). Сжатые куски - cp1251
 * (кодировка русских Word-документов), несжатые - UTF-16LE. null - файл не
 * .doc или структура не разобралась: MedDocReader попросит пересохранить в
 * docx, отказ честнее полу-текста.
 */
fun decodeDocText(bytes: ByteArray): String? = try {
    DocBinary(bytes).text()
} catch (e: Exception) {
    null
}

private const val SECTOR_FREE = -1
private const val SECTOR_END = -2
private const val SECTOR_FAT = -3

/** Разобранный .doc: только то, что нужно для текста, без записи и стилей. */
private class DocBinary(private val bytes: ByteArray) {
    init {
        val magic = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte())
        if (bytes.size < 0x48 || !magic.indices.all { bytes[it] == magic[it] }) {
            error("это не составной файл OLE2")
        }
    }

    private val sectorSize: Int = (1 shl u16(0x1E)).also {
        if (it < 512 || it > 8192 || it and (it - 1) != 0) error("странный размер сектора")
    }
    private val miniSectorSize: Int = 1 shl u16(0x20)
    private val cutoff: Int = u32(0x38).toInt().also {
        if (it <= 0) error("отсечка мини-потока не читается")
    }
    private val fat: IntArray = readFat()
    private val entries: List<DirEntry> = readDirectory()
    private val miniFat: IntArray = readMiniFat()
    private val miniStream: ByteArray by lazy {
        val root = entries.firstOrNull { it.type == 5.toByte() } ?: error("нет корня директории")
        normalStream(root.start, root.size)
    }

    private fun u16(offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(offset: Int): Long =
        (bytes[offset].toInt() and 0xFF).toLong() or
            ((bytes[offset + 1].toInt() and 0xFF).toLong() shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF).toLong() shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF).toLong() shl 24)

    private fun sector(id: Int): ByteArray {
        val from = (id + 1) * sectorSize
        if (id < 0 || from < 0 || from + sectorSize > bytes.size) error("сектор за пределами файла")
        return bytes.copyOfRange(from, from + sectorSize)
    }

    /** Секторные номера DIFAT: 109 из заголовка плюс цепочка секторов DIFAT. */
    private fun difatSectors(): List<Int> {
        val out = ArrayList<Int>()
        for (i in 0 until 109) {
            val id = u32(0x4C + i * 4).toInt()
            if (id == SECTOR_FREE) return out
            out.add(id)
        }
        var id = u32(0x44).toInt()
        val perSector = sectorSize / 4 - 1
        while (id != SECTOR_FREE && id != SECTOR_END) {
            val data = sector(id)
            for (i in 0 until perSector) {
                val fatId = le32(data, i * 4).toInt()
                if (fatId != SECTOR_FREE) out.add(fatId)
            }
            id = le32(data, perSector * 4).toInt()
            if (out.size > 1_000_000) error("DIFAT зациклился")
        }
        return out
    }

    private fun readFat(): IntArray {
        val ids = difatSectors()
        val perSector = sectorSize / 4
        val out = IntArray(ids.size * perSector) { SECTOR_FREE }
        for (id in ids) {
            if (id == SECTOR_FREE || id == SECTOR_FAT) continue
            val data = sector(id)
            for (i in 0 until perSector) {
                val idx = id * perSector + i
                if (idx in out.indices) out[idx] = le32(data, i * 4).toInt()
            }
        }
        return out
    }

    private fun chain(start: Int, table: IntArray): List<Int> {
        val out = ArrayList<Int>()
        var id = start
        val seen = HashSet<Int>()
        while (id != SECTOR_END) {
            if (id < 0 || id >= table.size || !seen.add(id)) error("цепочка секторов битая")
            out.add(id)
            id = table[id]
        }
        return out
    }

    private fun normalStream(start: Int, size: Long): ByteArray {
        val out = ByteArray(size.toInt())
        var pos = 0
        for (id in chain(start, fat)) {
            if (pos >= out.size) break
            val data = sector(id)
            val take = minOf(sectorSize, out.size - pos)
            System.arraycopy(data, 0, out, pos, take)
            pos += take
        }
        return out
    }

    /** Цепочка целиком: у директории и мини-FAT размера в заголовке нет. */
    private fun chainBytes(start: Int): ByteArray {
        val parts = chain(start, fat).map { sector(it) }
        val out = ByteArray(parts.size * sectorSize)
        parts.forEachIndexed { i, data -> System.arraycopy(data, 0, out, i * sectorSize, sectorSize) }
        return out
    }

    /** Малые потоки (короче отсечки) живут в мини-потоке корня по мини-FAT. */
    private fun smallStream(start: Int, size: Long): ByteArray {
        val out = ByteArray(size.toInt())
        var pos = 0
        for (id in chain(start, miniFat)) {
            if (pos >= out.size) break
            val from = id * miniSectorSize
            val take = minOf(miniSectorSize, out.size - pos)
            System.arraycopy(miniStream, from, out, pos, take)
            pos += take
        }
        return out
    }

    private fun stream(name: String): ByteArray? {
        val entry = entries.firstOrNull { it.name == name && it.type == 2.toByte() } ?: return null
        if (entry.size == 0L) return ByteArray(0)
        return if (entry.size >= cutoff) normalStream(entry.start, entry.size)
        else smallStream(entry.start, entry.size)
    }

    private class DirEntry(
        val name: String,
        val type: Byte,
        val left: Int,
        val right: Int,
        val child: Int,
        val start: Int,
        val size: Long,
    )

    private fun readDirectory(): List<DirEntry> {
        val dir = chainBytes(u32(0x30).toInt())
        val count = dir.size / 128
        val raw = (0 until count).map { i ->
            val base = i * 128
            val nameLen = le16(dir, base + 0x40)
            DirEntry(
                name = if (nameLen >= 2) {
                    String(dir, base, nameLen - 2, StandardCharsets.UTF_16LE)
                } else "",
                type = dir[base + 0x42],
                left = le32(dir, base + 0x44).toInt(),
                right = le32(dir, base + 0x48).toInt(),
                child = le32(dir, base + 0x4C).toInt(),
                start = le32(dir, base + 0x74).toInt(),
                size = le32(dir, base + 0x78) or (le32(dir, base + 0x7C) shl 32),
            )
        }
        // Дерево детей (red-black): важен сам обход до потоков, порядок не критичен.
        val out = ArrayList<DirEntry>()
        fun visit(i: Int) {
            if (i !in raw.indices || raw[i].type == 0.toByte()) return
            visit(raw[i].left)
            out.add(raw[i])
            visit(raw[i].right)
        }
        val root = raw.firstOrNull { it.type == 5.toByte() } ?: error("нет корня директории")
        visit(root.child)
        return listOf(root) + out
    }

    private fun readMiniFat(): IntArray {
        val start = u32(0x3C).toInt()
        if (start == SECTOR_FREE || start == SECTOR_END) return IntArray(0)
        val mini = chainBytes(start)
        return IntArray(mini.size / 4) { i -> le32(mini, i * 4).toInt() }
    }

    /** Текст документа: куски CLX склеиваются в порядке CP. */
    fun text(): String {
        val word = stream("WordDocument") ?: error("нет потока WordDocument")
        if (word.size < 0x200) error("WordDocument подозрительно короткий")
        val flags = le16(word, 0x0A)
        val tableName = if (flags and 0x0200 != 0) "1Table" else "0Table"
        val table = stream(tableName) ?: stream("0Table") ?: stream("1Table")
            ?: error("нет потока $tableName")
        val fcClx = le32(word, 0x1A2).toInt()
        val lcbClx = le32(word, 0x1A6).toInt()
        if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > table.size) error("CLX вне потока")

        // CLX: сначала Prc-записи (пропускаем), затем Pcdt с PlcPcd.
        var i = 0
        var plc: ByteArray? = null
        while (i < lcbClx) {
            when (table[fcClx + i].toInt() and 0xFF) {
                0x01 -> i += 3 + le16(table, fcClx + i + 1)
                0x02 -> {
                    val lcb = le32(table, fcClx + i + 1).toInt()
                    val from = fcClx + i + 5
                    if (lcb < 0 || from + lcb > table.size) error("PlcPcd вне потока")
                    plc = table.copyOfRange(from, from + lcb)
                    i = lcbClx
                }
                else -> error("CLX не разобрался")
            }
        }
        val clx = plc ?: error("в CLX нет PlcPcd")

        val n = (clx.size - 4) / 12
        if (n < 1 || clx.size < (n + 1) * 4 + n * 8) error("PlcPcd пуст или бит")
        val cps = IntArray(n + 1) { k -> le32(clx, k * 4).toInt() }
        val pcdAt = clx.size - n * 8

        val out = StringBuilder()
        for (k in 0 until n) {
            val chars = cps[k + 1] - cps[k]
            if (chars <= 0) continue
            val fc = le32(clx, pcdAt + k * 8 + 2).toInt()
            if (fc and 0x40000000 != 0) {
                // Сжатый кусок: смещение - fc/2, по байту на символ, cp1251.
                val from = (fc and 0x3FFFFFFF) ushr 1
                if (from + chars > word.size) error("кусок текста вне потока")
                out.append(String(word, from, chars, CP1251))
            } else {
                val from = fc and 0x3FFFFFFF
                if (from + chars * 2 > word.size) error("кусок текста вне потока")
                out.append(String(word, from, chars * 2, StandardCharsets.UTF_16LE))
            }
        }
        return out.toString().trim()
    }
}

private fun le32(data: ByteArray, offset: Int): Long =
    (data[offset].toInt() and 0xFF).toLong() or
        ((data[offset + 1].toInt() and 0xFF).toLong() shl 8) or
        ((data[offset + 2].toInt() and 0xFF).toLong() shl 16) or
        ((data[offset + 3].toInt() and 0xFF).toLong() shl 24)

private fun le16(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
