package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Записи Медкарты (спека 0010, тикет 01): флаг «вне референса», валидация, сводки. */
class MedCardTest {

    private val today = LocalDate.of(2026, 9, 28)
    private val past = LocalDate.of(2026, 5, 12)

    @Test
    fun `флаг вне референса считается по границам`() {
        val row = AnalyteRow("Гемоглобин", 134.0, "г/л", 120.0, 150.0)
        assertFalse(row.outOfRange)
        assertTrue(row.copy(value = 110.0).outOfRange)
        assertTrue(row.copy(value = 160.0).outOfRange)
        // Без границ флага нет: референс неизвестен - не наше дело судить.
        assertFalse(AnalyteRow("Гемоглобин", 134.0).outOfRange)
        assertFalse(AnalyteRow("Гемоглобин", 134.0, refLow = 120.0, refHigh = null).copy(value = 160.0).outOfRange)
    }

    @Test
    fun `сводка анализа считает показатели и вне референса`() {
        val record = MedRecord(
            kind = MedKind.ANALYSIS,
            date = past,
            items = listOf(
                AnalyteRow("Гемоглобин", 134.0, "г/л", 120.0, 150.0),
                AnalyteRow("Ферритин", 8.0, "нг/мл", 13.0, 150.0),
                AnalyteRow("Витамин D", 21.0, "нг/мл", 30.0, 100.0),
                AnalyteRow("ТТГ", 2.1, "мЕд/л", 0.4, 4.0),
            ),
        )
        assertEquals("4 показателя, 2 вне референса", record.describe())
        assertEquals(2, record.outOfRangeCount)
        assertEquals("Анализ от 12.05.2026: 4 показателя, 2 вне референса", record.chatSummary())
    }

    @Test
    fun `название анализа возглавляет сводки списка и чата`() {
        val record = MedRecord(
            kind = MedKind.ANALYSIS,
            date = past,
            title = "Биохимический анализ крови",
            items = listOf(AnalyteRow("Гемоглобин", 134.0, "г/л", 120.0, 150.0)),
        )
        assertEquals("Биохимический анализ крови: 1 показатель", record.describe())
        assertEquals(
            "Биохимический анализ крови от 12.05.2026: 1 показатель",
            record.chatSummary(),
        )
    }

    @Test
    fun `сводки обследования и протокола строятся из их полей`() {
        val exam = MedRecord(
            kind = MedKind.EXAM,
            date = past,
            examType = "УЗИ щитовидной железы",
            conclusion = "Без особенностей.",
        )
        assertEquals("УЗИ щитовидной железы: Без особенностей.", exam.describe())

        val protocol = MedRecord(
            kind = MedKind.PROTOCOL,
            date = past,
            specialty = "Эндокринолог",
            diagnoses = listOf("Гипотиреоз", "Анемия"),
        )
        assertEquals("Эндокринолог: Гипотиреоз, Анемия", protocol.describe())
    }

    @Test
    fun `склонение показателей по числам`() {
        assertEquals("1 показатель", "1 ${pluralIndicator(1)}")
        assertEquals("21 показатель", "21 ${pluralIndicator(21)}")
        assertEquals("2 показателя", "2 ${pluralIndicator(2)}")
        assertEquals("5 показателей", "5 ${pluralIndicator(5)}")
        assertEquals("11 показателей", "11 ${pluralIndicator(11)}")
    }

    @Test
    fun `валидатор требует дату в прошлом и содержимое по виду`() {
        assertTrue(
            MedValidator.validate(
                MedRecord(kind = MedKind.ANALYSIS, date = past, items = listOf(AnalyteRow("ТТГ", 2.0))), today
            ).isEmpty()
        )
        assertTrue(MedValidator.validate(MedRecord(kind = MedKind.EXAM, date = past, conclusion = "норма"), today).isEmpty())
        assertTrue(
            MedValidator.validate(MedRecord(kind = MedKind.PROTOCOL, date = past, diagnoses = listOf("А")), today).isEmpty()
        )

        assertTrue(MedValidator.validate(MedRecord(kind = MedKind.ANALYSIS, date = today.plusDays(1)), today).any { it.contains("будущего") })
        assertTrue(MedValidator.validate(MedRecord(kind = MedKind.ANALYSIS, date = past), today).any { it.contains("показателей") })
        assertTrue(MedValidator.validate(MedRecord(kind = MedKind.EXAM, date = past), today).any { it.contains("Обследование пустое") })
        assertTrue(MedValidator.validate(MedRecord(kind = MedKind.PROTOCOL, date = past), today).any { it.contains("Протокол пустой") })
        // Сегодняшняя дата - не будущее.
        assertTrue(MedValidator.validate(MedRecord(kind = MedKind.EXAM, date = today, examType = "ЭКГ"), today).isEmpty())
    }

    @Test
    fun `видимые строки без типографских тире`() {
        val record = MedRecord(
            kind = MedKind.ANALYSIS,
            date = past,
            items = listOf(AnalyteRow("Ферритин", 8.0, "нг/мл", 13.0, 150.0)),
        )
        assertFalse(record.describe().contains("—"))
        assertFalse(record.chatSummary().contains("—"))
    }
}
