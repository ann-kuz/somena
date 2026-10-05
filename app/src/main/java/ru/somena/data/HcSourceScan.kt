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
 * за окно и когда писали последний раз - свежесть показывается у Источника
 * на экране показателя (спека 0018), по ней видно, кто «живой». Обнаружение -
 * по факту записей (ADR-0004), поэтому работает с любыми приложениями, а не
 * только с известным каталогом. Имя - ярлык приложения из системы; для
 * удалённого приложения остаётся пакет. Расход сканирует оба типа калорий:
 * общие и активные пишут разные приложения.
 *
 * Каждый тип читается отдельно и стойко к отказу: не выданное разрешение или
 * сбой одного типа не роняет скан целиком - тип остаётся без писавших, а отказ
 * уходит в Журнал. Так после обновления с новым типом данных скан показывает
 * остальное и подсказывает выдать разрешения, а не гаснет весь экран.
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

        // endTime доступен у каждого типа записи, но не в общем интерфейсе Record,
        // поэтому конец записи передаётся селектором.
        suspend fun <T : androidx.health.connect.client.records.Record> writers(
            type: KClass<T>,
            endOf: (T) -> Instant,
        ): List<HcSource> = runCatching {
            client.readRecords(ReadRecordsRequest(type, range)).records
                .groupBy { it.metadata.dataOrigin.packageName }
                .map { (pkg, records) ->
                    HcSource(pkg, label(pkg), records.maxOf(endOf))
                }
        }.getOrElse { e ->
            AppLog.append(context, AppLog.HC, "скан ${type.simpleName}: ${e.message}")
            emptyList()
        }

        fun named(sources: List<HcSource>): List<HcSource> =
            sources.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))

        // Расход: общий и активный калории - один список, у писавшего оба типа
        // остаётся более поздняя запись.
        val burn = (writers(TotalCaloriesBurnedRecord::class) { it.endTime } + writers(ActiveCaloriesBurnedRecord::class) { it.endTime })
            .groupBy { it.packageName }
            .map { (pkg, same) -> HcSource(pkg, same.first().label, same.mapNotNull { it.lastWrite }.maxOrNull()) }

        return mapOf(
            SourceKind.STEPS to named(writers(StepsRecord::class) { it.endTime }),
            SourceKind.SLEEP to named(writers(SleepSessionRecord::class) { it.endTime }),
            SourceKind.BURN to named(burn),
            SourceKind.PULSE to named(writers(HeartRateRecord::class) { it.endTime }),
            SourceKind.FOOD to named(writers(NutritionRecord::class) { it.endTime }),
            // Вес и состав тела - мгновенные записи, у них есть только момент времени.
            SourceKind.WEIGHT to named(writers(WeightRecord::class) { it.time }),
            SourceKind.BODY_FAT to named(writers(BodyFatRecord::class) { it.time }),
            SourceKind.BONE to named(writers(BoneMassRecord::class) { it.time }),
            SourceKind.BMR to named(writers(BasalMetabolicRateRecord::class) { it.time }),
        )
    }
}
