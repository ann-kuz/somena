package ru.somena.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Обход Хранилища на вложенные папки (спека 0010): SAF отдаёт только прямых детей. */
class MedStorageWalkTest {

    @Test
    fun `файлы корня и вложенных папок собираются на всю глубину`() {
        val files = collectStorageFiles("root") { dir ->
            when (dir) {
                "root" -> listOf(
                    file("root:узи.pdf", "узи.pdf"),
                    folder("root:анализы", "анализы"),
                )
                "root:анализы" -> listOf(
                    file("root:анализы:кровь.pdf", "кровь.pdf"),
                    folder("root:анализы:2026", "2026"),
                )
                "root:анализы:2026" -> listOf(file("root:анализы:2026:глюкоза.jpg", "глюкоза.jpg"))
                else -> emptyList()
            }
        }
        // Путь папки от корня различает одинаковые имена из разных подпапок.
        assertEquals(listOf("узи.pdf", "кровь.pdf", "глюкоза.jpg"), files.map { it.entry.name })
        assertEquals(listOf(null, "анализы", "анализы/2026"), files.map { it.folder })
    }

    @Test
    fun `пустые папки исчезают из списка а не падают`() {
        val files = collectStorageFiles("root") { dir ->
            if (dir == "root") listOf(folder("root:пусто", "пусто")) else emptyList()
        }
        assertTrue(files.isEmpty())
    }

    private fun file(id: String, name: String) =
        StorageEntry(id, name, isDir = false, sizeBytes = 10, mime = "application/pdf")

    private fun folder(id: String, name: String) = StorageEntry(id, name, isDir = true)
}
