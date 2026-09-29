package ru.somena.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Дерево Хранилища (спека 0010): видимые строки из кэша уровней и раскрытых папок. */
class StorageTreeTest {

    @Test
    fun `корень показывает один уровень - папки вперёд файлов по алфавиту`() {
        val root = DirChildren(
            folders = listOf(entry("b", "биохимия", dir = true), entry("a", "анализы", dir = true)),
            files = listOf(entry("f2", "узи.pdf"), entry("f1", "анализ.pdf")),
        )
        val rows = treeRows(root, children = emptyMap(), expanded = emptySet())
        assertEquals(
            listOf("анализы", "биохимия", "анализ.pdf", "узи.pdf"),
            rows.map { it.entry.name },
        )
    }

    @Test
    fun `раскрытая папка вставляет детей под собой свёрнутая прячет поддерево`() {
        val root = DirChildren(
            folders = listOf(entry("a", "анализы", dir = true)),
            files = listOf(entry("f", "узи.pdf")),
        )
        val children = mapOf(
            "a" to DirChildren(
                folders = listOf(entry("a:2026", "2026", dir = true)),
                files = listOf(entry("a:кровь.pdf", "кровь.pdf")),
            ),
            "a:2026" to DirChildren(
                folders = emptyList(),
                files = listOf(entry("a:2026:глюкоза.jpg", "глюкоза.jpg")),
            ),
        )

        val collapsed = treeRows(root, children, expanded = emptySet())
        assertEquals(listOf("анализы", "узи.pdf"), collapsed.map { it.entry.name })

        val openOne = treeRows(root, children, expanded = setOf("a"))
        assertEquals(listOf("анализы", "2026", "кровь.pdf", "узи.pdf"), openOne.map { it.entry.name })
        assertEquals(listOf(0, 1, 1, 0), openOne.map { it.depth })

        val openBoth = treeRows(root, children, expanded = setOf("a", "a:2026"))
        assertEquals(
            listOf("анализы", "2026", "глюкоза.jpg", "кровь.pdf", "узи.pdf"),
            openBoth.map { it.entry.name },
        )
        assertEquals(listOf(0, 1, 2, 1, 0), openBoth.map { it.depth })
    }

    @Test
    fun `раскрытая но не загруженная папка помечена загрузкой корень без кэша пуст`() {
        val root = DirChildren(folders = listOf(entry("a", "анализы", dir = true)), files = emptyList())

        val pending = treeRows(root, children = emptyMap(), expanded = setOf("a"))
        val folderRow = pending.filterIsInstance<TreeRow.Folder>().single()
        assertTrue(folderRow.expanded)
        assertFalse(folderRow.loaded)

        val loaded = treeRows(
            root,
            children = mapOf("a" to DirChildren(emptyList(), listOf(entry("a:к.pdf", "к.pdf")))),
            expanded = setOf("a"),
        )
        assertTrue(loaded.filterIsInstance<TreeRow.Folder>().single().loaded)

        assertTrue(treeRows(null, children = emptyMap(), expanded = emptySet()).isEmpty())
    }

    private fun entry(id: String, name: String, dir: Boolean = false) =
        StorageEntry(id, name, isDir = dir, sizeBytes = if (dir) 0L else 10L)
}
