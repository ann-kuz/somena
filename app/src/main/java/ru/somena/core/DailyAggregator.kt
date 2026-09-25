package ru.somena.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Чистая логика Дневного среза: без Android-зависимостей, покрывается автотестами на сервере.
 * Политики см. ADR-0001 (шаги только с браслета) и спеку 0001 (день без данных ≠ ноль).
 */

/** Записи, приходящие из любого источника; time-поля — в UTC. */
data class StepEntry(val start: Instant, val end: Instant, val count: Long, val source: String)
data class SleepEntry(val start: Instant, val end: Instant)
data class BurnEntry(val start: Instant, val end: Instant, val kcal: Double)
data class MealEntry(
    val start: Instant,
    val end: Instant,
    val kcal: Double?,
    val proteinG: Double?,
    val fatG: Double?,
    val carbsG: Double?,
)
data class BodyEntry(
    val time: Instant,
    val weightKg: Double?,
    val bodyFatPct: Double?,
    val boneMassKg: Double?,
    val bmrKcalPerDay: Double?,
)

data class DaySlice(
    val date: LocalDate,
    val steps: Long? = null,
    val sleepMinutes: Long? = null,
    val burnedKcal: Double? = null,
    val eatenKcal: Double? = null,
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val carbsG: Double? = null,
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val boneMassKg: Double? = null,
    val bmrKcal: Double? = null,
) {
    fun mergeFresh(fresh: DaySlice): DaySlice = DaySlice(
        date = date,
        steps = fresh.steps ?: steps,
        sleepMinutes = fresh.sleepMinutes ?: sleepMinutes,
        burnedKcal = fresh.burnedKcal ?: burnedKcal,
        eatenKcal = fresh.eatenKcal ?: eatenKcal,
        proteinG = fresh.proteinG ?: proteinG,
        fatG = fresh.fatG ?: fatG,
        carbsG = fresh.carbsG ?: carbsG,
        weightKg = fresh.weightKg ?: weightKg,
        bodyFatPct = fresh.bodyFatPct ?: bodyFatPct,
        boneMassKg = fresh.boneMassKg ?: boneMassKg,
        bmrKcal = fresh.bmrKcal ?: bmrKcal,
    )
}

object DailyAggregator {

    /** Политика шагов: верим только браслету (ADR-0001). */
    const val BAND_SOURCE = "com.xiaomi.wear"

    fun buildSlice(
        date: LocalDate,
        zone: ZoneId,
        steps: List<StepEntry> = emptyList(),
        sleep: List<SleepEntry> = emptyList(),
        burn: List<BurnEntry> = emptyList(),
        meals: List<MealEntry> = emptyList(),
        body: List<BodyEntry> = emptyList(),
    ): DaySlice {
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()

        fun inDay(start: Instant) = start >= dayStart && start < dayEnd

        // Запись принадлежит дню своего начала: интервал через полночь не считается дважды.
        val bandSteps = dedupeSteps(steps.filter { it.source == BAND_SOURCE && inDay(it.start) })
        val daySleep = sleep.filter { inDay(it.start) }
        val dayBurn = burn.filter { inDay(it.start) }
        val dayMeals = meals.filter { inDay(it.start) }
        val dayBody = body.filter { inDay(it.time) }

        return DaySlice(
            date = date,
            steps = bandSteps.sumOf { it.count }.takeIf { bandSteps.isNotEmpty() },
            sleepMinutes = daySleep.sumOf { it.durationMinutes }.takeIf { daySleep.isNotEmpty() },
            burnedKcal = dayBurn.sumOf { it.kcal }.takeIf { dayBurn.isNotEmpty() },
            eatenKcal = dayMeals.sumOrNull { it.kcal },
            proteinG = dayMeals.sumOrNull { it.proteinG },
            fatG = dayMeals.sumOrNull { it.fatG },
            carbsG = dayMeals.sumOrNull { it.carbsG },
            // По каждому показателю тела — последнее непустое значение дня.
            weightKg = dayBody.lastNonNull { it.weightKg },
            bodyFatPct = dayBody.lastNonNull { it.bodyFatPct },
            boneMassKg = dayBody.lastNonNull { it.boneMassKg },
            bmrKcal = dayBody.lastNonNull { it.bmrKcalPerDay },
        )
    }

    /**
     * Схлопывает пересекающиеся записи шагов одного источника (ADR-0001: дедупликация —
     * наша работа): интервалы объединяются, шаги из пересечения считаются один раз
     * с пропорцией по длительности.
     */
    fun dedupeSteps(entries: List<StepEntry>): List<StepEntry> {
        val out = mutableListOf<StepEntry>()
        for (e in entries.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last == null || !e.start.isBefore(last.end)) {
                out.add(e)
                continue
            }
            var newEnd = last.end
            var newCount = last.count.toDouble()
            if (e.end.isAfter(last.end)) {
                val eDur = java.time.Duration.between(e.start, e.end).toMillis().coerceAtLeast(1)
                val tail = java.time.Duration.between(maxOf(e.start, last.end), e.end).toMillis()
                newCount += e.count.toDouble() * tail / eDur
                newEnd = e.end
            }
            out[out.size - 1] = last.copy(count = newCount.toLong(), end = newEnd)
        }
        return out
    }

    private fun <T> List<T>.sumOrNull(selector: (T) -> Double?): Double? {
        var any = false
        var sum = 0.0
        for (x in this) selector(x)?.let { any = true; sum += it }
        return sum.takeIf { any }
    }

    private fun <T> List<T>.lastNonNull(selector: (T) -> Double?): Double? {
        for (x in reversed()) selector(x)?.let { return it }
        return null
    }

    private val SleepEntry.durationMinutes: Long
        get() = java.time.Duration.between(start, end).toMinutes()
}
