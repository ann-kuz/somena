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
    val steps: Long?,
    val sleepMinutes: Long?,
    val burnedKcal: Double?,
    val eatenKcal: Double?,
    val proteinG: Double?,
    val fatG: Double?,
    val carbsG: Double?,
    val weightKg: Double?,
    val bodyFatPct: Double?,
    val boneMassKg: Double?,
    val bmrKcal: Double?,
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
        val bandSteps = steps.filter { it.source == BAND_SOURCE && inDay(it.start) }
        val daySleep = sleep.filter { inDay(it.start) }
        val dayBurn = burn.filter { inDay(it.start) }
        val dayMeals = meals.filter { inDay(it.start) }
        val dayBody = body.filter { inDay(it.time) }

        return DaySlice(
            date = date,
            steps = bandSteps.sumOf { it.count }.takeIf { bandSteps.isNotEmpty() },
            sleepMinutes = daySleep.sumOf { it.durationMinutes }.takeIf { daySleep.isNotEmpty() },
            burnedKcal = dayBurn.sumOf { it.kcal }.takeIf { dayBurn.isNotEmpty() },
            eatenKcal = dayMeals.mapNotNull { it.kcal }.sum().takeIf { dayMeals.any { it.kcal != null } },
            proteinG = dayMeals.mapNotNull { it.proteinG }.sum().takeIf { dayMeals.any { it.proteinG != null } },
            fatG = dayMeals.mapNotNull { it.fatG }.sum().takeIf { dayMeals.any { it.fatG != null } },
            carbsG = dayMeals.mapNotNull { it.carbsG }.sum().takeIf { dayMeals.any { it.carbsG != null } },
            // По каждому показателю тела — последнее непустое значение дня.
            weightKg = dayBody.map { it.weightKg }.lastOrNull { it != null },
            bodyFatPct = dayBody.map { it.bodyFatPct }.lastOrNull { it != null },
            boneMassKg = dayBody.map { it.boneMassKg }.lastOrNull { it != null },
            bmrKcal = dayBody.map { it.bmrKcalPerDay }.lastOrNull { it != null },
        )
    }

    private val SleepEntry.durationMinutes: Long
        get() = java.time.Duration.between(start, end).toMinutes()
}
