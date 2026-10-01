package ru.somena.core

import java.time.LocalDate

/**
 * Последние известные значения метрик (экран «Сегодня», чистая логика без
 * Android): каждая метрика берётся из самого позднего Дневного среза, где она
 * есть, вместе с этим днём. Свежесть - «получено сегодня» - решает экран,
 * сравнив день с текущим.
 */
data class MetricLatest<T>(val value: T, val on: LocalDate)

/** Пульс одного дня: среднее и границы, если замеров больше одного. */
data class PulseDay(val avg: Long, val min: Long?, val max: Long?)

data class LatestValues(
    val steps: MetricLatest<Long>? = null,
    val sleepMinutes: MetricLatest<Long>? = null,
    val burnedKcal: MetricLatest<Double>? = null,
    val pulse: MetricLatest<PulseDay>? = null,
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
        pulse == null && eatenKcal == null && proteinG == null && fatG == null && carbsG == null &&
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
            pulse = out.pulse ?: s.pulseAvg?.let {
                MetricLatest(PulseDay(it, s.pulseMin, s.pulseMax), s.date)
            },
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

/**
 * Суточная метрика (Съедено, Сожжено): показатели копятся за день и обнуляются
 * к началу нового. Нет данных за сегодня - значит ноль, вчерашний итог не
 * показывается вовсе.
 */
fun MetricLatest<Double>?.todayOrZero(today: LocalDate): Double =
    if (this != null && on == today) value else 0.0

/** БЖУ сегодняшнего дня: отсутствующий показатель - ноль, а не вчерашнее значение. */
data class TodayMacros(val proteinG: Double, val fatG: Double, val carbsG: Double)

/**
 * Суточное питание «Сегодня» из среза за сегодня: и калории, и БЖУ берутся только
 * из него - вчерашний итог не подтекает ни в цифру, ни в подстрочник. Калорий ноль,
 * если сегодня еды не записано; пока калорий ноль, плашка не считается свежей
 * (правило «светящееся = активное», спека 0002).
 */
data class TodayNutrition(val kcal: Double, val macros: TodayMacros?) {
    val hasEaten: Boolean get() = kcal > 0.0
}

fun DaySlice?.todayNutrition(): TodayNutrition = TodayNutrition(
    kcal = this?.eatenKcal ?: 0.0,
    macros = if (this == null || (proteinG == null && fatG == null && carbsG == null)) {
        null
    } else {
        TodayMacros(proteinG ?: 0.0, fatG ?: 0.0, carbsG ?: 0.0)
    },
)

/** Сожжено сегодня: ноль без сегодняшней записи; ноль - не свежее значение. */
fun DaySlice?.todayBurned(): Double = this?.burnedKcal ?: 0.0
