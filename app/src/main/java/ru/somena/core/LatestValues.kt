package ru.somena.core

import java.time.LocalDate

/**
 * Последние известные значения метрик (экран «Сегодня», чистая логика без
 * Android): каждая метрика берётся из самого позднего Дневного среза, где она
 * есть, вместе с этим днём. Свежесть - «получено сегодня» - решает экран,
 * сравнив день с текущим.
 */
data class MetricLatest<T>(val value: T, val on: LocalDate)

data class LatestValues(
    val steps: MetricLatest<Long>? = null,
    val sleepMinutes: MetricLatest<Long>? = null,
    val burnedKcal: MetricLatest<Double>? = null,
    val eatenKcal: MetricLatest<Double>? = null,
    val proteinG: MetricLatest<Double>? = null,
    val fatG: MetricLatest<Double>? = null,
    val carbsG: MetricLatest<Double>? = null,
    val weightKg: MetricLatest<Double>? = null,
    val bodyFatPct: MetricLatest<Double>? = null,
    val boneMassKg: MetricLatest<Double>? = null,
    val bmrKcal: MetricLatest<Double>? = null,
) {
    /** Данных нет вовсе: ни одна метрика нигде не встречалась. */
    val isEmpty: Boolean get() = steps == null && sleepMinutes == null && burnedKcal == null &&
        eatenKcal == null && proteinG == null && fatG == null && carbsG == null &&
        weightKg == null && bodyFatPct == null && boneMassKg == null && bmrKcal == null
}

/**
 * Последние известные значения по всем срезам: у каждой метрики свой самый
 * поздний день, порядок срезов в списке не важен.
 */
fun latestValues(all: List<DaySlice>): LatestValues {
    var out = LatestValues()
    for (s in all.sortedByDescending { it.date }) {
        out = out.copy(
            steps = out.steps ?: s.steps?.let { MetricLatest(it, s.date) },
            sleepMinutes = out.sleepMinutes ?: s.sleepMinutes?.let { MetricLatest(it, s.date) },
            burnedKcal = out.burnedKcal ?: s.burnedKcal?.let { MetricLatest(it, s.date) },
            eatenKcal = out.eatenKcal ?: s.eatenKcal?.let { MetricLatest(it, s.date) },
            proteinG = out.proteinG ?: s.proteinG?.let { MetricLatest(it, s.date) },
            fatG = out.fatG ?: s.fatG?.let { MetricLatest(it, s.date) },
            carbsG = out.carbsG ?: s.carbsG?.let { MetricLatest(it, s.date) },
            weightKg = out.weightKg ?: s.weightKg?.let { MetricLatest(it, s.date) },
            bodyFatPct = out.bodyFatPct ?: s.bodyFatPct?.let { MetricLatest(it, s.date) },
            boneMassKg = out.boneMassKg ?: s.boneMassKg?.let { MetricLatest(it, s.date) },
            bmrKcal = out.bmrKcal ?: s.bmrKcal?.let { MetricLatest(it, s.date) },
        )
    }
    return out
}
