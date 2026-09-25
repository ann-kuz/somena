package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
