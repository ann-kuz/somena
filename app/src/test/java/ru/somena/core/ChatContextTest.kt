package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextTest {

    private val today = LocalDate.of(2026, 9, 25)
    private val yesterday = today.minusDays(1)

    private val slice = DaySlice(
        date = yesterday,
        steps = 8432,
        sleepMinutes = 440,
        burnedKcal = 2100.0,
        eatenKcal = 1800.0,
        proteinG = 90.0,
        fatG = 70.0,
        carbsG = 180.0,
        weightKg = 62.4,
        bodyFatPct = 28.1,
    )
    private val wellbeing = Wellbeing(yesterday, energy = 7, mood = 8, sleepQuality = 6, note = "устала")

    private fun context(
        slices: Map<LocalDate, DaySlice> = mapOf(yesterday to slice),
        wellbeing: Map<LocalDate, List<Wellbeing>> = mapOf(yesterday to listOf(this.wellbeing)),
        profile: Profile? = null,
        cycle: List<CycleDay> = emptyList(),
    ) = buildChatContext(today, daysBack = 3, data = DayData(slices, wellbeing), profile = profile, cycle = cycle)

    @Test
    fun `контекст содержит показатели среза`() {
        val text = context()
        assertTrue(text.contains("шаги 8432"))
        assertTrue(text.contains("сон 7 ч 20 м"))
        assertTrue(text.contains("сожжено 2100 ккал"))
        assertTrue(text.contains("съедено 1800 ккал"))
        assertTrue(text.contains("Б 90 / Ж 70 / У 180 г"))
        // Дефицит: разница сожжённых и съеденных калорий (спека 0005).
        assertTrue(text.contains("дефицит 300 ккал"))
        assertTrue(text.contains("вес 62.4 кг"))
        assertTrue(text.contains("жир 28.1%"))
    }

    @Test
    fun `контекст учитывает Самочувствие и заметку`() {
        val text = context()
        assertTrue(text.contains("энергия 7/10"))
        assertTrue(text.contains("настроение 8/10"))
        assertTrue(text.contains("сон 6/10"))
        assertTrue(text.contains("заметка: «устала»"))
    }

    @Test
    fun `две отметки Самочувствия попадают в контекст обе`() {
        val evening = wellbeing.copy(slot = Wellbeing.SLOT_SECOND, energy = 4, mood = 5, note = "полегче")
        val text = context(wellbeing = mapOf(yesterday to listOf(wellbeing, evening)))
        assertTrue(text.contains("самочувствие (первая и вторая отметки): энергия 7 и 4 из 10, настроение 8 и 5 из 10, сон 6 и 6 из 10"))
        assertTrue(text.contains("заметки: «устала», «полегче»"))
    }

    @Test
    fun `дефицит в контексте прибавляет ручной обмен из профиля`() {
        val text = context(profile = Profile(bmrKcal = 1200.0))
        assertTrue(text.contains("дефицит 1500 ккал"))
        assertTrue(text.contains("обмен 1200 ккал/дн"))
    }

    @Test
    fun `обмен с весов сильнее ручного в дефиците`() {
        val text = context(
            slices = mapOf(yesterday to slice.copy(bmrKcal = 1300.0)),
            profile = Profile(bmrKcal = 1200.0),
        )
        assertTrue(text.contains("дефицит 1600 ккал"))
        assertTrue(text.contains("обмен 1300 ккал/дн"))
    }

    @Test
    fun `дни без данных перечисляются отдельно и не превращаются в нули`() {
        val text = context(slices = emptyMap(), wellbeing = emptyMap())
        assertTrue(text.contains("Дней без данных: 3"))
        assertTrue(text.contains("Это пропуски, а не нули"))
        assertFalse(text.contains("шаги 0"))
    }

    @Test
    fun `профиль попадает в контекст с вычисленным возрастом`() {
        val text = context(
            profile = Profile(heightCm = 168, birthDateIso = "1990-05-14", goalWeightKg = 60.0)
        )
        assertTrue(text.contains("рост 168 см"))
        assertTrue(text.contains("полных лет 36"))
        assertTrue(text.contains("цель по весу 60 кг"))
    }

    @Test
    fun `пустой профиль помечен честно`() {
        val text = context(profile = Profile())
        assertTrue(text.contains("Профиль: не заполнен"))
    }

    @Test
    fun `явная нет боли отличается от неотмеченного`() {
        val text = context(
            cycle = listOf(
                CycleDay(yesterday, menstruation = true, pain = CycleDay.PAIN_NONE),
                CycleDay(today.minusDays(2), menstruation = true, flow = 1),
            )
        )
        assertTrue(text.contains("менструация, боли нет"))
        assertTrue(text.contains("менструация, выделения скудные"))
        assertFalse(text.contains("выделения скудные, боль"))
    }

    @Test
    fun `видимые строки без типографских тире`() {
        val text = context(
            profile = Profile(heightCm = 168, birthDateIso = "1990-05-14", goalWeightKg = 60.0)
        )
        assertFalse(text.contains("—"))
        assertFalse(text.contains("–"))
        assertFalse(CHAT_SYSTEM_PROMPT.contains("—"))
    }

    @Test
    fun `менструация с интенсивностью и болью попадает в строку дня`() {
        val text = context(
            cycle = listOf(
                CycleDay(yesterday, menstruation = true, flow = 3, pain = 1),
                CycleDay(today.minusDays(2), menstruation = false, pain = 2),
            )
        )
        assertTrue(text.contains("менструация, выделения обильные, боль слабая"))
        assertTrue(text.contains("боль средняя"))
    }

    @Test
    fun `строка Цикл содержит последний период и прогноз начала`() {
        val text = context(
            cycle = listOf(CycleDay(LocalDate.of(2026, 9, 23), menstruation = true, flow = 2))
        )
        assertTrue(text.contains("Цикл: последний период 23.09 - 23.09"))
        assertTrue(text.contains("прогноз начала 21.10"))
        assertTrue(text.contains("приблизительно"))
    }

    @Test
    fun `фертильное окно и овуляция отмечаются в днях как прогноз`() {
        // Один период 11.09: прогноз следующего начала 09.10, овуляция 09.10 - 14 = 25.09,
        // фертильное окно 20.09 - 25.09 - попадает в окно контекста 23-25.09.
        val text = context(cycle = listOf(CycleDay(LocalDate.of(2026, 9, 11), menstruation = true)))
        assertTrue(text.contains("фертильное окно (прогноз)"))
        assertTrue(text.contains("25.09: овуляция (прогноз)"))
    }

    @Test
    fun `без записей цикл помечен честно`() {
        assertTrue(context().contains("Цикл: записей нет"))
    }
}
