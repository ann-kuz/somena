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
}
