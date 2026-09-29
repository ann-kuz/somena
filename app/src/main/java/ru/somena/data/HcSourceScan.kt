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
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass
import ru.somena.core.HcSource
import ru.somena.core.SourceKind

/**
 * Скан Источников Health Connect: какие приложения писали каждый тип данных
 * за окно. Обнаружение - по факту записей (ADR-0004), поэтому работает с любыми
 * приложениями, а не только с известным каталогом. Имя - ярлык приложения из
 * системы; для удалённого приложения остаётся пакет.
 */
object HcSourceScan {

    suspend fun scan(context: Context, days: Long = 30L): Map<SourceKind, List<HcSource>> {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return emptyMap()
        }
        val client = HealthConnectClient.getOrCreate(context)
        val range = TimeRangeFilter.between(Instant.now().minus(Duration.ofDays(days)), Instant.now())

        fun label(pkg: String): String = runCatching {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)

        suspend fun <T : androidx.health.connect.client.records.Record> writers(
            kind: SourceKind,
            type: KClass<T>,
        ): Pair<SourceKind, List<HcSource>> = kind to
            client.readRecords(ReadRecordsRequest(type, range)).records
                .groupBy { it.metadata.dataOrigin.packageName }
                .map { (pkg, _) -> HcSource(pkg, label(pkg)) }

        return mapOf(
            writers(SourceKind.STEPS, StepsRecord::class),
            writers(SourceKind.SLEEP, SleepSessionRecord::class),
            writers(SourceKind.BURN, TotalCaloriesBurnedRecord::class),
            writers(SourceKind.FOOD, NutritionRecord::class),
            writers(SourceKind.WEIGHT, WeightRecord::class),
            writers(SourceKind.BODY_FAT, BodyFatRecord::class),
            writers(SourceKind.BONE, BoneMassRecord::class),
            writers(SourceKind.BMR, BasalMetabolicRateRecord::class),
        )
    }
}
