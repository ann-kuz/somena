package ru.somena.core

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Мини-ридер старого xls (Excel 97-2003): OLE2-контейнер + BIFF8. Файлы собираются
 * прямо в тесте - как в XlsxReaderTest, только обёртка не zip, а compound file.
 * Исходник затеи - выгрузка Fitdays: старый бинарный xls под именем .csv.
 */
class XlsReaderTest {

    // --- Сборка BIFF8: записи глобальной секции и листа. ------------------------

    private fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun le32(v: Int): ByteArray = le16(v and 0xFFFF) + le16((v ushr 16) and 0xFFFF)

    private fun le64(v: Double): ByteArray {
        var bits = java.lang.Double.doubleToRawLongBits(v)
        val b = ByteArray(8)
        for (i in 0 until 8) {
            b[i] = (bits and 0xFF).toByte()
            bits = bits ushr 8
        }
        return b
    }

    private fun rec(id: Int, data: ByteArray): ByteArray = le16(id) + le16(data.size) + data

    private fun bof(type: Int): ByteArray = rec(0x0809, le16(0x0600) + le16(type) + ByteArray(12))

    private val eof = rec(0x000A, ByteArray(0))

    /** Общая строка таблицы: строка в SST (8 бит для латиницы, 16 для остального). */
    private fun labelSst(row: Int, col: Int, sst: Int, xf: Int = 0): ByteArray =
        rec(0x00FD, le16(row) + le16(col) + le16(xf) + le32(sst))

    private fun number(row: Int, col: Int, v: Double, xf: Int = 0): ByteArray =
        rec(0x0203, le16(row) + le16(col) + le16(xf) + le64(v))

    /** RK: 4 байта - целое или усечённый double, флаги в двух младших битах. */
    private fun rkCell(row: Int, col: Int, rk: Int, xf: Int = 0): ByteArray =
        rec(0x027E, le16(row) + le16(col) + le16(xf) + le32(rk))

    /** MULRK: несколько RK подряд на соседние колонки. */
    private fun mulRk(row: Int, colFirst: Int, rks: List<Int>, colLast: Int): ByteArray {
        var data = le16(row) + le16(colFirst)
        rks.forEach { data += le16(0) + le32(it) }
        return rec(0x00BD, data + le16(colLast))
    }

    /** LABEL: строка прямо в ячейке (не через SST), 8 или 16 бит по флагу. */
    private fun label(row: Int, col: Int, text: String, xf: Int = 0): ByteArray {
        val wide = text.any { it.code > 0xFF }
        var chars = ByteArray(0)
        text.forEach { ch -> chars += if (wide) le16(ch.code) else byteArrayOf(ch.code.toByte()) }
        return rec(
            0x0204,
            le16(row) + le16(col) + le16(xf) + le16(text.length) +
                byteArrayOf(if (wide) 1 else 0) + chars,
        )
    }

    /** SST с переносами CONTINUE посреди строки: на границе записи - новый байт флага. */
    private fun sstRecords(strings: List<String>): ByteArray {
        val parts = mutableListOf(ByteArrayOutputStream())
        fun roll(n: Int): Boolean {
            if (parts.last().size() + n <= 8224) return false
            parts.add(ByteArrayOutputStream())
            return true
        }
        parts.last().write(le32(strings.size))
        parts.last().write(le32(strings.size))
        for (s in strings) {
            var wide = s.any { it.code > 0xFF }
            roll(3)
            parts.last().write(le16(s.length))
            parts.last().write(byteArrayOf(if (wide) 1 else 0))
            for (ch in s) {
                if (wide) {
                    if (roll(2)) parts.last().write(byteArrayOf(1))
                    parts.last().write(le16(ch.code))
                } else {
                    if (roll(1)) parts.last().write(byteArrayOf(0))
                    parts.last().write(byteArrayOf(ch.code.toByte()))
                }
            }
        }
        var out = ByteArray(0)
        parts.forEachIndexed { i, p -> out += rec(if (i == 0) 0x00FC else 0x003C, p.toByteArray()) }
        return out
    }

