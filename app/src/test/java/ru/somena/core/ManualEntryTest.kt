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
        listOf("burned_kcal", "eaten_kcal", "weight", "steps", "sleep_h", "body_fat", "bone", "bmr").forEach {
            assertTrue("категория $it", keys.contains(it))
        }
    }

    @Test
    fun `состав тела и базовый расход проверяются своими диапазонами`() {
        assertNull(manualProblem(ManualMetric.BODY_FAT, "27.5"))
        assertNotNull("процента жира меньше одного не бывает", manualProblem(ManualMetric.BODY_FAT, "0.5"))
        assertNull(manualProblem(ManualMetric.BONE, "2.6"))
        assertNotNull(manualProblem(ManualMetric.BONE, "0.3"))
        assertNull(manualProblem(ManualMetric.BMR, "1450"))
        assertNotNull("базового расхода меньше пятисот не бывает", manualProblem(ManualMetric.BMR, "100"))
    }

    @Test
    fun `сейчас в базе читается по категории среза`() {
        val slice = DaySlice(
            date = d27, burnedKcal = 50.0, eatenKcal = 1800.0, weightKg = 62.0,
            steps = 7000, sleepMinutes = 420, bodyFatPct = 28.1, boneMassKg = 2.6, bmrKcal = 1400.0,
        )
        assertEquals(50.0, slice.manualOldValue(ManualMetric.BURNED)!!, 0.01)
        assertEquals(1800.0, slice.manualOldValue(ManualMetric.EATEN)!!, 0.01)
        assertEquals(62.0, slice.manualOldValue(ManualMetric.WEIGHT)!!, 0.01)
        assertEquals(7000.0, slice.manualOldValue(ManualMetric.STEPS)!!, 0.01)
        assertEquals("сон вводится часами", 7.0, slice.manualOldValue(ManualMetric.SLEEP)!!, 0.01)
        assertEquals(28.1, slice.manualOldValue(ManualMetric.BODY_FAT)!!, 0.01)
        assertEquals(2.6, slice.manualOldValue(ManualMetric.BONE)!!, 0.01)
        assertEquals(1400.0, slice.manualOldValue(ManualMetric.BMR)!!, 0.01)
        assertNull(slice.manualOldValue(ManualMetric.WELLBEING))
        val none: DaySlice? = null
        assertNull(none.manualOldValue(ManualMetric.WEIGHT))
    }

    @Test
    fun `граммы бжу необязательны и проверяются своим диапазоном`() {
        assertNull(manualMacroProblem(""))
        assertNull(manualMacroProblem("90"))
        assertNull(manualMacroProblem("90,5"))
        assertNotNull("двух тысяч грамм не бывает", manualMacroProblem("2500"))
        assertNotNull(manualMacroProblem("примерно девяносто"))
    }

    @Test
    fun `съедено с бжу попадает в предпросмотр с прежним питанием дня`() {
        val existing = DaySlice(date = d27, eatenKcal = 1200.0, proteinG = 60.0, fatG = 40.0, carbsG = 150.0)
        val preview = manualEatenPreview(
            kcal = 1800.0, proteinG = 90.0, fatG = null, carbsG = 70.0, date = d27, existing = existing,
        )
        val entry = preview.entries.single()
        assertEquals(1800.0, entry.values.eatenKcal!!, 0.01)
        assertEquals(90.0, entry.values.proteinG!!, 0.01)
        assertNull("пустое поле - не ноль", entry.values.fatG)
        assertEquals(70.0, entry.values.carbsG!!, 0.01)
        assertEquals(1200.0, entry.old.eatenKcal!!, 0.01)
        assertEquals(60.0, entry.old.proteinG!!, 0.01)
        assertEquals(1, preview.replacedCount)
    }

    @Test
    fun `базовый расход попадает в предпросмотр и строку значений`() {
        val preview = manualSlicePreview(ManualMetric.BMR, 1450.0, d27, existing = null)
        assertEquals(1450.0, preview.entries.single().values.bmrKcal!!, 0.01)
        assertEquals("базовый расход 1450 ккал/дн", preview.entries.single().values.describe())
    }
}
