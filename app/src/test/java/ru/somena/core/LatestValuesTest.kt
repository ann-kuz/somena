package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Последние известные значения для «Сегодня»: каждая метрика берётся из самого
 * позднего дня, где она есть, вместе с этим днём (свежесть решает экран).
 */
class LatestValuesTest {

    private val d26 = LocalDate.of(2026, 9, 26)
    private val d27 = LocalDate.of(2026, 9, 27)
    private val d30 = LocalDate.of(2026, 9, 30)

    @Test
    fun `пустой список дает пустые последние значения`() {
        val latest = latestValues(emptyList())
        assertTrue(latest.isEmpty)
        assertNull(latest.steps)
        assertNull(latest.weightKg)
    }

    @Test
    fun `каждая метрика берется из своего последнего дня`() {
        // Вес последний раз взвешивали 27-го, шаги есть и 30-го.
        val latest = latestValues(
            listOf(
                DaySlice(d26, steps = 9000, weightKg = 62.0),
                DaySlice(d27, weightKg = 62.4),
                DaySlice(d30, steps = 4500),
            ),
        )
        assertEquals(4500L, latest.steps?.value)
        assertEquals(d30, latest.steps?.on)
        assertEquals(62.4, latest.weightKg!!.value, 0.0)
        assertEquals(d27, latest.weightKg!!.on)
    }

    @Test
    fun `метрика без значений нигде остается пустой`() {
        val latest = latestValues(listOf(DaySlice(d30, steps = 1000)))
        assertNull(latest.sleepMinutes)
        assertNull(latest.carbsG)
    }

    @Test
    fun `значение из последнего дня а не максимальное`() {
        // Шаги 30-го меньше шагов 26-го: «последнее известное» - именно 1200.
        val latest = latestValues(
            listOf(DaySlice(d26, steps = 9000), DaySlice(d30, steps = 1200)),
        )
        assertEquals(1200L, latest.steps?.value)
    }

    @Test
    fun `нуль это значение а не пустота`() {
        // Спека 0001: день без данных ≠ ноль; записанный ноль - настоящее значение.
        val latest = latestValues(listOf(DaySlice(d26, burnedKcal = 0.0), DaySlice(d30, steps = 10)))
        assertEquals(0.0, latest.burnedKcal?.value!!, 0.0)
        assertEquals(d26, latest.burnedKcal?.on)
    }

    @Test
    fun `порядок срезов не важен - берется самый поздний день метрики`() {
        val latest = latestValues(listOf(DaySlice(d30, steps = 100), DaySlice(d26, steps = 9000)))
        assertEquals(100L, latest.steps?.value)
        assertEquals(d30, latest.steps?.on)
    }

    @Test
    fun `вес сегодня а костная масса вчера - каждая метрика сама по себе`() {
        // Весы могут прислать вес без состава тела: свежесть каждой метрики тела
        // решается её собственным днём, а не днём взвешивания целиком.
        val d29 = LocalDate.of(2026, 9, 29)
        val latest = latestValues(
            listOf(DaySlice(d29, weightKg = 62.0, boneMassKg = 3.1), DaySlice(d30, weightKg = 62.4)),
        )
        assertEquals(62.4, latest.weightKg!!.value, 0.0)
        assertEquals(d30, latest.weightKg!!.on)
        assertEquals(3.1, latest.boneMassKg!!.value, 0.0)
        assertEquals(d29, latest.boneMassKg!!.on)
    }

    @Test
    fun `суточная метрика сегодня показывает свое значение`() {
        val latest = latestValues(listOf(DaySlice(d30, eatenKcal = 1200.0)))
        assertEquals(1200.0, latest.eatenKcal.todayOrZero(d30), 0.0)
    }

    @Test
    fun `суточная метрика без сегодняшних данных зануляется а не тянется со вчера`() {
        // Съедено и Сожжено копятся за день: новым днём показывается ноль,
        // вчерашний итог не показывается вовсе.
        val latest = latestValues(listOf(DaySlice(d27, eatenKcal = 2100.0, burnedKcal = 1800.0)))
        assertEquals(0.0, latest.eatenKcal.todayOrZero(d30), 0.0)
        assertEquals(0.0, latest.burnedKcal.todayOrZero(d30), 0.0)
    }

    @Test
    fun `суточная метрика без данных вообще тоже ноль`() {
        val none: MetricLatest<Double>? = null
        assertEquals(0.0, none.todayOrZero(d30), 0.0)
    }
}
