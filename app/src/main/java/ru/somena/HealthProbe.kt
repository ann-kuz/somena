package ru.somena

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Отладочный зонд Health Connect: читает записи за N дней и показывает,
 * какие приложения (dataOrigin) их написали. Не production-код, живёт до первого релиза.
 */
object HealthProbe {

    private val fmt =
        DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
    private val locale = Locale("ru", "RU")

    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun statusText(context: Context): String = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> "Health Connect установлен ✓"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            "Health Connect установлен, но требует обновления: обнови его в Google Play"
        else -> "Health Connect не установлен"
    }

    suspend fun grantedPermissions(context: Context): Set<String> {
        if (!isAvailable(context)) return emptySet()
        val client = HealthConnectClient.getOrCreate(context)
        return client.permissionController.getGrantedPermissions()
    }

    suspend fun probe(context: Context, days: Long = 7L): String {
        val client = HealthConnectClient.getOrCreate(context)
        val now = Instant.now()
        val from = now.minus(Duration.ofDays(days))
        val range = TimeRangeFilter.between(from, now)
        val sb = StringBuilder()

        // --- Шаги: группируем по источнику, порядок приоритета помечаем ---
        val steps = client.readRecords(ReadRecordsRequest(StepsRecord::class, range)).records
        val stepsPriority = ru.somena.data.SourceStore(context).priorityOf(ru.somena.core.SourceGroup.ACTIVITY)
        sb.appendLine("ШАГИ за $days дн.:")
        if (steps.isEmpty()) sb.appendLine("  нет записей")
        steps.groupBy { it.metadata.dataOrigin.packageName }
            .map { (pkg, rs) -> pkg to rs.sumOf { it.count } }
            .sortedByDescending { it.second }
            .forEach { (pkg, cnt) ->
                val rank = stepsPriority.indexOf(pkg)
                val mark = if (rank == 0) " ← первый в приоритете" else if (rank > 0) " ← №${rank + 1} в приоритете" else ""
                sb.appendLine("  $pkg: ${fmtInt(cnt)}$mark")
            }
        sb.appendLine()

        // --- Сон ---
        val sleep = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records
        sb.append("СОН за $days дн.: ")
        if (sleep.isEmpty()) {
            sb.appendLine("нет записей")
        } else {
            val hours = sleep.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() } / 60.0
            sb.appendLine("${fmt1(hours)} ч, сессий: ${sleep.size}")
            sleep.map { it.metadata.dataOrigin.packageName }.distinct()
                .forEach { sb.appendLine("  источник: $it") }
        }
        sb.appendLine()

        // --- Пульс ---
        val hr = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records
        sb.append("ПУЛЬС за $days дн.: ")
        val beats = hr.flatMap { it.samples }.map { it.beatsPerMinute }
        if (beats.isEmpty()) {
            sb.appendLine("нет измерений")
        } else {
            sb.appendLine("мин ${beats.min()}, сред ${beats.sum() / beats.size}, макс ${beats.max()} (изм.: ${beats.size})")
            hr.map { it.metadata.dataOrigin.packageName }.distinct()
                .forEach { sb.appendLine("  источник: $it") }
        }
        sb.appendLine()

        // --- Расход калорий: общие и активные (запасной вариант) ---
        val kcal = client.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, range)).records
        sb.append("РАСХОД (общие) за $days дн.: ")
        sb.appendLine(
            if (kcal.isEmpty()) "нет записей"
            else "${fmtInt(kcal.sumOf { it.energy.inKilocalories })} ккал"
        )
        kcal.map { it.metadata.dataOrigin.packageName }.distinct()
            .forEach { sb.appendLine("  источник: $it") }
        val activeKcal = client.readRecords(
            ReadRecordsRequest(androidx.health.connect.client.records.ActiveCaloriesBurnedRecord::class, range)
        ).records
        sb.append("РАСХОД (активные) за $days дн.: ")
        sb.appendLine(
            if (activeKcal.isEmpty()) "нет записей"
            else "${fmtInt(activeKcal.sumOf { it.energy.inKilocalories })} ккал"
        )
        activeKcal.map { it.metadata.dataOrigin.packageName }.distinct()
            .forEach { sb.appendLine("  источник: $it") }
        sb.appendLine()

        // --- Последнее взвешивание и состав тела ---
        val weights = client.readRecords(ReadRecordsRequest(WeightRecord::class, range)).records
        val fat = client.readRecords(ReadRecordsRequest(BodyFatRecord::class, range)).records
        val bone = client.readRecords(ReadRecordsRequest(BoneMassRecord::class, range)).records
        val bmr = client.readRecords(ReadRecordsRequest(BasalMetabolicRateRecord::class, range)).records
        sb.appendLine("ТЕЛО (последние записи за $days дн.):")
        fun latestLine(label: String, time: Instant?, value: String, pkg: String?) {
            val t = time?.let { fmt.format(it) } ?: "—"
            sb.appendLine("  $label: $value (${t})${pkg?.let { " [$it]" } ?: ""}")
        }
        weights.maxByOrNull { it.time }?.let {
            latestLine("Вес", it.time, "${fmt1(it.weight.inKilograms)} кг", it.metadata.dataOrigin.packageName)
        } ?: sb.appendLine("  Вес: нет записей")
        fat.maxByOrNull { it.time }?.let {
            latestLine("Процент жира", it.time, "${fmt1(it.percentage.value)} %", it.metadata.dataOrigin.packageName)
        } ?: sb.appendLine("  Процент жира: нет записей")
        bone.maxByOrNull { it.time }?.let {
            latestLine("Костная масса", it.time, "${fmt1(it.mass.inKilograms)} кг", it.metadata.dataOrigin.packageName)
        } ?: sb.appendLine("  Костная масса: нет записей")
        bmr.maxByOrNull { it.time }?.let {
            latestLine("Базовый расход", it.time, "${fmtInt(it.basalMetabolicRate.inKilocaloriesPerDay)} ккал/дн", it.metadata.dataOrigin.packageName)
        } ?: sb.appendLine("  Базовый расход: нет записей")
        sb.appendLine()

        // --- Еда: важно видеть, кто её пишет в HC (FatSecret не пишет — кто тогда?) ---
        val food = client.readRecords(ReadRecordsRequest(NutritionRecord::class, range)).records
        sb.append("ЕДА (Health Connect): ")
        if (food.isEmpty()) {
            sb.appendLine("нет записей")
        } else {
            val eaten = food.sumOf { it.energy?.inKilocalories ?: 0.0 }
            sb.appendLine("${fmtInt(eaten)} ккал за $days дн., записей: ${food.size}")
            food.groupBy { it.metadata.dataOrigin.packageName }.forEach { (pkg, rs) ->
                val kcal = rs.sumOf { it.energy?.inKilocalories ?: 0.0 }
                sb.appendLine("  источник: $pkg, ${fmtInt(kcal)} ккал (${rs.size} записей)")
            }
        }

        return sb.toString().trim()
    }

    private fun fmtInt(x: Number): String = String.format(locale, "%,.0f", x.toDouble())
    private fun fmt1(x: Double): String = String.format(locale, "%.1f", x)
}