    /** Книга: глобальная секция (SST, стили) + первый лист, дальше - дополнительные секции. */
    private fun workbook(sst: List<String>, xfs: List<Int> = emptyList(), vararg sections: ByteArray): ByteArray {
        var globalsBytes = bof(0x0005)
        xfs.forEach { fmt -> globalsBytes += rec(0x00E0, le16(0) + le16(fmt) + ByteArray(16)) }
        if (sst.isNotEmpty()) globalsBytes += sstRecords(sst)
        globalsBytes += eof
        var out = globalsBytes
        sections.forEach { out += it }
        return out
    }

    private fun sheet(vararg cells: ByteArray): ByteArray {
        var out = bof(0x0010)
        cells.forEach { out += it }
        out += eof
        return out
    }
    // --- Сборка OLE2-контейнера (compound file v3, сектора 512, мини-сектора 64). ---

    private val FREE = -1
    private val ENDOFCHAIN = -2
    private val FATSECT = -3

    /** Минимальный правильный контейнер: маленькие потоки - в мини-стриме, большие и
     *  каталог - в обычных секторах (каталог всегда в FAT, как пишут Excel и POI). */
    private fun ole2(streams: List<Pair<String, ByteArray>>): ByteArray {
        val cutoff = 4096
        val dirSize = 128 * (streams.size + 1)
        val minis = streams.filter { it.second.size in 1 until cutoff }
        val bigs = streams.filter { it.second.size >= cutoff }

        val miniStart = mutableMapOf<String, Int>()
        var miniUsed = 0
        minis.forEach { (name, data) ->
            miniStart[name] = miniUsed
            miniUsed += (data.size + 63) / 64
        }
        val miniBytes = ByteArray(miniUsed * 64)
        var miniFat = IntArray(miniUsed) { FREE }

        fun miniChain(start: Int, size: Int) {
            val count = (size + 63) / 64
            for (i in 0 until count) miniFat[start + i] = if (i == count - 1) ENDOFCHAIN else start + i + 1
        }

        var nextSector = 0
        fun alloc(count: Int): Int = nextSector.also { nextSector += count }

        val rootStart = if (miniUsed > 0) alloc((miniUsed * 64 + 511) / 512) else ENDOFCHAIN
        val rootSectors = if (miniUsed > 0) (miniUsed * 64 + 511) / 512 else 0
        val miniFatStart = if (miniUsed > 0) alloc((miniUsed * 4 + 511) / 512) else ENDOFCHAIN
        val miniFatSectors = if (miniUsed > 0) (miniUsed * 4 + 511) / 512 else 0
        val dirStart = alloc((dirSize + 511) / 512)
        val dirSectors = (dirSize + 511) / 512
        val bigStart = mutableMapOf<String, Int>()
        bigs.forEach { (name, data) -> bigStart[name] = alloc((data.size + 511) / 512) }

        // FAT: секторов должно хватать на себя же.
        var fatCount = 1
        while (fatCount * 128 < nextSector + fatCount) fatCount++
        val fatSectorIds = (0 until fatCount).map { nextSector++ }

        val total = nextSector
        val fat = IntArray(total) { FREE }

        fun chain(start: Int, count: Int) {
            if (count == 0) return
            for (i in 0 until count) fat[start + i] = if (i == count - 1) ENDOFCHAIN else start + i + 1
        }

        chain(rootStart, rootSectors)
        chain(miniFatStart, miniFatSectors)
        chain(dirStart, dirSectors)
        bigs.forEach { (name, data) -> chain(bigStart[name]!!, (data.size + 511) / 512) }
        fatSectorIds.forEach { s -> fat[s] = FATSECT }

        // Записи каталога: корень + потоки правыми братьями.
        fun entry(name: String, type: Int, start: Int, size: Int, child: Int = -1, right: Int = -1): ByteArray {
            val b = ByteArray(128)
            val nameBytes = (name + "\u0000").toByteArray(Charsets.UTF_16LE)
            nameBytes.copyInto(b, 0)
            le16((name.length + 1) * 2).copyInto(b, 0x40)
            b[0x42] = type.toByte()
            b[0x43] = 1
            le32(-1).copyInto(b, 0x44) // левый брат
            le32(if (right >= 0) right else -1).copyInto(b, 0x48)
            le32(if (child >= 0) child else -1).copyInto(b, 0x4C)
            le32(start).copyInto(b, 0x74)
            le32(size).copyInto(b, 0x78)
            return b
        }

        fun startOf(name: String): Int = miniStart[name] ?: bigStart[name] ?: ENDOFCHAIN

        val entries = ByteArray(dirSize)
        entry(
            "Root Entry", 5, if (rootStart >= 0) rootStart else ENDOFCHAIN, miniUsed * 64,
            child = if (streams.isNotEmpty()) 1 else -1,
        ).copyInto(entries, 0)
        streams.forEachIndexed { i, (name, data) ->
            entry(name, 2, startOf(name), data.size, right = if (i + 2 <= streams.size) i + 2 else -1)
                .copyInto(entries, (i + 1) * 128)
        }
        minis.forEach { (name, data) ->
            data.copyInto(miniBytes, miniStart[name]!! * 64)
            miniChain(miniStart[name]!!, data.size)
        }

        // Сектора файла: контейнер мини-стрима, miniFAT, большие потоки, FAT.
        val sectors = Array(total) { ByteArray(512) }
        fun put(start: Int, data: ByteArray) {
            var off = 0
            var s = start
            while (off < data.size) {
                val len = minOf(512, data.size - off)
                data.copyInto(sectors[s], 0, off, off + len)
                off += len
                s++
            }
        }

        if (rootSectors > 0) put(rootStart, miniBytes)
        if (miniFatSectors > 0) {
            val fatBytes = ByteArray(miniFatSectors * 512)
            for (i in miniFat.indices) le32(miniFat[i]).copyInto(fatBytes, i * 4)
            put(miniFatStart, fatBytes)
        }
        put(dirStart, entries)
        bigs.forEach { (name, data) -> put(bigStart[name]!!, data) }
        fatSectorIds.forEachIndexed { fi, s ->
            for (i in 0 until 128) {
                val idx = fi * 128 + i
                le32(if (idx < fat.size) fat[idx] else FREE).copyInto(sectors[s], i * 4)
            }
        }

        // Заголовок: DIFAT из 109 ячеек, дальше FREE.
        val header = ByteArray(512)
        byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte(),
        ).copyInto(header, 0)
        le16(0x0009).copyInto(header, 0x1E) // сектор 512
        le16(0x0006).copyInto(header, 0x20) // мини-сектор 64
        le32(fatCount).copyInto(header, 0x2C)
        le32(dirStart).copyInto(header, 0x30)
        le32(cutoff).copyInto(header, 0x38)
        le32(miniFatStart).copyInto(header, 0x3C)
        le32(miniFatSectors).copyInto(header, 0x40)
        le32(ENDOFCHAIN).copyInto(header, 0x44)
        le32(0).copyInto(header, 0x48)
        fatSectorIds.forEachIndexed { i, s -> le32(s).copyInto(header, 0x4C + i * 4) }
        for (i in fatSectorIds.size until 109) le32(FREE).copyInto(header, 0x4C + i * 4)

