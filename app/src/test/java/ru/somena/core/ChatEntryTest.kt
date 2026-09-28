package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ввод данных через Чат по данным: блоки ```данные``` в ответе ИИ превращаются
 * в тот же Предпросмотр, что у Разбора таблицы, - запись только по явному «Записать».
 */
class ChatEntryTest {

    private val today = LocalDate.of(2026, 9, 28)
    private val dayBeforeYesterday = LocalDate.of(2026, 9, 26)

    private val existing = mapOf(
        dayBeforeYesterday to DaySlice(date = dayBeforeYesterday, burnedKcal = 1800.0),
    )

    @Test
    fun `ответ без блока возвращается как есть`() {
        val reply = "Сожжённые за 26.09 в данных не заполнены."
        val r = parseAiDataEntries(reply, existing, today = today)
        assertEquals(reply, r.text)
        assertNull(r.preview)
        assertEquals(0, r.brokenBlocks)
    }

    @Test
    fun `блок данные превращается в предпросмотр с заменой`() {
        val reply = "Записываю сожжённые калории за 26.09.\n" +
            "```данные\n" +
            "{\"days\":[{\"date\":\"26.09.2026\",\"burned_kcal\":2100}]}\n" +
            "```"
        val r = parseAiDataEntries(reply, existing, today = today)
        assertTrue(r.text.contains("Записываю"))
        assertEquals(false, r.text.contains("burned_kcal"))
        val preview = r.preview
        assertNotNull(preview)
        assertEquals(1, preview!!.entries.size)
        val entry = preview.entries.single()
        assertEquals(dayBeforeYesterday, entry.date)
        assertEquals(2100.0, entry.values.burnedKcal!!, 0.01)
        assertEquals(1800.0, entry.old.burnedKcal!!, 0.01)
        assertEquals(0, r.brokenBlocks)
    }

    @Test
    fun `сломанный блок вырезается из текста и считается разбитым`() {
        val reply = "Готово.\n```данные\nэто не json\n```"
        val r = parseAiDataEntries(reply, existing, today = today)
        assertEquals("Готово.", r.text)
        assertNull(r.preview)
        assertEquals(1, r.brokenBlocks)
    }

    @Test
    fun `дата из будущего уходит в отброшенные как в разборе таблицы`() {
        val reply = "```данные\n{\"days\":[{\"date\":\"2026-09-29\",\"burned_kcal\":2100}]}\n```"
        val r = parseAiDataEntries(reply, existing, today = today)
        assertNotNull(r.preview)
        assertTrue(r.preview!!.entries.isEmpty())
        assertTrue(r.preview!!.rejected.any { it.reason.contains("будущего") })
    }

    @Test
    fun `несколько блоков сливаются, поздний сильнее`() {
        val reply = "```данные\n{\"days\":[{\"date\":\"2026-09-26\",\"burned_kcal\":2000}]}\n```\n" +
            "Поправила:\n```данные\n{\"days\":[{\"date\":\"2026-09-26\",\"burned_kcal\":2100}]}\n```"
        val r = parseAiDataEntries(reply, existing, today = today)
        val preview = r.preview
        assertNotNull(preview)
        assertEquals(1, preview!!.entries.size)
        assertEquals(2100.0, preview.entries.single().values.burnedKcal!!, 0.01)
    }

    @Test
    fun `самочувствие из блока тоже попадает в предпросмотр`() {
        val reply = "```данные\n{\"wellbeing\":[{\"date\":\"2026-09-26\",\"energy\":7,\"mood\":6,\"sleep_quality\":8}]}\n```"
        val r = parseAiDataEntries(reply, emptyMap(), existingWellbeing = emptyMap(), today = today)
        assertNotNull(r.preview)
        assertEquals(1, r.preview!!.wellbeing.size)
    }

    @Test
    fun `пометка внесения данных распознаётся в начале сообщения`() {
        assertTrue(isDataEntryRequest("Внести данные: сожжено 400 за 26.09"))
        assertTrue(isDataEntryRequest("внести данные"))
        assertTrue(isDataEntryRequest("  Внести данные: вес 62.4"))
        assertEquals(false, isDataEntryRequest("Запиши сожжённые 400 за 26.09"))
        assertEquals(false, isDataEntryRequest("Как внести данные в график?"))
    }
}
