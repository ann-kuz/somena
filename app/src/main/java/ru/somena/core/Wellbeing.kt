package ru.somena.core

import java.time.LocalDate

/**
 * Самочувствие (тикет 05): три шкалы 0–10 и заметка по желанию.
 * Локальные данные приложения, в Health Connect не пишутся.
 * Старые записи со шкалой 1–5 переносятся на новую шкалу удвоением (миграция БД v3).
 */
data class Wellbeing(
    val date: LocalDate,
    val energy: Int,
    val mood: Int,
    val sleepQuality: Int,
    val note: String? = null,
) {
    companion object {
        const val MIN = 0
        const val MAX = 10

        fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
    }
}
