package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun `шаги расход и сон разбираются в значения дня`() {
        // ADR-0007: таблица восполняет измерения задним числом для дней без Источника.
        val preview = parseImportReply(
            """{"days":[{"date":"05.01.2025","steps":12000,"burned_kcal":2100,"sleep_h":7.5,"weight":62.4}]}""",
            emptyMap(),
        )
        val values = preview!!.entries.single().values
        assertEquals(12000L, values.steps)
        assertEquals(2100.0, values.burnedKcal!!, 1e-9)
        assertEquals("сон в часах хранится минутами", 450L, values.sleepMinutes)
        val merged = preview.entries.single().toSlice(DaySlice(date = jan5, weightKg = 60.0, eatenKcal = 1800.0))
        assertEquals(12000L, merged.steps)
        assertEquals(2100.0, merged.burnedKcal!!, 1e-9)
        assertEquals(450L, merged.sleepMinutes)
        assertEquals("табличное значение сильнее существующего", 62.4, merged.weightKg!!, 1e-9)
        assertEquals("чужие поля дня разбор не трогает", 1800.0, merged.eatenKcal!!, 1e-9)
    }

    @Test
    fun `дробные шаги отбраковывают строку, а не весь разбор`() {
        val preview = parseImportReply(
            """{"days":[
                {"date":"05.01.2025","steps":12000.5},
                {"date":"06.01.2025","steps":8000}]}""",
            emptyMap(),
        )
        assertEquals(listOf(jan6), preview!!.entries.map { it.date })
        assertTrue(preview.rejected.single().raw.contains("12000.5"))
        assertTrue(preview.rejected.single().reason.contains("steps не целое"))
    }

    @Test
    fun `сон вне суток отбраковывается по значению из файла`() {
        val preview = parseImportReply("""{"days":[{"date":"05.01.2025","sleep_h":30.0}]}""", emptyMap())
        assertTrue(preview!!.entries.isEmpty())
        assertEquals("30.0", preview.rejected.single().raw.substringAfter(": "))
        assertTrue(preview.rejected.single().reason.contains("sleep_h вне диапазона 0..24"))
    }

    @Test
    fun `мердж не создаёт новую отметку из неполной строки`() {
        val entry = ImportWellbeingEntry(jan5, ImportWellbeing(mood = 7), ImportWellbeing())
        try {
            entry.toWellbeing(null)
            fail("неполная строка без существующей отметки должна отказать")
        } catch (e: IllegalStateException) {
            // ожидаемый отказ: пустая шкала стала бы ложным нулём в графике
        }
    }

    @Test
    fun `шаги и сон вне диапазона отбрасывают строку`() {
        val preview = parseImportReply(
            """{"days":[
                {"date":"05.01.2025","steps":200000},
                {"date":"06.01.2025","sleep_h":30.0},
                {"date":"07.01.2025","steps":8000}]}""",
            emptyMap(),
        )
        assertEquals(listOf(LocalDate.of(2025, 1, 7)), preview!!.entries.map { it.date })
        assertEquals(2, preview.rejected.size)
    }

    @Test
    fun `дата из будущего уходит в отброшенные`() {
        val today = LocalDate.of(2026, 9, 25)
        val preview = parseImportReply(
            """{"days":[{"date":"2026-09-26","weight":62.4}],
                "wellbeing":[{"date":"2027-01-01","energy":7,"mood":6,"sleep_quality":8}]}""",
            emptyMap(),
            today = today,
        )
        assertTrue(preview!!.entries.isEmpty())
        assertTrue(preview.wellbeing.isEmpty())
        assertEquals(2, preview.rejected.size)
    }

    @Test
    fun `самочувствие разбирается в предпросмотр`() {
        val preview = parseImportReply(
            """{"wellbeing":[{"date":"05.01.2025","energy":7,"mood":6,"sleep_quality":8}]}""",
            emptyMap(),
        )
        val entry = preview!!.wellbeing.single()
        assertEquals(jan5, entry.date)
        assertEquals(7, entry.values.energy)
        assertEquals(6, entry.values.mood)
        assertEquals(8, entry.values.sleepQuality)
        assertEquals("энергия 7, настроение 6, качество сна 8", entry.values.describe())
        assertEquals(0, preview.wellbeingReplacedCount)
    }

    @Test
    fun `частичное самочувствие заменяет отмеченный день и бережёт заметку`() {
        val existingWb = mapOf(jan5 to Wellbeing(jan5, energy = 4, mood = 5, sleepQuality = 3, note = "была мигрень"))
        val preview = parseImportReply(
            """{"wellbeing":[{"date":"05.01.2025","mood":7}]}""",
            emptyMap(),
            existingWellbeing = existingWb,
        )
        val entry = preview!!.wellbeing.single()
        assertEquals(1, preview.wellbeingReplacedCount)
        assertEquals(5, entry.old.mood)
        val merged = entry.toWellbeing(existingWb[jan5])
        assertEquals("не указана - осталась старая", 4, merged.energy)
        assertEquals("указана - заменена", 7, merged.mood)
        assertEquals(3, merged.sleepQuality)
        assertEquals("была мигрень", merged.note)
    }

    @Test
    fun `новый день с неполным самочувствием отбрасывается`() {
        // Пустая шкала в новой отметке стала бы ложным нулём в графике.
        val preview = parseImportReply(
            """{"wellbeing":[{"date":"05.01.2025","mood":7}]}""",
            emptyMap(),
        )
        assertTrue(preview!!.wellbeing.isEmpty())
        assertEquals(1, preview.rejected.size)
    }

    @Test
    fun `дробная шкала и шкала вне 0-10 отбрасывают строку`() {
        val preview = parseImportReply(
            """{"wellbeing":[
                {"date":"05.01.2025","mood":6.5},
                {"date":"06.01.2025","energy":11},
                {"date":"07.01.2025","energy":3,"mood":3,"sleep_quality":3}]}""",
            emptyMap(),
        )
        assertEquals(listOf(LocalDate.of(2025, 1, 7)), preview!!.wellbeing.map { it.date })
        assertEquals(2, preview.rejected.size)
    }

    @Test
    fun `повторное самочувствие без изменений не предлагается`() {
        val existingWb = mapOf(jan5 to Wellbeing(jan5, energy = 7, mood = 6, sleepQuality = 8))
        val preview = parseImportReply(
            """{"wellbeing":[{"date":"05.01.2025","energy":7,"mood":6,"sleep_quality":8}]}""",
            emptyMap(),
            existingWellbeing = existingWb,
        )
        assertTrue(preview!!.wellbeing.isEmpty())
    }

    @Test
    fun `замены новых полей дня считаются заменами`() {
        val existing = mapOf(jan5 to DaySlice(date = jan5, steps = 9000))
        val preview = parseImportReply("""{"days":[{"date":"05.01.2025","steps":8000}]}""", existing)
        assertEquals(1, preview!!.replacedCount)
    }

    @Test
    fun `промпт разбора умещается в лимит бэкенда на system`() {
        assertTrue("лимит Бэкенда на system - 4000 символов", IMPORT_SYSTEM_PROMPT.length < 4000)
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
