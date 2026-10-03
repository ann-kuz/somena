package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты Расхода от шагов (спека 0017): формула обычной ходьбы по ровной
 * местности и замена показателя «Сожжено» в срезах при включённом
 * переключателе. Чистая логика без Android-зависимостей.
 */
class StepsBurnTest {

    private val day: LocalDate = LocalDate.of(2026, 10, 1)
    private val profile = Profile(
        heightCm = 168,
        birthDateIso = "1990-05-14",
        sex = Sex.FEMALE,
    )

    @Test
    fun `формула ходьбы от веса, роста, возраста и пола`() {
        // 70 кг, 168 см, 36 лет (на 01.10.2026 при рождении 14.05.1990), женский:
        // обмен 1409 ккал/дн, минута ходьбы МЕТ 3,5, 10 000 шагов темпом
        // 100 шаг/мин = 100 минут = 342 ккал.
        val kcal = StepsBurn.kcal(10_000, 70.0, profile, day)
        assertEquals(342.0, kcal!!, 0.5)
    }

    @Test
    fun `возраст считается полных лет на день расчёта`() {
        // 14.05.1990: 30.04.2026 - ещё 35 лет, 15.05.2026 - уже 36; годом больше
        // обмен ниже, калорий ходьбы меньше.
        val before = StepsBurn.kcal(10_000, 70.0, profile, LocalDate.of(2026, 4, 30))!!
        val after = StepsBurn.kcal(10_000, 70.0, profile, LocalDate.of(2026, 5, 15))!!
        org.junit.Assert.assertTrue("ожидалось $before > $after", before > after)
    }

    @Test
    fun `без даты рождения расчёт невозможен - день остаётся на записанном`() {
        assertNull(StepsBurn.kcal(10_000, 70.0, Profile(), day))
    }

    @Test
    fun `нулевые шаги дают ноль калорий`() {
        assertEquals(0.0, StepsBurn.kcal(0, 70.0, profile, day)!!, 0.0)
    }

    @Test
    fun `вес несётся вперёд до нового взвешивания`() {
        val slices = listOf(
            DaySlice(LocalDate.of(2026, 9, 28), steps = 5_000, weightKg = 70.0, burnedKcal = 10.0),
            DaySlice(LocalDate.of(2026, 9, 29), steps = 8_000),
        ).withStepsBurn(profile)
        assertEquals(StepsBurn.kcal(5_000, 70.0, profile, LocalDate.of(2026, 9, 28)), slices[0].burnedKcal)
        assertEquals(StepsBurn.kcal(8_000, 70.0, profile, LocalDate.of(2026, 9, 29)), slices[1].burnedKcal)
    }

    @Test
    fun `новое взвешивание меняет расчёт со своего дня`() {
        val slices = listOf(
            DaySlice(LocalDate.of(2026, 9, 28), steps = 5_000, weightKg = 70.0),
            DaySlice(LocalDate.of(2026, 9, 29), steps = 5_000, weightKg = 75.0),
        ).withStepsBurn(profile)
        val heavier = StepsBurn.kcal(5_000, 75.0, profile, LocalDate.of(2026, 9, 29))!!
        org.junit.Assert.assertTrue(slices[1].burnedKcal!! > slices[0].burnedKcal!!)
        assertEquals(heavier, slices[1].burnedKcal)
    }

    @Test
    fun `день без шагов оставляет записанный расход`() {
        val slices = listOf(
            DaySlice(LocalDate.of(2026, 9, 28), weightKg = 70.0, burnedKcal = 450.0),
        ).withStepsBurn(profile)
        assertEquals(450.0, slices[0].burnedKcal!!, 0.0)
    }

    @Test
    fun `без взвешиваний вовсе расход остаётся записанным`() {
        val slices = listOf(
            DaySlice(LocalDate.of(2026, 9, 28), steps = 8_000, burnedKcal = 300.0),
        ).withStepsBurn(profile)
        assertEquals(300.0, slices[0].burnedKcal!!, 0.0)
    }

    @Test
    fun `пустой рост и пол берутся средними женщинами`() {
        // Тот же день и вес, но Профиль без роста и пола: средние 168 см и женский
        // дают то же число, что и заполненный профиль.
        val filled = StepsBurn.kcal(10_000, 70.0, profile, day)
        val sparse = StepsBurn.kcal(10_000, 70.0, Profile(birthDateIso = "1990-05-14"), day)
        assertEquals(filled, sparse)
    }

    @Test
    fun `мужской пол сжигает больше при том же весе`() {
        val female = StepsBurn.kcal(10_000, 60.0, profile, day)!!
        val male = StepsBurn.kcal(10_000, 60.0, Profile(birthDateIso = "1990-05-14", sex = Sex.MALE), day)!!
        org.junit.Assert.assertTrue(male > female)
    }
}
