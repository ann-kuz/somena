package ru.somena.core

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocReaderTest {

    /** Минимальный настоящий .doc: заголовок OLE2, FAT, директория и потоки. */
    private fun buildDoc(
        text: String,
        compressedCp1251: Boolean,
        whichTable: Boolean,
        tableInMiniStream: Boolean,
    ): ByteArray {
        val tableName = if (whichTable) "1Table" else "0Table"
        val textBytes = if (compressedCp1251) {
            text.toByteArray(charset("windows-1251"))
        } else {
            text.toByteArray(StandardCharsets.UTF_16LE)
        }
        val charCount = if (compressedCp1251) textBytes.size else textBytes.size / 2
        val textOffset = 0x400

        // FIB: имя потока таблицы (бит 0x0200) и адрес CLX (fcClx/lcbClx).
        // PlcPcd одного куска: (n+1)*4 байт CP + n*8 байт PCD, при n=1 это 16.
        val plc = plcPcd(textOffset, charCount, compressedCp1251)
        val clx = byteArrayOf(0x02) + le32(plc.size.toLong()) + plc
        // WordDocument у настоящего Word всегда длиннее отсечки мини-потока.
        val word = ByteArray(0x1200)
        le16Into(word, 0x0A, if (whichTable) 0x0200 else 0)
        le32Into(word, 0x1A2, 0L)                   // fcClx: CLX в начале потока таблицы
        le32Into(word, 0x1A6, clx.size.toLong())    // lcbClx
        System.arraycopy(textBytes, 0, word, textOffset, textBytes.size)

        val FREE = -1
        val END = -2
        val sectors = ArrayList<ByteArray>()

        fun alloc(data: ByteArray): Int {
            val id = sectors.size
            var pos = 0
            while (pos < data.size || (data.isEmpty() && pos == 0)) {
                val s = ByteArray(512)
                val take = minOf(512, data.size - pos).coerceAtLeast(0)
                if (take > 0) System.arraycopy(data, pos, s, 0, take)
                sectors.add(s)
                pos += 512
                if (data.isEmpty()) break
            }
            return id
        }

        val fatId = alloc(ByteArray(0)) // заглушка, сектор 0
        val dirId = alloc(ByteArray(0)) // заглушка, сектор 1
        val needMini = tableInMiniStream
        val miniFatId = if (needMini) alloc(ByteArray(0)) else -1

        val miniBytes = if (needMini) ByteArray((clx.size + 63) / 64 * 64).also {
            System.arraycopy(clx, 0, it, 0, clx.size)
        } else ByteArray(0)
        val miniSectorCount = if (needMini) miniBytes.size / 64 else 0

        val miniStreamIds = if (needMini) {
            List(miniSectorCount) { alloc(miniBytes.copyOfRange(it * 64, (it + 1) * 64)) }
        } else emptyList()
        val wordIds = List(word.size / 512) { alloc(word.copyOfRange(it * 512, (it + 1) * 512)) }
        // Вне мини-потока поток обязан быть длиннее отсечки: после CLX в таблице
        // живёт и другая ерунда Word, добиваем нулями как настоящий 0Table.
        val tableBytes = if (needMini) clx else ByteArray(0x1200).also {
            System.arraycopy(clx, 0, it, 0, clx.size)
        }
        val tableIds = if (!needMini) {
            List(tableBytes.size / 512) { alloc(tableBytes.copyOfRange(it * 512, (it + 1) * 512)) }
        } else emptyList()

        // FAT: цепочки потоков + сами сектора служебные.
        val fat = IntArray(sectors.size * 4) { FREE }
        fat[fatId] = -3
        fat[dirId] = END
        if (miniFatId >= 0) fat[miniFatId] = END
        fun link(ids: List<Int>) {
            for (i in ids.indices) fat[ids[i]] = if (i == ids.lastIndex) END else ids[i + 1]
        }
        link(miniStreamIds)
        link(wordIds)
        link(tableIds)
        val fatSector = ByteArray(512)
        fat.forEachIndexed { i, v -> le32Into(fatSector, i * 4, v.toLong() and 0xFFFFFFFFL) }
        sectors[fatId] = fatSector

        val miniFatSector = ByteArray(512)
        if (needMini) {
            for (i in 0 until miniSectorCount) {
                le32Into(miniFatSector, i * 4, (if (i == miniSectorCount - 1) END else i + 1).toLong())
            }
            sectors[miniFatId] = miniFatSector
        }

        // Директория: корень (мини-поток), WordDocument и таблица, правое поддерево.
        fun entry(name: String, type: Int, left: Int, right: Int, child: Int, start: Int, size: Long): ByteArray {
            val e = ByteArray(128)
            val nameBytes = (name + "\u0000").toByteArray(StandardCharsets.UTF_16LE)
            System.arraycopy(nameBytes, 0, e, 0, nameBytes.size)
            le16Into(e, 0x40, nameBytes.size)
            e[0x42] = type.toByte()
            le32Into(e, 0x44, left.toLong() and 0xFFFFFFFFL)
            le32Into(e, 0x48, right.toLong() and 0xFFFFFFFFL)
            le32Into(e, 0x4C, child.toLong() and 0xFFFFFFFFL)
            le32Into(e, 0x74, start.toLong() and 0xFFFFFFFFL)
            le32Into(e, 0x78, size)
            return e
        }

        val rootStart = if (needMini) miniStreamIds.first() else END
        val rootSize = if (needMini) miniBytes.size.toLong() else 0L
        val dir = ByteArray(512)
        System.arraycopy(entry("Root Entry", 5, -1, -1, 1, rootStart, rootSize), 0, dir, 0, 128)
        System.arraycopy(entry("WordDocument", 2, -1, 2, -1, wordIds.first(), word.size.toLong()), 0, dir, 128, 128)
        val tableStart = if (needMini) 0 else tableIds.first()
        System.arraycopy(
            entry(tableName, 2, -1, -1, -1, tableStart, tableBytes.size.toLong()),
            0, dir, 256, 128,
        )
        sectors[dirId] = dir

        // Заголовок файла.
        val header = ByteArray(512)
        val magic = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte())
        System.arraycopy(magic, 0, header, 0, 8)
        le16Into(header, 0x1E, 9)   // сектор 512
        le16Into(header, 0x20, 6)   // мини-сектор 64
        le32Into(header, 0x2C, 1)   // один сектор FAT
        le32Into(header, 0x30, dirId.toLong())
        le32Into(header, 0x38, 4096)
        le32Into(header, 0x3C, (if (needMini) miniFatId else FREE).toLong() and 0xFFFFFFFFL)
        le32Into(header, 0x40, (if (needMini) 1 else 0).toLong())
        le32Into(header, 0x44, FREE.toLong() and 0xFFFFFFFFL)
        le32Into(header, 0x48, 0)
        le32Into(header, 0x4C, fatId.toLong()) // DIFAT[0], остальные FREE
        for (i in 1 until 109) le32Into(header, 0x4C + i * 4, FREE.toLong() and 0xFFFFFFFFL)

        return header + sectors.fold(ByteArray(0)) { acc, s -> acc + s }
    }

    /** PlcPcd одного куска: CP 0..charCount и PCD с адресом текста. */
    private fun plcPcd(textOffset: Int, charCount: Int, compressed: Boolean): ByteArray {
        val fc = if (compressed) (textOffset * 2 or 0x40000000).toLong() else textOffset.toLong()
        return le32(0) + le32(charCount.toLong()) + byteArrayOf(0, 0) + le32(fc) + byteArrayOf(0, 0)
    }

    private fun le32(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    private fun le16Into(dst: ByteArray, offset: Int, v: Int) {
        dst[offset] = (v and 0xFF).toByte()
        dst[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun le32Into(dst: ByteArray, offset: Int, v: Long) {
        val b = le32(v)
        System.arraycopy(b, 0, dst, offset, 4)
    }

    @Test
    fun `doc со сжатым cp1251 текстом читается из 0Table`() {
        val text = "Тестовый документ поликлиники"
        assertEquals(text, decodeDocText(buildDoc(text, compressedCp1251 = true, whichTable = false, tableInMiniStream = false)))
    }

    @Test
    fun `doc с utf16 текстом читается из 1Table в мини-потоке`() {
        val text = "Гемоглобин 134 г/л"
        assertEquals(text, decodeDocText(buildDoc(text, compressedCp1251 = false, whichTable = true, tableInMiniStream = true)))
    }

    @Test
    fun `мусор и обрубок дают null`() {
        assertNull(decodeDocText("не doc вовсе".toByteArray()))
        val whole = buildDoc("Тест", compressedCp1251 = true, whichTable = false, tableInMiniStream = false)
        assertNull(decodeDocText(whole.copyOf(600)))
    }
}
