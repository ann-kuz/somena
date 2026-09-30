package ru.somena.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Раскладка плашек «Сегодня»: порядок, скрытость и перенос - чистая логика,
 * хранилище хранит только идентификаторы.
 */
class TodayLayoutTest {

    private val available = todayPlatesFor(male = false)

    @Test
    fun `пустое хранилище дает порядок по умолчанию без скрытых`() {
        val layout = todayLayout(emptyList(), emptySet(), available)
        assertEquals(available, layout.order)
        assertTrue(layout.hidden.isEmpty())
        assertEquals(available, layout.visible)
    }

    @Test
    fun `незнакомые идентификаторы выбрасываются забытые плашки дописываются в конец`() {
        val layout = todayLayout(
            orderIds = listOf("weight", "no-such-plate", "steps"),
            hiddenIds = emptySet(),
            available = available,
        )
        assertEquals(TodayPlate.WEIGHT, layout.order[0])
        assertEquals(TodayPlate.STEPS, layout.order[1])
        assertEquals("забытые дописываются в конце", TodayPlate.SLEEP, layout.order[2])
        assertEquals(available.size, layout.order.size)
    }

    @Test
    fun `пол м - календарь цикла недоступен и не хранится`() {
        val malePlates = todayPlatesFor(male = true)
        assertFalse(malePlates.contains(TodayPlate.CYCLE))
        val layout = todayLayout(
            orderIds = listOf("cycle", "steps"),
            hiddenIds = setOf("cycle"),
            available = malePlates,
        )
        assertFalse(layout.order.contains(TodayPlate.CYCLE))
        assertFalse(layout.hidden.contains(TodayPlate.CYCLE))
    }

    @Test
    fun `скрытость вне порядка не учитывается`() {
        val layout = todayLayout(listOf("steps"), setOf("sleep"), available)
        assertFalse(layout.hidden.contains(TodayPlate.SLEEP))
    }

    @Test
    fun `дубли идентификатора не размножают плашку`() {
        val layout = todayLayout(listOf("steps", "steps", "sleep"), emptySet(), available)
        assertEquals(available.size, layout.order.size)
    }

    @Test
    fun `перенос сдвигает остальных`() {
        val order = listOf(TodayPlate.STEPS, TodayPlate.SLEEP, TodayPlate.BURNED, TodayPlate.EATEN)
        val moved = TodayLayout(order, emptySet()).moved(TodayPlate.EATEN, 1)
        assertEquals(
            listOf(TodayPlate.STEPS, TodayPlate.EATEN, TodayPlate.SLEEP, TodayPlate.BURNED),
            moved.order,
        )
    }

    @Test
    fun `перенос за границы и на своё место ничего не меняет`() {
        val layout = TodayLayout(available, emptySet())
        assertEquals(layout, layout.moved(TodayPlate.STEPS, -3))
        assertEquals(layout, layout.moved(TodayPlate.STEPS, available.size + 5))
        assertEquals(layout, layout.moved(TodayPlate.STEPS, 0))
        assertEquals("чужой плашки в порядке нет - перенос молчит", layout, layout.moved(TodayPlate.BURNED, 2))
    }

    @Test
    fun `скрытая плашка помнит место - показать возвращает к порядку`() {
        var layout = TodayLayout(listOf(TodayPlate.STEPS, TodayPlate.SLEEP, TodayPlate.BURNED), emptySet())
        layout = layout.hiddenAs(TodayPlate.SLEEP, hide = true)
        assertEquals(listOf(TodayPlate.STEPS, TodayPlate.BURNED), layout.visible)
        layout = layout.hiddenAs(TodayPlate.SLEEP, hide = false)
        assertEquals(listOf(TodayPlate.STEPS, TodayPlate.SLEEP, TodayPlate.BURNED), layout.visible)
    }

    @Test
    fun `скрытие не меняет порядок целиком`() {
        var layout = TodayLayout(listOf(TodayPlate.STEPS, TodayPlate.SLEEP, TodayPlate.BURNED), emptySet())
        val before = layout.order
        layout = layout.hiddenAs(TodayPlate.STEPS, hide = true)
        assertEquals(before, layout.order)
        assertTrue(layout.hidden.contains(TodayPlate.STEPS))
    }

    @Test
    fun `метрические плашки - все кроме самочувствия и цикла`() {
        assertTrue(TodayPlate.STEPS.isMetric)
        assertFalse(TodayPlate.WELLBEING.isMetric)
        assertFalse(TodayPlate.CYCLE.isMetric)
    }

    @Test
    fun `каждой метрической плашке - своя категория ручного ввода`() {
        available.forEach { plate ->
            if (plate.isMetric) {
                assertEquals("плашка ${plate.title}", plate.title, plate.manualMetric()!!.label)
            } else {
                assertNull(plate.manualMetric())
            }
        }
    }

    @Test
    fun `перенос списка двигает элемент и сдвигает остальных`() {
        val list = listOf("а", "б", "в", "г")
        assertEquals(listOf("а", "в", "б", "г"), list.moved("в", 1))
        assertEquals(listOf("б", "а", "в", "г"), list.moved("б", 0))
        assertEquals("за границами - без изменений", list, list.moved("б", 9))
    }
}
