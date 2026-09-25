package ru.somena.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProfileValidatorTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 25)
    private val noErrors: (List<ProfileValidator.Error>) -> Unit = { assertTrue(it.isEmpty()) }

    @Test
    fun `пустой профиль валиден`() {
        noErrors(ProfileValidator.validate(Profile(), today))
    }

    @Test
    fun `разумные значения проходят`() {
        noErrors(
            ProfileValidator.validate(
                Profile(heightCm = 168, birthDateIso = "1990-05-14", goalWeightKg = 65.0), today
            )
        )
    }

    @Test
    fun `нереалистичные значения отбраковываются`() {
        val errors = ProfileValidator.validate(
            Profile(heightCm = 50, birthDateIso = "1890-01-01", goalWeightKg = 1000.0), today
        )
        assertEquals(setOf("heightCm", "birthDate", "goalWeightKg"), errors.map { it.field }.toSet())
    }

    @Test
    fun `возраст считается из даты рождения на заданный день`() {
        val p = Profile(birthDateIso = "1990-09-25")
        assertEquals(36, p.ageYears(today))
        assertEquals(35, p.ageYears(today.minusDays(1)))
        assertNull(Profile().ageYears(today))
    }

    @Test
    fun `границы диапазонов включены`() {
        noErrors(
            ProfileValidator.validate(
                Profile(heightCm = 100, birthDateIso = "2016-09-25", goalWeightKg = 30.0), today
            )
        )
        noErrors(
            ProfileValidator.validate(
                Profile(heightCm = 250, birthDateIso = "1920-01-01", goalWeightKg = 300.0), today
            )
        )
    }

    @Test
    fun `дата рождения строго проверяется на существование`() {
        assertNull(parseBirthDate("31.02.1990")) // 31 февраля не существует
        assertNull(parseBirthDate("14.13.1990")) // 13-го месяца не существует
        assertNull(parseBirthDate("май 1990"))
        assertEquals(LocalDate.of(1990, 5, 14), parseBirthDate("14.05.1990"))
    }

    @Test
    fun `ошибки поля даты рождения на экране`() {
        assertEquals("Формат: ДД.ММ.ГГГГ", birthDateFieldError("май", today))
        assertEquals(null, birthDateFieldError("", today))
        assertTrue(birthDateFieldError("14.05.2020", today)!!.contains("Разумно"))
        assertEquals(null, birthDateFieldError("14.05.1990", today))
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
