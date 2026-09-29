package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ручное внесение данных кнопкой «Внести данные» (меню: категория - дата - значение):
 * чистая логика без модели - Быстрая ступень отвечала «записала» без Предпросмотра
 * (инцидент 29.09), поэтому кнопка вообще не зависит от ИИ.
 */
class ManualEntryTest {

    private val today = LocalDate.of(2026, 9, 29)
    private val d27 = LocalDate.of(2026, 9, 27)

    @Test
    fun `число понимается с запятой и точкой, мусор отбрасывается`() {
        assertEquals(400.0, parseManualNumber("400")!!, 0.01)
        assertEquals(62.4, parseManualNumber("62,4")!!, 0.01)
        assertEquals(7.5, parseManualNumber("7.5")!!, 0.01)
        assertNull(parseManualNumber(""))
        assertNull(parseManualNumber("примерно четыреста"))
        assertNull(parseManualNumber("400 ккал"))
    }

    @Test
    fun `значение проверяется диапазоном своей категории`() {
        assertNull(manualProblem(ManualMetric.BURNED, "400"))
        assertNull(manualProblem(ManualMetric.BURNED, "0"))
        assertNotNull("сожжённое за день не бывает 999999", manualProblem(ManualMetric.BURNED, "999999"))
        assertNotNull("вес человека не бывает 5 кг", manualProblem(ManualMetric.WEIGHT, "5"))
        assertNotNull("сна больше суток не бывает", manualProblem(ManualMetric.SLEEP, "25"))
        assertNull(manualProblem(ManualMetric.SLEEP, "7.5"))
        assertNotNull("шаги дробными не бывают", manualProblem(ManualMetric.STEPS, "8000.5"))
        assertNull(manualProblem(ManualMetric.STEPS, "8000"))
    }

    @Test
    fun `сожжённое попадает в предпросмотр с прежним значением дня`() {
        val existing = DaySlice(date = d27, steps = 7000, burnedKcal = 50.0)
        val preview = manualSlicePreview(ManualMetric.BURNED, 400.0, d27, existing)
        val entry = preview.entries.single()
        assertEquals(400.0, entry.values.burnedKcal!!, 0.01)
        assertEquals(50.0, entry.old.burnedKcal!!, 0.01)
        assertEquals(1, preview.replacedCount)
    }

    @Test
    fun `новый день появляется в предпросмотре без замен`() {
        val preview = manualSlicePreview(ManualMetric.EATEN, 1800.0, d27, existing = null)
        val entry = preview.entries.single()
        assertEquals(1800.0, entry.values.eatenKcal!!, 0.01)
        assertNull(entry.old.eatenKcal)
        assertEquals(0, preview.replacedCount)
    }

    @Test
    fun `сон вводится часами и хранится минутами`() {
        val preview = manualSlicePreview(ManualMetric.SLEEP, 7.5, d27, existing = null)
        assertEquals(450L, preview.entries.single().values.sleepMinutes)
    }

    @Test
    fun `самочувствие собирается полной строкой трёх шкал`() {
        val old = Wellbeing(d27, energy = 5, mood = 4, sleepQuality = 6)
        val preview = manualWellbeingPreview(d27, energy = 7, mood = 8, sleepQuality = 6, existing = old)
        val entry = preview.wellbeing.single()
        assertEquals(7, entry.values.energy)
        assertEquals(8, entry.values.mood)
        assertEquals(5, entry.old.energy)
        assertEquals(1, preview.wellbeingReplacedCount)
    }

    @Test
    fun `шкалы самочувствия - целые от нуля до десяти`() {
        assertNull(manualWellbeingProblem("7", "8", "6"))
        assertNotNull(manualWellbeingProblem("11", "8", "6"))
        assertNotNull(manualWellbeingProblem("7", "", "6"))
        assertNotNull(manualWellbeingProblem("7.5", "8", "6"))
    }

    @Test
    fun `категории накрывают показатели среза и самочувствие`() {
        val keys = ManualMetric.entries.map { it.wireKey }
        listOf("burned_kcal", "eaten_kcal", "weight", "steps", "sleep_h").forEach {
            assertTrue("категория $it", keys.contains(it))
        }
    }
}
