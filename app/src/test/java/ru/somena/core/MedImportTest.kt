package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор документа в запись Медкарты (спека 0010, тикет 03). */
class MedImportTest {

    private val today = LocalDate.of(2026, 9, 28)

    @Test
    fun `анализ разбирается построчно с референсами и флагом вне референса`() {
        val raw = """
            {"kind":"analysis","date":"12.05.2026","items":[
            {"name":"Гемоглобин","value":134,"unit":"г/л","ref_low":120,"ref_high":150},
            {"name":"Ферритин","value":8,"unit":"нг/мл","ref_low":13,"ref_high":150}]}
        """.trimIndent()
        val result = parseMedReply(raw, today)!!
        assertEquals(MedKind.ANALYSIS, result.draft.kind)
        assertEquals(LocalDate.of(2026, 5, 12), result.draft.date)
        assertEquals(2, result.draft.items.size)
        assertFalse(result.draft.items[0].outOfRange)
        assertTrue(result.draft.items[1].outOfRange)
        assertEquals(1, result.draft.outOfRangeCount)
        assertNull(result.dateProblem)
    }

    @Test
    fun `обследование и протокол разбираются в свои поля`() {
        val exam = parseMedReply(
            """{"kind":"exam","date":"2026-05-12","exam_type":"УЗИ щитовидной железы","conclusion":"Без особенностей."}""",
            today,
        )!!
        assertEquals(MedKind.EXAM, exam.draft.kind)
        assertEquals("УЗИ щитовидной железы", exam.draft.examType)

        val protocol = parseMedReply(
            """{"kind":"protocol","date":"2026-05-12","specialty":"Эндокринолог","diagnoses":["Гипотиреоз"],"recommendations":"Контроль ТТГ через 3 месяца."}""",
            today,
        )!!
        assertEquals(MedKind.PROTOCOL, protocol.draft.kind)
        assertEquals(listOf("Гипотиреоз"), protocol.draft.diagnoses)
    }

    @Test
    fun `дата не извлеклась - заглушка сегодня и напоминание поставить вручную`() {
        val result = parseMedReply(
            """{"kind":"analysis","items":[{"name":"ТТГ","value":2.1}]}""", today,
        )!!
        assertEquals(today, result.draft.date)
        assertTrue(result.dateProblem!!.contains("вручную"))
    }

    @Test
    fun `дата из будущего помечается проблемой`() {
        val result = parseMedReply(
            """{"kind":"exam","date":"12.05.2027","exam_type":"ЭКГ"}""", today,
        )!!
        assertTrue(result.dateProblem!!.contains("будущ"))
    }

    @Test
    fun `существующая запись того же вида и даты предупреждает о дубле`() {
        val existing = listOf(
            MedRecord(id = 7, kind = MedKind.ANALYSIS, date = LocalDate.of(2026, 5, 12), items = listOf(AnalyteRow("ТТГ", 2.0))),
        )
        val result = parseMedReply(
            """{"kind":"analysis","date":"12.05.2026","items":[{"name":"ТТГ","value":2.2}]}""",
            today, existing,
        )!!
        assertEquals(7L, result.duplicateOf?.id)
        // Другой вид или другая дата - не дубль.
        val other = parseMedReply(
            """{"kind":"exam","date":"12.05.2026","exam_type":"ЭКГ"}""", today, existing,
        )!!
        assertNull(other.duplicateOf)
    }

    @Test
    fun `мусорный ответ и пустая запись дают null`() {
        assertNull(parseMedReply("Простите, я не вижу документа.", today))
        assertNull(parseMedReply("""{"kind":"analysis","items":[]}""", today))
        assertNull(parseMedReply("""{"kind":"unknown"}""", today))
    }

    @Test
    fun `строки без значения уходят в отброшенные а не роняют разбор`() {
        val result = parseMedReply(
            """{"kind":"analysis","date":"2026-05-12","items":[
            {"name":"Гемоглобин","value":134},
            {"name":"Лейкоциты","value":null},
            {"value":5.0}],
            "unparsed":[{"row":"Реклама клиники","problem":"не по делу"}]}""",
            today,
        )!!
        assertEquals(1, result.draft.items.size)
        assertEquals(3, result.rejected.size)
        assertTrue(result.rejected.any { it.raw == "Лейкоциты" })
        assertTrue(result.rejected.any { it.raw == "Реклама клиники" })
    }

    @Test
    fun `название документа из ответа ИИ ложится в запись`() {
        val result = parseMedReply(
            """{"kind":"analysis","date":"12.05.2026","title":"Биохимический анализ крови","items":[{"name":"ТТГ","value":2.1}]}""",
            today,
        )!!
        assertEquals("Биохимический анализ крови", result.draft.title)
    }

    @Test
    fun `анализ без названия получает понятное имя обследование и протокол без`() {
        // Гарантия кода, а не только промпта: в базе не должно быть безымянных анализов.
        val analysis = parseMedReply(
            """{"kind":"analysis","date":"12.05.2026","items":[{"name":"ТТГ","value":2.1}]}""",
            today,
        )!!
        assertEquals("Анализ крови", analysis.draft.title)

        val exam = parseMedReply(
            """{"kind":"exam","date":"12.05.2026","exam_type":"ЭКГ"}""", today,
        )!!
        assertNull(exam.draft.title)
        val protocol = parseMedReply(
            """{"kind":"protocol","date":"12.05.2026","specialty":"Эндокринолог","diagnoses":["А"]}""",
            today,
        )!!
        assertNull(protocol.draft.title)
    }

    @Test
    fun `вид не назван но содержимое однозначное - доверяем содержимому`() {
        val result = parseMedReply(
            """{"items":[{"name":"ТТГ","value":2.1}]}""", today,
        )!!
        assertEquals(MedKind.ANALYSIS, result.draft.kind)
    }

    @Test
    fun `плотность текстового слоя pdf отделяет скан от настоящего текста`() {
        assertTrue(pdfTextIsDense("Гемоглобин 134 г/л 120-150\nФерритин 8 нг/мл 13-150\n" + "х".repeat(400)))
        assertFalse(pdfTextIsDense("   \n\t"))
        assertFalse(pdfTextIsDense("Скан без текста 12"))
    }

    @Test
    fun `проблема даты не из будущего не врёт при корректной дате`() {
        val result = parseMedReply(
            """{"kind":"analysis","date":"12.05.2026","items":[{"name":"ТТГ","value":2.1}]}""", today,
        )
        assertNotNull(result)
        assertNull(result!!.dateProblem)
    }
}
