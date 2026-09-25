package ru.somena.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.somena.core.numericFieldError
import ru.somena.core.parseOptionalDouble
import ru.somena.core.parseOptionalInt

class ProfileValidatorTest {

    private val noErrors: (List<ProfileValidator.Error>) -> Unit = { assertTrue(it.isEmpty()) }

    @Test
    fun `пустой профиль валиден`() {
        noErrors(ProfileValidator.validate(Profile()))
    }

    @Test
    fun `разумные значения проходят`() {
        noErrors(ProfileValidator.validate(Profile(heightCm = 168, ageYears = 30, goalWeightKg = 65.0)))
    }

    @Test
    fun `нереалистичные значения отбраковываются`() {
        val errors = ProfileValidator.validate(Profile(heightCm = 50, ageYears = 300, goalWeightKg = 1000.0))
        assertEquals(setOf("heightCm", "ageYears", "goalWeightKg"), errors.map { it.field }.toSet())
    }

    @Test
    fun `границы диапазонов включены`() {
        noErrors(ProfileValidator.validate(Profile(heightCm = 100, ageYears = 10, goalWeightKg = 30.0)))
        noErrors(ProfileValidator.validate(Profile(heightCm = 250, ageYears = 100, goalWeightKg = 300.0)))
    }

    @Test
    fun `русский ввод с запятой разбирается`() {
        assertEquals(74.5, parseOptionalDouble("74,5")!!, 0.001)
        assertEquals(74.5, parseOptionalDouble(" 74.5 ")!!, 0.001)
        assertEquals(null, parseOptionalDouble(""))
        assertEquals(30, parseOptionalInt("30"))
        assertEquals(null, parseOptionalInt("30,5"))
    }

    @Test
    fun `мусорный ввод даёт ошибку а не молчаливую пустоту`() {
        assertEquals("Введите целое число", numericFieldError("abc", integer = true))
        assertEquals("Введите число", numericFieldError("abc", integer = false))
        assertEquals("Введите число", numericFieldError("7..", integer = false))
        // «74,» после замены запятой становится «74.» — Java считает это числом 74.0, не мусор
        assertEquals(null, numericFieldError("74,", integer = false))
        assertEquals(null, numericFieldError("", integer = true))
        assertEquals(null, numericFieldError("65,5", integer = false))
    }
}
