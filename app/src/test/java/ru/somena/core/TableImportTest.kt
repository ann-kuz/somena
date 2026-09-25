package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableImportTest {

    private val jan5 = LocalDate.of(2025, 1, 5)
    private val jan6 = LocalDate.of(2025, 1, 6)

    @Test
    fun `таблица разбирается в дни по возрастанию даты`() {
        val preview = parseImportReply(
            """{"days":[
                {"date":"2025-01-06","weight":62.1},
                {"date":"05.01.2025","weight":62.4,"eaten_kcal":1850}]}""",
            emptyMap(),
        )
        assertNotNull(preview)
        assertEquals(2, preview!!.entries.size)
        assertEquals(jan5, preview.entries[0].date)
        assertEquals(62.4, preview.entries[0].values.weightKg!!, 1e-9)
        assertEquals(1850.0, preview.entries[0].values.eatenKcal!!, 1e-9)
        assertEquals(jan6, preview.entries[1].date)
    }

    @Test
    fun `русские даты с коротким годом понимаются`() {
        val preview = parseImportReply("""{"days":[{"date":"05.01.25","weight":62.4}]}""", emptyMap())
        assertEquals(jan5, preview!!.entries.single().date)
    }

    @Test
    fun `строки модели с лишними метриками не заносятся`() {
        // Схема разбора не содержит шагов: таблица не может записать измерения устройств (ADR-0001).
        val preview = parseImportReply("""{"days":[{"date":"05.01.2025","steps":12000,"weight":62.4}]}""", emptyMap())
        assertEquals(62.4, preview!!.entries.single().values.weightKg!!, 1e-9)
        assertTrue(preview.entries.single().toSlice(null).steps == null)
    }

    @Test
    fun `значение вне диапазона отбрасывает строку целиком`() {
        val preview = parseImportReply(
            """{"days":[
                {"date":"05.01.2025","weight":500.0},
                {"date":"06.01.2025","weight":62.1}]}""",
            emptyMap(),
        )
        assertTrue(preview!!.entries.map { it.date } == listOf(jan6))
        assertEquals(1, preview.rejected.size)
        assertTrue(preview.rejected.single().reason.contains("вне диапазона"))
    }

    @Test
    fun `непонятная дата и день без показателей уходят в отброшенные`() {
        val preview = parseImportReply(
            """{"days":[
                {"date":"какая-то","weight":62.4},
                {"date":"06.01.2025"},
                {"date":"07.01.2025","eaten_kcal":1800}]}""",
            emptyMap(),
        )
        assertEquals(2, preview!!.rejected.size)
        assertEquals(listOf(LocalDate.of(2025, 1, 7)), preview.entries.map { it.date })
    }

    @Test
    fun `список unparsed от модели попадает в отброшенные`() {
        val preview = parseImportReply(
            """{"days":[],"unparsed":[{"row":"Итого за месяц","problem":"итоговая строка"}]}""",
            emptyMap(),
        )
        assertEquals("Итого за месяц", preview!!.rejected.single().raw)
        assertEquals("итоговая строка", preview.rejected.single().reason)
    }

    @Test
    fun `повторный импорт без изменений не предлагает замен`() {
        val existing = mapOf(jan5 to DaySlice(date = jan5, weightKg = 62.4, steps = 9000))
        val preview = parseImportReply("""{"days":[{"date":"05.01.2025","weight":62.4}]}""", existing)
        assertTrue(preview!!.entries.isEmpty())
    }

    @Test
    fun `замена показывает старое значение и пишет импорт поверх остального`() {
        val existing = mapOf(jan5 to DaySlice(date = jan5, weightKg = 62.9, steps = 9000))
        val preview = parseImportReply("""{"days":[{"date":"05.01.2025","weight":62.4}]}""", existing)
        val entry = preview!!.entries.single()
        assertEquals(62.9, entry.old.weightKg!!, 1e-9)
        val merged = entry.toSlice(existing[jan5])
        assertEquals(62.4, merged.weightKg!!, 1e-9)
        // Чужие поля дня импорт не трогает: шаги остаются на месте.
        assertEquals(9000L, merged.steps!!)
    }

    @Test
    fun `описание значений читается по-человечески`() {
        val line = ImportValues(weightKg = 62.0, eatenKcal = 1850.5).describe()
        assertEquals("вес 62 кг, съедено 1850.5 ккал", line)
    }

    @Test
    fun `markdown-обрамление ответа срезается, а не-JSON даёт null`() {
        val fenced = "Вот что я нашла:\n```json\n{\"days\":[{\"date\":\"05.01.2025\",\"weight\":62.4}]}\n```"
        assertEquals(62.4, parseImportReply(fenced, emptyMap())!!.entries.single().values.weightKg!!, 1e-9)
        assertNull(parseImportReply("К сожалению, я не вижу таблицы.", emptyMap()))
    }

    @Test
    fun `текст файла читается в utf-8 с BOM и в windows-1251`() {
        val utf8 = "\uFEFFДата;Вес\n05.01.2025;62,4".toByteArray(Charsets.UTF_8)
        assertEquals("Дата;Вес\n05.01.2025;62,4", decodeTableText(utf8))

        val cp1251 = "Дата;Вес\n05.01.2025;62.4".toByteArray(charset("windows-1251"))
        assertEquals("Дата;Вес\n05.01.2025;62.4", decodeTableText(cp1251))
    }
}
