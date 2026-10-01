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
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass
import ru.somena.core.HcSource
import ru.somena.core.SourceKind

/**
 * Скан Источников Health Connect: какие приложения писали каждый тип данных
 * за окно. Обнаружение - по факту записей (ADR-0004), поэтому работает с любыми
 * приложениями, а не только с известным каталогом. Имя - ярлык приложения из
 * системы; для удалённого приложения остаётся пакет. Расход сканирует оба типа
 * калорий: общие и активные пишут разные приложения.
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
            type: KClass<T>,
        ): List<String> =
            client.readRecords(ReadRecordsRequest(type, range)).records
                .groupBy { it.metadata.dataOrigin.packageName }
                .map { (pkg, _) -> pkg }

        fun named(pkgs: List<String>): List<HcSource> =
            pkgs.map { HcSource(it, label(it)) }.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))

        return mapOf(
            SourceKind.STEPS to named(writers(StepsRecord::class)),
            SourceKind.SLEEP to named(writers(SleepSessionRecord::class)),
            SourceKind.BURN to named((writers(TotalCaloriesBurnedRecord::class) + writers(ActiveCaloriesBurnedRecord::class)).distinct()),
            SourceKind.PULSE to named(writers(HeartRateRecord::class)),
            SourceKind.FOOD to named(writers(NutritionRecord::class)),
            SourceKind.WEIGHT to named(writers(WeightRecord::class)),
            SourceKind.BODY_FAT to named(writers(BodyFatRecord::class)),
            SourceKind.BONE to named(writers(BoneMassRecord::class)),
            SourceKind.BMR to named(writers(BasalMetabolicRateRecord::class)),
        )
    }
}
