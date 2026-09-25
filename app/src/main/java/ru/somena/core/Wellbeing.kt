package ru.somena.core

import java.time.LocalDate

/**
 * Самочувствие (тикет 05): три шкалы 1–5 и заметка по желанию.
 * Локальные данные приложения, в Health Connect не пишутся.
 */
data class Wellbeing(
    val date: LocalDate,
    val energy: Int,
    val mood: Int,
    val sleepQuality: Int,
    val note: String? = null,
) {
    companion object {
        const val MIN = 1
        const val MAX = 5

        fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
    }
}
