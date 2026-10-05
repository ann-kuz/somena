package ru.somena.core

/**
 * Порог съеденного (спека 0019), Настройки → Данные: день, где «Съедено» меньше
 * порога, считается днём без еды - неполный дневник еды не рвёт графики пиками
 * почти-голодных дней. Фильтр живёт при показе (как Расход от шагов): записи
 * базы и Health Connect не стираются, смена порога действует сразу, день на
 * пороге и выше остаётся как записан. Порог 0 и меньше - выключено.
 *
 * Калории и БЖУ - одна запись еды, скрываются вместе. День без калорий, но
 * с БЖУ (такое приносит Разбор таблицы) тоже ниже порога. Дефицит такого дня
 * пуст сам собой: он считается от «Съедено», день без еды дефицита не даёт.
 */
fun List<DaySlice>.withEatenFloor(minKcal: Double): List<DaySlice> {
    if (minKcal <= 0.0) return this
    return map { s ->
        val hasFood = s.eatenKcal != null || s.proteinG != null ||
            s.fatG != null || s.carbsG != null
        if (hasFood && (s.eatenKcal ?: 0.0) < minKcal) {
            s.copy(eatenKcal = null, proteinG = null, fatG = null, carbsG = null)
        } else {
            s
        }
    }
}
