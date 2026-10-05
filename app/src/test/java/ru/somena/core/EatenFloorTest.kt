package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты Порога съеденного (спека 0019): день, где «Съедено» меньше порога
 * из Настроек, считается днём без еды - на графиках, в Дефиците и в Чате
 * по данным его нет. Чистая логика без Android-зависимостей.
 */
class EatenFloorTest {

    private val day: LocalDate = LocalDate.of(2026, 10, 1)

    @Test
    fun `день с едой ниже порога считается днём без еды`() {
        val slices = listOf(
            DaySlice(day, steps = 8_000, burnedKcal = 1_800.0, eatenKcal = 250.0,
                proteinG = 12.0, fatG = 8.0, carbsG = 30.0, weightKg = 70.0),
        ).withEatenFloor(500.0)
        assertNull(slices[0].eatenKcal)
        // БЖУ - та же запись еды, что и калории: скрываются вместе с ними.
        assertNull(slices[0].proteinG)
        assertNull(slices[0].fatG)
        assertNull(slices[0].carbsG)
        // Остальные показатели дня не трогаются.
        assertEquals(8_000L, slices[0].steps)
        assertEquals(1_800.0, slices[0].burnedKcal!!, 0.0)
        assertEquals(70.0, slices[0].weightKg!!, 0.0)
    }

    @Test
    fun `день на пороге и выше остаётся как записан`() {
        // «меньше x» - строго: ровно на пороге день настоящий, не неполный.
        val slices = listOf(
            DaySlice(day, eatenKcal = 500.0, proteinG = 30.0),
            DaySlice(day.plusDays(1), eatenKcal = 501.0),
        ).withEatenFloor(500.0)
        assertEquals(500.0, slices[0].eatenKcal!!, 0.0)
        assertEquals(30.0, slices[0].proteinG!!, 0.0)
        assertEquals(501.0, slices[1].eatenKcal!!, 0.0)
    }

    @Test
    fun `пустой и отрицательный порог ничего не скрывают`() {
        val slices = listOf(
            DaySlice(day, eatenKcal = 30.0, fatG = 2.0),
        )
        assertEquals(slices, slices.withEatenFloor(0.0))
        assertEquals(slices, slices.withEatenFloor(-100.0))
    }

    @Test
    fun `день без еды порог не замечает`() {
        val slices = listOf(
            DaySlice(day, steps = 4_000, sleepMinutes = 420, pulseAvg = 62),
        ).withEatenFloor(500.0)
        assertEquals(4_000L, slices[0].steps)
        assertEquals(420L, slices[0].sleepMinutes)
        assertEquals(62L, slices[0].pulseAvg)
    }

    @Test
    fun `бжу без калорий тоже ниже порога`() {
        // Разбор таблицы может принести день с БЖУ и без калорий: по калориям
        // это ноль - день неполный, скрывается целиком.
        val slices = listOf(
            DaySlice(day, proteinG = 20.0, carbsG = 40.0),
        ).withEatenFloor(500.0)
        assertNull(slices[0].proteinG)
        assertNull(slices[0].carbsG)
    }

    @Test
    fun `скрытый день не попадает на графики Съедено и Дефицита`() {
        val slices = listOf(
            DaySlice(day.minusDays(1), eatenKcal = 120.0, burnedKcal = 2_000.0),
            DaySlice(day, eatenKcal = 1_800.0, burnedKcal = 2_000.0, bmrKcal = 1_400.0),
        ).withEatenFloor(500.0)
        val data = DayData(slices.associateBy { it.date })
        val dates = listOf(day.minusDays(1), day)
        assertNull(metricSeries(AiMetric.EATEN, dates, data)[0])
        assertEquals(1_800.0, metricSeries(AiMetric.EATEN, dates, data)[1]!!, 0.0)
        // Дефицит неполного дня пуст: день без еды дефицита не даёт.
        assertNull(metricSeries(AiMetric.DEFICIT, dates, data)[0])
    }
}
