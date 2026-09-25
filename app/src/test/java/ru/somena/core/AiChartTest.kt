package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WellbeingTest {

    @Test
    fun `шкалы ограничены диапазоном 0-10`() {
        assertEquals(0, Wellbeing.clamp(-3))
        assertEquals(0, Wellbeing.clamp(0))
        assertEquals(10, Wellbeing.clamp(10))
        assertEquals(10, Wellbeing.clamp(15))
    }
}

class AiChartTest {

    @Test
    fun `блок chart вырезается из текста и разбирается`() {
        val reply = "Смотри, вот вес:\n```chart\n{\"title\": \"Вес\", \"days\": 30, \"metrics\": [\"weight\"]}\n```\nВывод: Trend ровный."
        val (text, specs) = parseAiCharts(reply)
        assertEquals("Смотри, вот вес:\n\nВывод: Trend ровный.", text)
        assertEquals(1, specs.size)
        assertEquals("Вес", specs[0].title)
        assertEquals(30, specs[0].windowDays)
        assertEquals(listOf("weight"), specs[0].metrics)
    }

    @Test
    fun `несколько блоков поддерживаются`() {
        val reply = "```chart\n{\"metrics\": [\"weight\", \"eaten\"]}\n```\nи ещё:\n```chart\n{\"title\": \"Сон\", \"days\": 7, \"metrics\": [\"sleep\"]}\n```"
        val (text, specs) = parseAiCharts(reply)
        assertEquals("и ещё:", text)
        assertEquals(2, specs.size)
        assertEquals(listOf("weight", "eaten"), specs[0].metrics)
        assertEquals(7, specs[1].windowDays)
    }

    @Test
    fun `окно в днях обрезается до разумных пределов`() {
        val (_, specs) = parseAiCharts(
            "```chart\n{\"metrics\": [\"steps\"], \"days\": 3}\n```" +
                "```chart\n{\"metrics\": [\"steps\"], \"days\": 500}\n```"
        )
        assertEquals(AiChartSpec.MIN_DAYS, specs[0].windowDays)
        assertEquals(AiChartSpec.MAX_DAYS, specs[1].windowDays)
    }

    @Test
    fun `неизвестные метрики отбрасываются, пустой график игнорируется`() {
        val reply = "```chart\n{\"metrics\": [\"weight\", \"magic\", \"weight\"]}\n```" +
            "```chart\n{\"metrics\": [\"magic\"]}\n```"
        val (_, specs) = parseAiCharts(reply)
        assertEquals(1, specs.size)
        assertEquals(listOf("weight"), specs[0].metrics)
    }

    @Test
    fun `сломанный JSON не ломает текст`() {
        val reply = "Ответ:\n```chart\n{не json}\n```Конец."
        val (text, specs) = parseAiCharts(reply)
        assertEquals("Ответ:\nКонец.", text)
        assertTrue(specs.isEmpty())
    }
}

class MetricSeriesTest {

    private val d1 = LocalDate.of(2026, 9, 23)
    private val d2 = LocalDate.of(2026, 9, 24)
    private val dates = listOf(d1, d2)

    private val slice = DaySlice(
        date = d1,
        steps = 8432,
        sleepMinutes = 440,
        burnedKcal = 2100.0,
        eatenKcal = 1800.0,
    )
    private val slices = mapOf(d1 to slice)
    private val wellbeing = mapOf(
        d1 to Wellbeing(d1, energy = 7, mood = 8, sleepQuality = 6, note = "норм"),
    )

    @Test
    fun `метрики достаются из срезов и Самочувствия`() {
        assertEquals(listOf(8432.0, null), metricSeries(AiMetric.STEPS, dates, DayData(slices, wellbeing)))
        assertEquals(listOf(7.0, null), metricSeries(AiMetric.ENERGY, dates, DayData(slices, wellbeing)))
        assertEquals(listOf(8.0, null), metricSeries(AiMetric.MOOD, dates, DayData(slices, wellbeing)))
    }

    @Test
    fun `сон переводится в часы, дефицит считается из пары калорий`() {
        assertEquals(listOf(440.0 / 60, null), metricSeries(AiMetric.SLEEP, dates, DayData(slices, wellbeing)))
        assertEquals(listOf(300.0, null), metricSeries(AiMetric.DEFICIT, dates, DayData(slices, wellbeing)))
    }

    @Test
    fun `дефицит без пары калорий не считается - пропуск не ноль`() {
        val partial = mapOf(d1 to slice.copy(eatenKcal = null))
        assertEquals(listOf(null, null), metricSeries(AiMetric.DEFICIT, dates, DayData(partial)))
    }

    @Test
    fun `день без данных дает null а не ноль`() {
        val empty = metricSeries(AiMetric.WEIGHT, dates)
        assertEquals(listOf(null, null), empty)
        assertNull(empty[0])
    }
}
