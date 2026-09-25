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
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Онбординг Источников (тикет 02): статус каждого Источника вычисляется по факту записей
 * в Health Connect за последние дни, а не по документации вендора (ADR-0004).
 */

data class SourceSpec(
    val id: String,
    val name: String,
    /** Что пользователь должен включить, чтобы Источник начал писать. */
    val instructions: String,
)

data class SourceStatus(
    val spec: SourceSpec,
    val writing: Boolean,
    /** Время последней записи из этого Источника в окне проверки. */
    val lastWrite: Instant?,
    /** Что именно найдено, для прозрачности. */
    val foundTypes: List<String>,
)

object SourceCatalog {
    val MI_FITNESS = SourceSpec(
        id = "com.xiaomi.wear",
        name = "Mi Fitness (браслет)",
        instructions = "Mi Fitness → Профиль → Настройки → Health Connect: включить шаги, пульс и сон, затем разрешить доступ в самом Health Connect",
    )
    val FITDAYS = SourceSpec(
        id = "cn.fitdays.fitdays",
        name = "Fitdays (весы)",
        instructions = "Fitdays → Настройки → включить синхронизацию с Health Connect",
    )
    val FATSECRET = SourceSpec(
        id = "com.fatsecret.android",
        name = "FatSecret (еда)",
        instructions = "FatSecret → Настройки → Подключения → включить Health Connect",
    )
    val ALL = listOf(MI_FITNESS, FITDAYS, FATSECRET)
}

object OnboardingChecker {

    private val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

    fun formatLastWrite(status: SourceStatus): String =
        status.lastWrite?.let { fmt.format(it) } ?: ""

    /** Проверка по факту записей за последние [days] дней для всех Источников. */
    suspend fun sourceStatuses(context: Context, days: Long = 7L): List<SourceStatus> {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return SourceCatalog.ALL.map { SourceStatus(it, writing = false, lastWrite = null, foundTypes = emptyList()) }
        }
        val client = HealthConnectClient.getOrCreate(context)
        val now = Instant.now()
        val range = TimeRangeFilter.between(now.minus(Duration.ofDays(days)), now)

        // Для каждого Источника — какие типы записей реально появились в окне.
        val found = mutableMapOf<String, MutableSet<String>>()
        val last = mutableMapOf<String, Instant>()

        suspend fun <T : androidx.health.connect.client.records.Record> absorb(
            label: String,
            timeOf: (T) -> Instant,
            request: ReadRecordsRequest<T>,
        ) {
            for (rec in client.readRecords(request).records) {
                val pkg = rec.metadata.dataOrigin.packageName
                if (SourceCatalog.ALL.any { it.id == pkg }) {
                    found.getOrPut(pkg) { mutableSetOf() }.add(label)
                    last[pkg] = maxOf(last[pkg] ?: timeOf(rec), timeOf(rec))
                }
            }
        }

        absorb<StepsRecord>("шаги", { it.startTime }, ReadRecordsRequest(StepsRecord::class, range))
        absorb<SleepSessionRecord>("сон", { it.startTime }, ReadRecordsRequest(SleepSessionRecord::class, range))
        absorb<HeartRateRecord>("пульс", { it.startTime }, ReadRecordsRequest(HeartRateRecord::class, range))
        absorb<NutritionRecord>("еда", { it.startTime }, ReadRecordsRequest(NutritionRecord::class, range))
        absorb<WeightRecord>("вес", { it.time }, ReadRecordsRequest(WeightRecord::class, range))
        absorb<BodyFatRecord>("жир", { it.time }, ReadRecordsRequest(BodyFatRecord::class, range))
        absorb<BoneMassRecord>("кости", { it.time }, ReadRecordsRequest(BoneMassRecord::class, range))
        absorb<BasalMetabolicRateRecord>("обмен", { it.time }, ReadRecordsRequest(BasalMetabolicRateRecord::class, range))

        return SourceCatalog.ALL.map { spec ->
            SourceStatus(
                spec = spec,
                writing = found[spec.id]?.isNotEmpty() == true,
                lastWrite = last[spec.id],
                foundTypes = found[spec.id]?.sorted().orEmpty(),
            )
        }
    }
}

fun onboardingCompleted(context: Context): Boolean =
    context.getSharedPreferences("somena", Context.MODE_PRIVATE).getBoolean("onboarding_done", false)

fun setOnboardingCompleted(context: Context) {
    context.getSharedPreferences("somena", Context.MODE_PRIVATE)
        .edit().putBoolean("onboarding_done", true).apply()
}
