package ru.somena.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import ru.somena.core.BurnEntry
import ru.somena.core.BodyEntry
import ru.somena.core.DaySlice
import ru.somena.core.DailyAggregator
import ru.somena.core.ImportWindow
import ru.somena.core.MealEntry
import ru.somena.core.PulseEntry
import ru.somena.core.SleepEntry
import ru.somena.core.SourceGroup
import ru.somena.core.SourceKind
import ru.somena.core.StepEntry
import ru.somena.core.applySourcePriority

/**
 * Читает записи Health Connect за окно и пересчитывает Дневные срезы.
 * Окно: неделя долечивания (ImportWindow) - дозапись задним числом в любую неделю
 * подхватывается при следующем импорте; после долгого перерыва - весь непрочитанный
 * промежуток. Пересчитанный день сливается с сохранённым (свежие поля сильнее),
 * поэтому дубликатов не бывает.
 *
 * Приоритет Источников (ADR-0010): записи читаются без фильтра Источника, а потом
 * за каждый день оставляется первый Источник порядка, у которого в этот день есть
 * записи. Расход читается двумя типами: общие калории честнее, активные - запасной
 * вариант на день, когда общих никто не написал.
 */
class HcImporter(
    private val db: SliceDb,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun importRecent(context: Context, windowDays: Long = 14L): Int {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return 0
        }
        val client = HealthConnectClient.getOrCreate(context)
        val now = Instant.now()
        val range = TimeRangeFilter.between(ImportWindow.start(now, zone, db.lastStoredDate(), windowDays), now)
        val priorities = SourceStore(context).load()

        fun priority(kind: SourceKind): List<String> = priorities[SourceGroup.byKind(kind).key].orEmpty()

        fun <T> List<T>.byPriority(kind: SourceKind, sourceOf: (T) -> String, timeOf: (T) -> Instant): List<T> =
            applySourcePriority(this, priority(kind), zone, sourceOf, timeOf)

        val steps = client.readRecords(ReadRecordsRequest(StepsRecord::class, range)).records
            .map { StepEntry(it.startTime, it.endTime, it.count, it.metadata.dataOrigin.packageName) }
            .byPriority(SourceKind.STEPS, { it.source }, { it.start })
        val sleep = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records
            .map { SleepEntry(it.startTime, it.endTime, it.metadata.dataOrigin.packageName) }
            .byPriority(SourceKind.SLEEP, { it.source }, { it.start })
        // Расход: общие калории (active = false) и активные (active = true) одним
        // списком - приоритет выбирает Источник за день, агрегатор вид записи.
        val burn = (
            client.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, range)).records
                .map { BurnEntry(it.startTime, it.endTime, it.energy.inKilocalories, it.metadata.dataOrigin.packageName) } +
                client.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, range)).records
                    .map { BurnEntry(it.startTime, it.endTime, it.energy.inKilocalories, it.metadata.dataOrigin.packageName, active = true) }
            ).byPriority(SourceKind.BURN, { it.source }, { it.start })
        val pulse = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records
            .flatMap { r -> r.samples.map { PulseEntry(it.time, it.beatsPerMinute, r.metadata.dataOrigin.packageName) } }
            .byPriority(SourceKind.PULSE, { it.source }, { it.time })
        val meals = client.readRecords(ReadRecordsRequest(NutritionRecord::class, range)).records
            .map {
                MealEntry(
                    start = it.startTime,
                    end = it.endTime,
                    kcal = it.energy?.inKilocalories,
                    proteinG = it.protein?.inGrams,
                    fatG = it.totalFat?.inGrams,
                    carbsG = it.totalCarbohydrate?.inGrams,
                    source = it.metadata.dataOrigin.packageName,
                )
            }
            .byPriority(SourceKind.FOOD, { it.source }, { it.start })
        // Показатели тела собираем в единые точки взвешивания по времени.
        val weights = client.readRecords(ReadRecordsRequest(WeightRecord::class, range)).records
            .map { BodyEntry(it.time, it.weight.inKilograms, null, null, null, it.metadata.dataOrigin.packageName) }
            .byPriority(SourceKind.WEIGHT, { it.source }, { it.time })
        val fats = client.readRecords(ReadRecordsRequest(BodyFatRecord::class, range)).records
            .map { BodyEntry(it.time, null, it.percentage.value, null, null, it.metadata.dataOrigin.packageName) }
            .byPriority(SourceKind.BODY_FAT, { it.source }, { it.time })
        val bones = client.readRecords(ReadRecordsRequest(BoneMassRecord::class, range)).records
            .map { BodyEntry(it.time, null, null, it.mass.inKilograms, null, it.metadata.dataOrigin.packageName) }
            .byPriority(SourceKind.BONE, { it.source }, { it.time })
        val bmr = client.readRecords(ReadRecordsRequest(BasalMetabolicRateRecord::class, range)).records
            .map {
                BodyEntry(it.time, null, null, null, it.basalMetabolicRate.inKilocaloriesPerDay, it.metadata.dataOrigin.packageName)
            }
            .byPriority(SourceKind.BMR, { it.source }, { it.time })
        val bodyTimes = (weights.map { it.time } + fats.map { it.time } + bones.map { it.time } + bmr.map { it.time }).distinct()
        val body = bodyTimes.map { t ->
            BodyEntry(
                time = t,
                weightKg = weights.lastOrNull { it.time == t }?.weightKg,
                bodyFatPct = fats.lastOrNull { it.time == t }?.bodyFatPct,
                boneMassKg = bones.lastOrNull { it.time == t }?.boneMassKg,
                bmrKcalPerDay = bmr.lastOrNull { it.time == t }?.bmrKcalPerDay,
            )
        }

        val affectedDays = (
            steps.map { it.start } + sleep.map { it.start } + burn.map { it.start } +
                pulse.map { it.time } + meals.map { it.start } + body.map { it.time }
            ).map { it.atZone(zone).toLocalDate() }.distinct()

        var imported = 0
        for (day in affectedDays.sorted()) {
            val fresh = DailyAggregator.buildSlice(day, zone, steps, sleep, burn, pulse, meals, body)
            val merged = (db.get(day) ?: DailyAggregator.buildSlice(day, zone)).mergeFresh(fresh)
            // Считаются только реально записанные дни: переписывание неизменившихся
            // срезов не делает «Обновить» импортом.
            if (db.upsert(merged)) imported++
        }
        return imported
    }
}
