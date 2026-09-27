package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Индекс «файлы Хранилища ↔ записи» (спека 0010, тикет 02, ADR-0008). */
class MedFileIndexTest {

    private val day = LocalDate.of(2026, 5, 12)

    private val withFile = MedRecord(id = 1, kind = MedKind.ANALYSIS, date = day, fileUri = "content://tree/doc1", fileName = "анализ.pdf")
    private val manual = MedRecord(id = 2, kind = MedKind.PROTOCOL, date = day, diagnoses = listOf("А"))
    private val replaced = MedRecord(id = 3, kind = MedKind.EXAM, date = day, examType = "УЗИ", fileUri = "content://tree/doc2")

    @Test
    fun `бейдж разобран ставится файлу со ссылкой из записи`() {
        val index = MedFileIndex(listOf(withFile, manual, replaced), setOf("content://tree/doc1", "content://tree/doc2", "content://tree/doc3"))
        assertTrue(index.isParsed("content://tree/doc1"))
        assertTrue(index.isParsed("content://tree/doc2"))
        assertFalse(index.isParsed("content://tree/doc3"))
        assertEquals(1L, index.recordOf("content://tree/doc1")?.id)
    }

    @Test
    fun `файл переименованный или удалённый снаружи даёт записи статус пропавшего`() {
        val index = MedFileIndex(listOf(withFile, replaced), folderFileUris = setOf("content://tree/doc2"))
        val missing = index.missingRecords
        assertEquals(listOf(withFile), missing)
        // Запись при этом доступна: находят по uri, данные целы.
        assertEquals(1L, index.recordOf("content://tree/doc1")?.id)
    }

    @Test
    fun `ручная запись без оригинала не числится пропавшей`() {
        val index = MedFileIndex(listOf(manual), folderFileUris = emptySet())
        assertTrue(index.missingRecords.isEmpty())
        assertNull(index.recordOf("content://tree/doc1"))
    }
}
