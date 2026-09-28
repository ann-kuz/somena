package ru.somena.core

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Весь путь «записала через Чат - вижу на графике» на её сессии 28.09: сожжённые
 * 400 за 26.09 и 50 за 27.09, в обоих днях уже были шаги браслета, но не было расхода.
 */
class ChatEntryToChartTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 9, 28)
    private val d26 = today.minusDays(2)
    private val d27 = today.minusDays(1)

    private val reply = "Готово, заношу.\n" +
        "```данные\n" +
        "{\"days\":[{\"date\":\"2026-09-26\",\"burned_kcal\":400},{\"date\":\"2026-09-27\",\"burned_kcal\":50}]}\n" +
        "```"

    private val existing = mapOf(
        d26 to DaySlice(date = d26, steps = 8000),
        d27 to DaySlice(date = d27, steps = 7000),
    )

    private fun writtenSlices(): Map<LocalDate, DaySlice> {
        val parsed = parseAiDataEntries(reply, existing, today = today)
        val preview = parsed.preview
        assertNotNull("блок данные должен разобраться в Предпросмотр", preview)
        assertEquals(2, preview!!.entries.size)
        // Путь кнопки «Записать» в ChatUi: toSlice поверх существующего дня и upsert.
        return preview.entries.fold(existing) { acc, e -> acc + (e.date to e.toSlice(acc[e.date])) }
    }

    @Test
    fun `записанные через чат сожжённые доезжают до серии графика`() {
        val series = metricSeries(AiMetric.BURNED, lastDays(today, 7), DayData(writtenSlices()))
        assertEquals(7, series.size)
        assertEquals(400.0, series[series.size - 3]!!, 0.01)
        assertEquals(50.0, series[series.size - 2]!!, 0.01)
    }

    @Test
    fun `после записи контекст чата показывает сожжённые за оба дня`() {
        val context = buildChatContext(today = today, daysBack = 7, data = DayData(writtenSlices()))
        assertTrue(context.contains("26.09: шаги 8000, сожжено 400 ккал"))
        assertTrue(context.contains("27.09: шаги 7000, сожжено 50 ккал"))
    }

    @Test
    fun `пересчёт HC без расхода браслета не трогает внесённое из чата`() {
        val stored = writtenSlices()[d26]!!
        val start = d26.atStartOfDay(zone).toInstant()
        val fresh = DailyAggregator.buildSlice(
            d26, zone,
            steps = listOf(StepEntry(start, start.plusSeconds(3600), 8000, "com.xiaomi.wear")),
        )
        val merged = stored.mergeFresh(fresh)
        assertEquals(400.0, merged.burnedKcal!!, 0.01)
    }

    @Test
    fun `если браслет принёс свой расход - Источники сильнее внесённого из чата`() {
        val stored = writtenSlices()[d26]!!
        val start = d26.atStartOfDay(zone).toInstant()
        val fresh = DailyAggregator.buildSlice(
            d26, zone,
            burn = listOf(BurnEntry(start, start.plusSeconds(3600), 2050.0)),
        )
        val merged = stored.mergeFresh(fresh)
        assertEquals(2050.0, merged.burnedKcal!!, 0.01)
    }
}
