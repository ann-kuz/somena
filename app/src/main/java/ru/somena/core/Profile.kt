package ru.somena.core

import kotlinx.serialization.Serializable

/**
 * Профиль Пользователя (тикет 03): контекст для будущего Чата по данным.
 * Все поля необязательны — пустой Профиль не блокирует остальные экраны.
 */
@Serializable
data class Profile(
    val heightCm: Int? = null,
    val ageYears: Int? = null,
    val goalWeightKg: Double? = null,
) {
    val isEmpty: Boolean get() = heightCm == null && ageYears == null && goalWeightKg == null
}

object ProfileValidator {

    data class Error(val field: String, val message: String)

    fun validate(profile: Profile): List<Error> = buildList {
        profile.heightCm?.let {
            if (it !in 100..250) add(Error("heightCm", "Рост: разумно 100–250 см"))
        }
        profile.ageYears?.let {
            if (it !in 10..100) add(Error("ageYears", "Возраст: разумно 10–100 лет"))
        }
        profile.goalWeightKg?.let {
            if (it !in 30.0..300.0) add(Error("goalWeightKg", "Цель по весу: разумно 30–300 кг"))
        }
    }
}

/** Разбор числа из русского ввода: «74,5» и «74.5» одинаково допустимы. Пусто = null. */
fun parseOptionalDouble(raw: String): Double? =
    raw.trim().replace(',', '.').toDoubleOrNull()

fun parseOptionalInt(raw: String): Int? =
    raw.trim().toIntOrNull()
