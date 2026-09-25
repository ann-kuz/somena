package ru.somena.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import ru.somena.core.BurnEntry
import ru.somena.core.BodyEntry
import ru.somena.core.DaySlice
import ru.somena.core.DailyAggregator
import ru.somena.core.MealEntry
import ru.somena.core.SleepEntry
import ru.somena.core.StepEntry

/**
 * Читает записи Health Connect за окно и пересчитывает Дневные срезы.
 * Пересчитанный день сливается с уже сохранённым (свежие поля сильнее) —
 * поэтому повторный импорт не создаёт дубликаты и не затирает дни вне окна.
 */
class HcImporter(
    private val db: SliceDb,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun importRecent(context: Context, windowDays: Long = 14L): ImportStats {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return ImportStats(importedDays = 0, hcAvailable = false)
        }
        val client = HealthConnectClient.getOrCreate(context)
        val now = Instant.now()
        val from = db.lastStoredDate()
        // Окно: от последнего сохранённого дня (полностью) до сих пор; в первый раз — N дней.
        val windowStart = (from?.plusDays(1)?.atStartOfDay(zone)?.toInstant())
            ?: now.minus(java.time.Duration.ofDays(windowDays))
        val range = TimeRangeFilter.between(windowStart, now)

        val steps = client.readRecords(ReadRecordsRequest(StepsRecord::class, range)).records.map {
            StepEntry(it.startTime, it.endTime, it.count, it.metadata.dataOrigin.packageName)
        }
        val sleep = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records.map {
            SleepEntry(it.startTime, it.endTime)
        }
        val burn = client.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, range)).records.map {
            BurnEntry(it.startTime, it.endTime, it.energy.inKilocalories)
        }
        val meals = client.readRecords(ReadRecordsRequest(NutritionRecord::class, range)).records.map {
            MealEntry(
                start = it.startTime,
                end = it.endTime,
                kcal = it.energy?.inKilocalories,
                proteinG = it.protein?.inGrams,
                fatG = it.totalFat?.inGrams,
                carbsG = it.totalCarbohydrate?.inGrams,
            )
        }
        // Показатели тела собираем в единые точки взвешивания по времени.
        val weights = client.readRecords(ReadRecordsRequest(WeightRecord::class, range)).records
        val fats = client.readRecords(ReadRecordsRequest(BodyFatRecord::class, range)).records
        val bones = client.readRecords(ReadRecordsRequest(BoneMassRecord::class, range)).records
        val bmr = client.readRecords(ReadRecordsRequest(BasalMetabolicRateRecord::class, range)).records
        val bodyTimes = (weights.map { it.time } + fats.map { it.time } + bones.map { it.time } + bmr.map { it.time }).distinct()
        val body = bodyTimes.map { t ->
            BodyEntry(
                time = t,
                weightKg = weights.lastOrNull { it.time == t }?.weight?.inKilograms,
                bodyFatPct = fats.lastOrNull { it.time == t }?.percentage?.value,
                boneMassKg = bones.lastOrNull { it.time == t }?.mass?.inKilograms,
                bmrKcalPerDay = bmr.lastOrNull { it.time == t }?.basalMetabolicRate?.inKilocaloriesPerDay,
            )
        }

        val affectedDays = (
            steps.map { it.start } + sleep.map { it.start } + burn.map { it.start } +
                meals.map { it.start } + body.map { it.time }
            ).map { it.atZone(zone).toLocalDate() }.distinct()

        var imported = 0
        for (day in affectedDays.sorted()) {
            val fresh = DailyAggregator.buildSlice(day, zone, steps, sleep, burn, meals, body)
            val merged = (db.get(day) ?: freshWithDate(day)).mergeFresh(fresh)
            db.upsert(merged)
            imported++
        }
        return ImportStats(importedDays = imported, hcAvailable = true)
    }

    private fun freshWithDate(day: LocalDate) = DaySlice(day, null, null, null, null, null, null, null, null, null, null, null)
}

data class ImportStats(val importedDays: Int, val hcAvailable: Boolean)
