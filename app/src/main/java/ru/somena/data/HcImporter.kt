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
import androidx.health.connect.client.records.metadata.DataOrigin
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
import ru.somena.core.SleepEntry
import ru.somena.core.SourceGroup
import ru.somena.core.SourceKind
import ru.somena.core.StepEntry

/**
 * Читает записи Health Connect за окно и пересчитывает Дневные срезы.
 * Окно: неделя долечивания (ImportWindow) - дозапись задним числом в любую неделю
 * подхватывается при следующем импорте; после долгого перерыва - весь непрочитанный
 * промежуток. Пересчитанный день сливается с сохранённым (свежие поля сильнее),
 * поэтому дубликатов не бывает.
 *
 * Выбор Источника (ADR-0010): если Пользователь выбрал Источник для группы типов,
 * чтение этого типа фильтруется по пакету; без выбора читаются все Источники.
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
        val choices = SourceStore(context).load()

        fun origins(kind: SourceKind): Set<DataOrigin> =
            choices[SourceGroup.byKind(kind).key]?.let { setOf(DataOrigin(it)) } ?: emptySet()

        val steps = client.readRecords(
            ReadRecordsRequest(StepsRecord::class, range, dataOriginFilter = origins(SourceKind.STEPS)),
        ).records.map {
            StepEntry(it.startTime, it.endTime, it.count, it.metadata.dataOrigin.packageName)
        }
        val sleep = client.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, range, dataOriginFilter = origins(SourceKind.SLEEP)),
        ).records.map {
            SleepEntry(it.startTime, it.endTime)
        }
        val burn = client.readRecords(
            ReadRecordsRequest(TotalCaloriesBurnedRecord::class, range, dataOriginFilter = origins(SourceKind.BURN)),
        ).records.map {
            BurnEntry(it.startTime, it.endTime, it.energy.inKilocalories)
        }
        val meals = client.readRecords(
            ReadRecordsRequest(NutritionRecord::class, range, dataOriginFilter = origins(SourceKind.FOOD)),
        ).records.map {
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
        val weights = client.readRecords(
            ReadRecordsRequest(WeightRecord::class, range, dataOriginFilter = origins(SourceKind.WEIGHT)),
        ).records
        val fats = client.readRecords(
            ReadRecordsRequest(BodyFatRecord::class, range, dataOriginFilter = origins(SourceKind.BODY_FAT)),
        ).records
        val bones = client.readRecords(
            ReadRecordsRequest(BoneMassRecord::class, range, dataOriginFilter = origins(SourceKind.BONE)),
        ).records
        val bmr = client.readRecords(
            ReadRecordsRequest(BasalMetabolicRateRecord::class, range, dataOriginFilter = origins(SourceKind.BMR)),
        ).records
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
            val merged = (db.get(day) ?: DailyAggregator.buildSlice(day, zone)).mergeFresh(fresh)
            db.upsert(merged)
            imported++
        }
        return imported
    }
}
