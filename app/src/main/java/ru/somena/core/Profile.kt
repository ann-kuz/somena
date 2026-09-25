package ru.somena.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Профиль Пользователя (тикет 03): контекст для будущего Чата по данным.
 * Все поля необязательны — пустой Профиль не блокирует остальные экраны.
 * Храним дату рождения, а не возраст: возраст вычисляется на дату вопроса.
 */
@Serializable
data class Profile(
    val heightCm: Int? = null,
    val birthDateIso: String? = null,
    val goalWeightKg: Double? = null,
) {
    fun ageYears(today: LocalDate): Int? = birthDate
        ?.let { Period.between(it, today).years.takeIf { y -> y >= 0 } }

    val birthDate: LocalDate?
        get() = birthDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

object ProfileValidator {

    data class Error(val field: String, val message: String)

    fun validate(profile: Profile, today: LocalDate): List<Error> = buildList {
        profile.heightCm?.let {
            if (it !in 100..250) add(Error("heightCm", "Рост: разумно 100–250 см"))
        }
        profile.birthDate?.let {
            if (it.isBefore(LocalDate.of(1920, 1, 1)) || it.isAfter(today.minusYears(10))) {
                add(Error("birthDate", "Дата рождения: разумно от 01.01.1920 до ${today.minusYears(10).format(RU_DATE)}"))
            }
        }
        profile.goalWeightKg?.let {
            if (it !in 30.0..300.0) add(Error("goalWeightKg", "Цель по весу: разумно 30–300 кг"))
        }
    }
}

private val RU_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)

/** «14.05.1990» → дата; строго, с проверкой существования дня. Пусто/неверно = null. */
fun parseBirthDate(raw: String): LocalDate? =
    runCatching { LocalDate.parse(raw.trim(), RU_DATE) }.getOrNull()

/** Ошибка ввода даты рождения для экрана; null = ввод корректен или пуст. */
fun birthDateFieldError(raw: String, today: LocalDate): String? {
    if (raw.isBlank()) return null
    val d = parseBirthDate(raw) ?: return "Формат: ДД.ММ.ГГГГ"
    return if (d.isBefore(LocalDate.of(1920, 1, 1)) || d.isAfter(today.minusYears(10))) {
        "Разумно: 01.01.1920 – ${today.minusYears(10).format(RU_DATE)}"
    } else null
}

/** Не-числовой ввод не должен молча превращаться в «пусто» (находка код-ревью тикета 03). */
fun numericFieldError(raw: String, integer: Boolean): String? = when {
    raw.isBlank() -> null
    integer && parseOptionalInt(raw) == null -> "Введите целое число"
    !integer && parseOptionalDouble(raw) == null -> "Введите число"
    else -> null
}

/** Разбор числа из русского ввода: «74,5» и «74.5» одинаково допустимы. Пусто/мусор = null. */
fun parseOptionalDouble(raw: String): Double? =
    raw.trim().replace(',', '.').toDoubleOrNull()

fun parseOptionalInt(raw: String): Int? =
    raw.trim().toIntOrNull()