        var out = header
        sectors.forEach { out += it }
        return out
    }

    private fun xls(sst: List<String>, xfs: List<Int> = emptyList(), sections: List<ByteArray> = emptyList()): ByteArray =
        ole2(listOf("Workbook" to workbook(sst, xfs, *sections.toTypedArray())))

    @Test
    fun `простой xls читается - заголовок и числа на месте`() {
        val bytes = xls(
            sst = listOf("Дата", "Вес"),
            sections = listOf(
                sheet(
                    labelSst(0, 0, 0),
                    labelSst(0, 1, 1),
                    number(1, 0, 62.4),
                    number(1, 1, 1850.0),
                ),
            ),
        )
        val text = decodeXlsText(bytes)
        assertEquals("Дата\tВес\n62.4\t1850", text)
    }

    @Test
    fun `пустая ячейка между заполненными читается дыркой`() {
        val bytes = xls(
            sst = listOf("Вес", "Заметка"),
            sections = listOf(
                sheet(
                    labelSst(0, 0, 0),
                    labelSst(0, 2, 1),
                    number(1, 1, 63.1),
                ),
            ),
        )
        assertEquals("Вес\t\tЗаметка\n\t63.1", decodeXlsText(bytes))
    }

    @Test
    fun `дата из числовой ячейки со стилем даты становится ISO`() {
        // Порука формата: serial 2026-06-01, стиль XF#1 с форматом 14 (дата).
        val serial = java.time.temporal.ChronoUnit.DAYS
            .between(java.time.LocalDate.of(1899, 12, 30), java.time.LocalDate.of(2026, 6, 1))
        val bytes = xls(
            sst = listOf("Дата", "Вес"),
            xfs = listOf(0, 14),
            sections = listOf(
                sheet(
                    labelSst(0, 0, 0),
                    labelSst(0, 1, 1),
                    number(1, 0, serial.toDouble(), xf = 1),
                    number(1, 1, 62.4),
                ),
            ),
        )
        assertEquals("Дата\tВес\n2026-06-01\t62.4", decodeXlsText(bytes))
    }

    @Test
    fun `второй лист не читается - как у xlsx`() {
        val bytes = xls(
            sst = listOf("Первый", "Второй"),
            sections = listOf(
                sheet(labelSst(0, 0, 0)),
                bof(0x0010) + labelSst(1, 1, 1) + eof,
            ),
        )
        assertEquals("Первый", decodeXlsText(bytes))
    }

    @Test
    fun `rk и mulrk читаются - целое деленное на сто и усеченный double`() {
        val bytes = xls(
            sst = emptyList(),
            sections = listOf(
                sheet(
                    rkCell(0, 0, (1200 shl 2) or 0x02), // целое 1200
                    rkCell(0, 1, (7280 shl 2) or 0x03), // 7280/100 = 72.8
                    rkCell(0, 2, 0x404F4000.toInt()),   // верх double 62.5
                    mulRk(1, 0, listOf((1850 shl 2) or 0x02, (6240 shl 2) or 0x03), 1),
                ),
            ),
        )
        assertEquals("1200\t72.8\t62.5\n1850\t62.4", decodeXlsText(bytes))
    }

    @Test
    fun `строка в LABEL читается - русские идут шестнадцатью битами`() {
        val bytes = xls(
            sst = emptyList(),
            sections = listOf(
                sheet(
                    label(0, 0, "Дата, время"),
                    label(0, 1, "08:32 01/06/2026"),
                ),
            ),
        )
        assertEquals("Дата, время\t08:32 01/06/2026", decodeXlsText(bytes))
    }

    @Test
    fun `SST рвется записью CONTINUE посреди строки и читается целиком`() {
        val long = "А".repeat(6000) + "Я" // 12002 байта знаков - не влезает в одну запись
        val bytes = xls(
            sst = listOf(long, "хвост"),
            sections = listOf(sheet(labelSst(0, 0, 0), labelSst(1, 0, 1))),
        )
        assertEquals(long + "\nхвост", decodeXlsText(bytes))
    }

    @Test
    fun `книга длиннее порога лежит в обычных секторах и читается`() {
        val wide = "x".repeat(4500) // SST больше 4096 - поток уходит из мини-стрима в FAT
        val bytes = xls(
            sst = listOf(wide),
            sections = listOf(sheet(labelSst(0, 0, 0))),
        )
        assertEquals(wide, decodeXlsText(bytes))
    }

    @Test
    fun `ole2-магия ведет в xls-ридер а мусор внутри дает null`() {
        val good = xls(
            sst = listOf("вес"),
            sections = listOf(sheet(labelSst(0, 0, 0))),
        )
        assertEquals("вес", decodeTableBytes(good))
        val broken = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte(),
        ) + "мусор".toByteArray()
        assertNull(decodeTableBytes(broken))
    }
}
