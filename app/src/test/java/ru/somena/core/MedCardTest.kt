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
    fun `пометка записи звучит в сводке чата и не трогает записи без неё`() {
        val record = MedRecord(
            kind = MedKind.ANALYSIS,
            date = past,
            title = "Биохимический анализ крови",
            mark = "до операции",
            items = listOf(AnalyteRow("Гемоглобин", 134.0, "г/л", 120.0, 150.0)),
        )
        assertEquals(
            "Биохимический анализ крови от 12.05.2026 (до операции): 1 показатель",
            record.chatSummary(),
        )
        assertEquals(
            "Биохимический анализ крови от 12.05.2026: 1 показатель",
            record.copy(mark = null).chatSummary(),
        )
    }

    // ---- Список Медкарты: сортировка, поиск и фильтр по виду ----

    private fun analysisAt(date: LocalDate, title: String? = null, mark: String? = null) = MedRecord(
        kind = MedKind.ANALYSIS,
        date = date,
        title = title,
        mark = mark,
        items = listOf(AnalyteRow("Гемоглобин", 134.0, "г/л", 120.0, 150.0)),
    )

    @Test
    fun `список всегда по дате новые сверху`() {
        val january = analysisAt(LocalDate.of(2026, 1, 5))
        val may = analysisAt(LocalDate.of(2026, 5, 12))
        val september = analysisAt(LocalDate.of(2026, 9, 1))
        // Одна дата - выше та, что записана позже.
        val septemberAgain = analysisAt(LocalDate.of(2026, 9, 1)).copy(id = 2)
        assertEquals(
            listOf(septemberAgain, september, may, january),
            medRecordsView(listOf(january, september, may, septemberAgain), "", null),
        )
    }

    @Test
    fun `фильтр по виду оставляет только выбранный вид`() {
        val analysis = analysisAt(past)
        val exam = MedRecord(kind = MedKind.EXAM, date = past, examType = "УЗИ щитовидной железы")
        val protocol = MedRecord(kind = MedKind.PROTOCOL, date = past, specialty = "Эндокринолог")
        assertEquals(listOf(exam), medRecordsView(listOf(analysis, exam, protocol), "", MedKind.EXAM))
        assertEquals(listOf(analysis, exam, protocol), medRecordsView(listOf(analysis, exam, protocol), "", null))
    }

    @Test
    fun `поиск находит по названию пометке показателю заключению и диагнозу`() {
        val marked = analysisAt(past, title = "Биохимический анализ крови", mark = "до операции")
        val ferritin = MedRecord(
            kind = MedKind.ANALYSIS,
            date = past,
            items = listOf(AnalyteRow("Ферритин", 8.0, "нг/мл", 13.0, 150.0)),
        )
        val exam = MedRecord(kind = MedKind.EXAM, date = past, examType = "УЗИ щитовидной железы", conclusion = "Без особенностей")
        val protocol = MedRecord(kind = MedKind.PROTOCOL, date = past, specialty = "Эндокринолог", diagnoses = listOf("Гипотиреоз"))

        val all = listOf(marked, ferritin, exam, protocol)
        // Регистр не важен, пробелы по краям не мешают.
        assertEquals(listOf(marked), medRecordsView(all, "  биохимический ", null))
        assertEquals(listOf(marked), medRecordsView(all, "до операции", null))
        assertEquals(listOf(ferritin), medRecordsView(all, "ферритин", null))
        assertEquals(listOf(exam), medRecordsView(all, "узи", null))
        assertEquals(listOf(exam), medRecordsView(all, "особенност", null))
        assertEquals(listOf(protocol), medRecordsView(all, "гипотиреоз", null))
        // Часть слова тоже находит.
        assertEquals(listOf(marked, ferritin), medRecordsView(all, "анализ", null).sortedBy { it.items.first().name })
    }

    @Test
    fun `поиск находит по дате и работает вместе с фильтром вида`() {
        val may = analysisAt(LocalDate.of(2026, 5, 12), title = "Ферритин")
        val september = MedRecord(kind = MedKind.EXAM, date = LocalDate.of(2026, 9, 1), examType = "УЗИ")
        val all = listOf(may, september)
        assertEquals(listOf(may), medRecordsView(all, "12.05.2026", null))
        assertEquals(listOf(september), medRecordsView(all, "09.2026", null))
        // Фильтр и поиск складываются: поиск без вида, но фильтр отсекает чужие.
        assertEquals(emptyList<MedRecord>(), medRecordsView(all, "УЗИ", MedKind.ANALYSIS))
        assertEquals(listOf(september), medRecordsView(all, "УЗИ", MedKind.EXAM))
        // Пустой запрос и без фильтра - всё, но по дате новые сверху.
        assertEquals(listOf(september, may), medRecordsView(all, "  ", null))
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
