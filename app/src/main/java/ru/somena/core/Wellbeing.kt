package ru.somena.core

import java.time.LocalDate

/**
 * Самочувствие (тикет 05): три шкалы 0–10 и заметка по желанию.
 * Локальные данные приложения, в Health Connect не пишутся.
 * Старые записи со шкалой 1–5 переносятся на новую шкалу удвоением (миграция БД v3).
 * Отметок в день может быть две (слот 1 и слот 2, миграция БД v5): прежние записи
 * считаются первыми.
 */
data class Wellbeing(
    val date: LocalDate,
    val energy: Int,
    val mood: Int,
    val sleepQuality: Int,
    val note: String? = null,
    val slot: Int = SLOT_FIRST,
) {
    companion object {
        const val MIN = 0
        const val MAX = 10
        const val SLOT_FIRST = 1
        const val SLOT_SECOND = 2

        fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
    }
}

/** Значение дня для графиков и контекста Чата: последняя отметка дня, если их две. */
fun List<Wellbeing>.latestOfDay(): Wellbeing? = maxByOrNull { it.slot }
