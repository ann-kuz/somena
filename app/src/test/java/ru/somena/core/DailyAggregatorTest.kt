package ru.somena.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Поведенческие тесты Дневного среза — единственный тестовый шов проекта (спека 0001).
 * Гоняются на сервере сборки без эмулятора.
 */
class DailyAggregatorTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val day: LocalDate = LocalDate.of(2026, 9, 24)

    private fun at(h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant()
    private fun build(
        steps: List<StepEntry> = emptyList(),
        sleep: List<SleepEntry> = emptyList(),
        burn: List<BurnEntry> = emptyList(),
        meals: List<MealEntry> = emptyList(),
        body: List<BodyEntry> = emptyList(),
    ) = DailyAggregator.buildSlice(day, zone, steps, sleep, burn, meals, body)

    @Test
    fun `шаги считаются только с браслета`() {
        val slice = build(
            steps = listOf(
                StepEntry(at(9), at(10), 5000, DailyAggregator.BAND_SOURCE),
                StepEntry(at(9), at(10), 6000, "com.android.healthconnect.phone.abc"),
            )
        )
        assertEquals(5000L, slice.steps)
    }

    @Test
    fun `день без браслета - шаги отсутствуют а не ноль`() {
        val slice = build(steps = listOf(StepEntry(at(9), at(10), 6000, "phone.pedometer")))
        assertNull(slice.steps)
    }

    @Test
    fun `полностью пустой день - все показатели отсутствуют`() {
        val slice = build()
        assertNull(slice.steps)
        assertNull(slice.sleepMinutes)
        assertNull(slice.burnedKcal)
        assertNull(slice.eatenKcal)
        assertNull(slice.weightKg)
    }

    @Test
    fun `сон и расход суммируются за день`() {
        val slice = build(
            sleep = listOf(SleepEntry(at(0, 30), at(8)), SleepEntry(at(14), at(15))),
            burn = listOf(BurnEntry(at(9), at(10), 120.0), BurnEntry(at(11), at(12), 80.5)),
        )
        assertEquals((8 * 60 + 30).toLong(), slice.sleepMinutes)
        assertEquals(200.5, slice.burnedKcal!!, 0.001)
    }

    @Test
    fun `еда суммируется по приёмам с БЖУ`() {
        val slice = build(
            meals = listOf(
                MealEntry(at(8), at(8, 15), kcal = 400.0, proteinG = 20.0, fatG = 10.0, carbsG = 50.0),
                MealEntry(at(13), at(13, 20), kcal = 600.0, proteinG = 30.0, fatG = 15.0, carbsG = 70.0),
            )
        )
        assertEquals(1000.0, slice.eatenKcal!!, 0.001)
        assertEquals(50.0, slice.proteinG!!, 0.001)
        assertEquals(25.0, slice.fatG!!, 0.001)
        assertEquals(120.0, slice.carbsG!!, 0.001)
    }

    @Test
    fun `приём без калорий не превращает день в ноль`() {
        val slice = build(
            meals = listOf(MealEntry(at(8), at(8, 15), kcal = null, proteinG = 20.0, fatG = null, carbsG = null))
        )
        assertNull(slice.eatenKcal)
        assertEquals(20.0, slice.proteinG!!, 0.001)
    }

    @Test
    fun `тело берёт последнее взвешивание дня`() {
        val slice = build(
            body = listOf(
                BodyEntry(at(8), weightKg = 75.0, bodyFatPct = 36.6, boneMassKg = 2.9, bmrKcalPerDay = 1454.0),
                BodyEntry(at(21), weightKg = 74.5, bodyFatPct = 36.5, boneMassKg = 2.9, bmrKcalPerDay = 1454.0),
            )
        )
        assertEquals(74.5, slice.weightKg!!, 0.001)
        assertEquals(36.5, slice.bodyFatPct!!, 0.001)
    }

    @Test
    fun `записи соседнего дня не попадают в срез`() {
        val otherDayStart = day.plusDays(1).atStartOfDay(zone).toInstant()
        val slice = build(
            steps = listOf(StepEntry(otherDayStart, otherDayStart.plusSeconds(3600), 9999, DailyAggregator.BAND_SOURCE)),
            meals = listOf(MealEntry(otherDayStart, otherDayStart, kcal = 5000.0, proteinG = null, fatG = null, carbsG = null)),
        )
        assertNull(slice.steps)
        assertNull(slice.eatenKcal)
    }

    @Test
    fun `слияние со свежим пересчётом не теряет старые поля`() {
        val stored = build(steps = listOf(StepEntry(at(9), at(10), 5000, DailyAggregator.BAND_SOURCE)))
        val fresh = build(body = listOf(BodyEntry(at(8), weightKg = 74.5, bodyFatPct = null, boneMassKg = null, bmrKcalPerDay = null)))
        val merged = stored.mergeFresh(fresh)
        assertEquals(5000L, merged.steps)
        assertEquals(74.5, merged.weightKg!!, 0.001)
    }

    @Test
    fun `интервал через полночь принадлежит дню начала`() {
        val lateNight = day.atTime(23, 50).atZone(zone).toInstant()
        val afterMidnight = day.plusDays(1).atTime(0, 10).atZone(zone).toInstant()
        val slice = build(
            sleep = listOf(SleepEntry(lateNight, afterMidnight)),
            burn = listOf(BurnEntry(lateNight, afterMidnight, 30.0)),
        )
        assertEquals(20L, slice.sleepMinutes)
        assertEquals(30.0, slice.burnedKcal!!, 0.001)
    }
}
