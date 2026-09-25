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
        wellbeing: Map<LocalDate, Wellbeing> = mapOf(yesterday to this.wellbeing),
        profile: Profile? = null,
    ) = buildChatContext(today, daysBack = 3, data = DayData(slices, wellbeing), profile = profile)

    @Test
    fun `контекст содержит показатели среза`() {
        val text = context()
        assertTrue(text.contains("шаги 8432"))
        assertTrue(text.contains("сон 7 ч 20 м"))
        assertTrue(text.contains("сожжено 2100 ккал"))
        assertTrue(text.contains("съедено 1800 ккал"))
        assertTrue(text.contains("Б 90 / Ж 70 / У 180 г"))
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
    fun `видимые строки без типографских тире`() {
        val text = context(
            profile = Profile(heightCm = 168, birthDateIso = "1990-05-14", goalWeightKg = 60.0)
        )
        assertFalse(text.contains("—"))
        assertFalse(text.contains("–"))
        assertFalse(CHAT_SYSTEM_PROMPT.contains("—"))
    }
}
