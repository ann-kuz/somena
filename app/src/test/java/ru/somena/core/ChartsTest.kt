package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartsTest {

    @Test
    fun `тренд требует минимум значений в окне`() {
        assertNull(weightTrend(listOf(70.0))[0]) // одно значение — мало
        assertNull(weightTrend(listOf(70.0, 71.0))[1]) // два — мало
        assertEquals(70.3333, weightTrend(listOf(70.0, 71.0, 70.0))[2]!!, 0.001)
    }

    @Test
    fun `пробелы в весе не ломают тренд`() {
        val weights: List<Double?> = listOf(70.0, null, null, 71.0, 72.0, null, 73.0)
        val trend = weightTrend(weights)
        assertNull(trend[1])
        assertNull(trend[2])
        // к 4-му дню в окне три значения: 70, 71, 72
        assertEquals(71.0, trend[4]!!, 0.001)
    }

    @Test
    fun `окно тренда ограничено семью днями`() {
        val weights = List(20) { 70.0 + it } // растущий ряд
        val trend = weightTrend(weights)
        // в точке 19 окно: дни 13..19 (7 значений), среднее = 70+13..70+19 среднее = 86.0
        assertEquals(86.0, trend[19]!!, 0.001)
    }

    @Test
    fun `метрика пульса идет средним за день и не выдумывает нули`() {
        val d1 = LocalDate.of(2026, 9, 24)
        val d2 = d1.plusDays(1)
        val data = DayData(
            slicesByDate = mapOf(
                d1 to DaySlice(d1, pulseAvg = 70, pulseMin = 58, pulseMax = 82),
                d2 to DaySlice(d2, steps = 4000),
            )
        )
        val series = metricSeries(AiMetric.PULSE, listOf(d1, d2), data)
        assertEquals(70.0, series[0]!!, 0.001)
        assertNull(series[1])
    }

    @Test
    fun `диапазоны периодов корректны`() {
        val end = LocalDate.of(2026, 9, 25)
        val week = chartRange(end, ChartPeriod.WEEK)
        assertEquals(7, week.size)
        assertEquals(end.minusDays(6), week.first())
        assertEquals(end, week.last())
        val month = chartRange(end, ChartPeriod.MONTH)
        assertEquals(30, month.size)
        assertEquals(emptyList<LocalDate>(), chartRange(end, ChartPeriod.ALL))
    }

    @Test
    fun `границы окна - не дальше сегодня и не раньше первых данных`() {
        val today = LocalDate.of(2026, 9, 25)
        val first = LocalDate.of(2026, 9, 1)
        assertEquals(today, clampWindowEnd(today.plusDays(3), today, first))
        assertEquals(first, clampWindowEnd(first.minusDays(10), today, first))
        assertEquals(LocalDate.of(2026, 9, 10), clampWindowEnd(LocalDate.of(2026, 9, 10), today, first))
    }

    @Test
    fun `перетаскивание мелкими шагами сдвигает окно данных`() {
        // Жест по неделе при ~41px на день (график ~290dp): 60 событий по 5px = 300px, это ~7 дней.
        val pan = PanAccumulator()
        var shifted = 0
        repeat(60) { shifted += pan.add(-5f, 41f) }
        assertEquals("палец тянет вправо - окно уходит в прошлое", -7, shifted)
    }

    @Test
    fun `остаток пикселей копится между событиями и откатывается назад`() {
        val pan = PanAccumulator()
        assertEquals("треть дня - день не набрался", 0, pan.add(-10f, 27f))
        assertEquals(0, pan.add(-10f, 27f))
        assertEquals("30px из 27 - ровно день", -1, pan.add(-10f, 27f))
        assertEquals("сразу полтора дня - один день, остаток копится", -1, pan.add(-40f, 27f))
        assertEquals("лёгкий откат недодвигает окно", 0, pan.add(5f, 27f))
        assertEquals("откат дошёл до целого дня", 1, pan.add(40f, 27f))
    }

    @Test
    fun `нулевой масштаб окна не двигает ничего`() {
        val pan = PanAccumulator()
        assertEquals(0, pan.add(-100f, 0f))
    }

    @Test
    fun `окно месяца дотягивается до занесённого задним числом веса`() {
        // Сценарий Разбора таблицы: HC дал только недавние дни, вес приехал из таблицы
        // за старые месяцы редкими взвешиваниями. Как на экране Графиков: окно месяца
        // листается от сегодня назад и обязано показать подтянутые точки.
        val today = LocalDate.of(2026, 9, 25)
        val hcDays = lastDays(today, 56) // HC: последние 8 недель без веса
        val backfilled = listOf(
            DaySlice(LocalDate.of(2026, 1, 5), weightKg = 61.5),
            DaySlice(LocalDate.of(2026, 2, 10), weightKg = 61.8),
            DaySlice(LocalDate.of(2026, 3, 15), weightKg = 61.2),
        )
        val slices = backfilled + hcDays.map { DaySlice(it, steps = 8000) }
        val byDate = slices.associateBy { it.date }
        val firstDataDate = listOfNotNull(slices.firstOrNull()?.date).minOrNull() ?: today

        // Листание окна месяца назад рывками по месяцу, пока клэмп не остановит окно.
        var windowEnd = today
        var reachedOldest = false
        val coveredWeights = mutableListOf<Pair<LocalDate, Double?>>()
        repeat(24) {
            windowEnd = clampWindowEnd(windowEnd.minusDays(31), today, firstDataDate)
            val days = lastDays(windowEnd, 31)
            if (windowEnd == firstDataDate) reachedOldest = true
            // точки веса окна месяца, ровно как values { it.weightKg } на экране
            days.mapNotNull(byDate::get).filter { it.weightKg != null }.forEach {
                coveredWeights += it.date to it.weightKg
            }
            if (windowEnd == firstDataDate) return@repeat
        }
        assertTrue("клэмп пускает окно до самого старого дня данных", reachedOldest)
        assertEquals(
            "все три задним числом занесённые точки попадают в окно месяца",
            listOf(
                LocalDate.of(2026, 3, 15) to 61.2,
                LocalDate.of(2026, 2, 10) to 61.8,
                LocalDate.of(2026, 1, 5) to 61.5,
            ),
            coveredWeights.distinct(),
        )
    }
}
