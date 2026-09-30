package ru.somena.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Разбор таблицы (спеки 0004 и 0006, ADR-0007): чистая логика без Android-зависимостей.
 * Вход - ответ ИИ (строгий JSON), существующие Дневные срезы и отметки Самочувствия;
 * выход - Предпросмотр: какие дни добавятся, какие значения заменят существующие,
 * какие строки не разобрались. Заносятся показатели Дневного среза - вес, состав тела,
 * еда, шаги, расход, сон - и шкалы Самочувствия, задним числом за любой прошедший день.
 * При последующем пересчёте дня из HC показания Источников сильнее табличных (ADR-0006).
 */

/** Рукотворные показатели одного дня, которые можно занести таблицей. */
data class ImportValues(
    val weightKg: Double? = null,
    val eatenKcal: Double? = null,
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val carbsG: Double? = null,
    val bodyFatPct: Double? = null,
    val boneMassKg: Double? = null,
    val bmrKcal: Double? = null,
    val steps: Long? = null,
    val burnedKcal: Double? = null,
    val sleepMinutes: Long? = null,
)

/** Шкалы Самочувствия одного дня, которые можно занести таблицей (целые 0..10). */
data class ImportWellbeing(
    val energy: Int? = null,
    val mood: Int? = null,
    val sleepQuality: Int? = null,
)

/** Один разобранный день и то, что он заменяет (null - поле было пустым). */
data class ImportEntry(
    val date: LocalDate,
    val values: ImportValues,
    val old: ImportValues,
)

/** Одна разобранная отметка Самочувствия и то, что она заменяет (null - шкала была пуста). */
data class ImportWellbeingEntry(
    val date: LocalDate,
    val values: ImportWellbeing,
    val old: ImportWellbeing,
)

data class RejectedRow(val raw: String, val reason: String)

data class ImportPreview(
    val entries: List<ImportEntry> = emptyList(),
    val rejected: List<RejectedRow> = emptyList(),
    val wellbeing: List<ImportWellbeingEntry> = emptyList(),
) {
    // «Замена» = у существующего дня было хоть что-то из занимаемых полей.
    val replacedCount: Int get() = entries.count { e -> e.old.describe().isNotEmpty() }

    val wellbeingReplacedCount: Int get() = wellbeing.count { it.old != ImportWellbeing() }
}

/** Промпт разбора: отдельный от Чата по данным, требует только JSON. */
const val IMPORT_SYSTEM_PROMPT = """Ты - разборщик таблиц приложения здоровья Somena. Пользователь пришлёт текст таблицы: колонки с датами и показателями (вес, еда, состав тела, шаги, расход, сон, самочувствие). Твоя задача - вернуть строгий JSON и вообще никакой другой текст.

Формат ответа:
{"days":[{"date":"ГГГГ-ММ-ДД","weight":62.4,"eaten_kcal":1850,"protein":90,"fat":70,"carbs":180,"body_fat":28.1,"bone":2.6,"steps":12000,"burned_kcal":2100,"sleep_h":7.5}],"wellbeing":[{"date":"ГГГГ-ММ-ДД","energy":7,"mood":6,"sleep_quality":8}],"unparsed":[{"row":"исходная строка","problem":"что не так"}]}

Правила:
- Даты приведи к виду ГГГГ-ММ-ДД. Русская запись 05.01.2025 означает 5 января 2025.
- Пустая ячейка - поле не включай. Ноль не подставляй: пустое место - это пропуск, а не ноль.
- weight - вес в кг; eaten_kcal - съеденные калории за день; protein, fat, carbs - белки, жиры, углеводы в граммах; body_fat - процент жира; bone - костная масса в кг; steps - шаги за день, целое число; burned_kcal - сожжённые калории за день; sleep_h - сон в часах, дробь допустима (7.5 - это 7 часов 30 минут).
- wellbeing - отмеченные самочувствия: energy, mood, sleep_quality - целые от 0 до 10. Дни из wellbeing не дублируй в days.
- Запятую как десятичный разделитель (62,4) меняй на точку (62.4).
- Строки, которые не удалось разобрать или не относятся к показателям (заголовок, итоги, пустые), перечисли в unparsed с причиной.
- Ничего не придумывай: нет данных - нет поля."""

/** Диапазоны значений (спеки 0004 и 0006): за границей строка целиком уходит в отброшенные.
 *  Сон здесь нет: он приходит часами и проверяется своим диапазоном 0..24 в dayProblem. */
private val VALUE_RANGES = listOf(
    "weight" to (25.0..350.0),
    "eaten_kcal" to (0.0..15000.0),
    "protein" to (0.0..2000.0),
    "fat" to (0.0..2000.0),
    "carbs" to (0.0..2000.0),
    "body_fat" to (1.0..70.0),
    "bone" to (0.5..10.0),
    "steps" to (0.0..100000.0),
    "burned_kcal" to (0.0..15000.0),
)

@Serializable
private data class ImportDayDto(
    val date: String? = null,
    val weight: Double? = null,
    val eaten_kcal: Double? = null,
    val protein: Double? = null,
    val fat: Double? = null,
    val carbs: Double? = null,
    val body_fat: Double? = null,
    val bone: Double? = null,
    // Шаги приходят как Double: дробное значение - повод отбраковать строку,
    // а не уронить разбор всей таблицы.
    val steps: Double? = null,
    val burned_kcal: Double? = null,
    val sleep_h: Double? = null,
)

@Serializable
private data class WellbeingDayDto(
    val date: String? = null,
    val energy: Double? = null,
    val mood: Double? = null,
    val sleep_quality: Double? = null,
)

@Serializable
private data class UnparsedDto(val row: String? = null, val problem: String? = null)

@Serializable
private data class ImportReplyDto(
    val days: List<ImportDayDto> = emptyList(),
    val wellbeing: List<WellbeingDayDto> = emptyList(),
    val unparsed: List<UnparsedDto> = emptyList(),
)

private val importJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Разбор ответа ИИ в Предпросмотр. null - ответ вообще не JSON и не про таблицу.
 * Одинаковые по значениям дни (повторный импорт без изменений) в Предпросмотр не попадают.
 * Дни позже [today] уходят в отброшенные: будущего в истории не бывает.
 */
fun parseImportReply(
    raw: String,
    existing: Map<LocalDate, DaySlice>,
    existingWellbeing: Map<LocalDate, Wellbeing> = emptyMap(),
    today: LocalDate = LocalDate.now(),
): ImportPreview? {
    val body = raw.substringAfter("{", "").substringBeforeLast("}", "")
    if (body.isBlank()) return null
    val reply = try {
        importJson.decodeFromString<ImportReplyDto>("{$body}")
    } catch (e: Exception) {
        return null
    }

    val rejected = mutableListOf<RejectedRow>()
    val byDate = mutableMapOf<LocalDate, ImportValues>()
    for (day in reply.days) {
        val date = day.date?.let { parseLooseDate(it) }
        when {
            date == null -> rejected += RejectedRow(day.date ?: "?", "дата не разобралась")
            date.isAfter(today) -> rejected += RejectedRow(day.date, "дата из будущего")
            else -> {
                val values = ImportValues(
                    weightKg = day.weight,
                    eatenKcal = day.eaten_kcal,
                    proteinG = day.protein,
                    fatG = day.fat,
                    carbsG = day.carbs,
                    bodyFatPct = day.body_fat,
                    boneMassKg = day.bone,
                    steps = day.steps?.roundToLong(),
                    burnedKcal = day.burned_kcal,
                    sleepMinutes = day.sleep_h?.let { (it * 60).roundToLong() },
                )
                val problem = dayProblem(day, values)
                if (problem != null) rejected += RejectedRow(problem.first, problem.second)
                else byDate[date] = values
            }
        }
    }

    val byWellbeingDate = mutableMapOf<LocalDate, ImportWellbeing>()
    for (day in reply.wellbeing) {
        val date = day.date?.let { parseLooseDate(it) }
        when {
            date == null -> rejected += RejectedRow(day.date ?: "?", "дата не разобралась")
            date.isAfter(today) -> rejected += RejectedRow(day.date, "дата из будущего")
            else -> {
                val scales = listOf(
                    "energy" to day.energy,
                    "mood" to day.mood,
                    "sleep_quality" to day.sleep_quality,
                )
                val bad = scales.firstOrNull { (_, v) ->
                    v != null && (v != v.roundToLong().toDouble() || v < Wellbeing.MIN || v > Wellbeing.MAX)
                }
                val values = ImportWellbeing(day.energy?.toInt(), day.mood?.toInt(), day.sleep_quality?.toInt())
                when {
                    bad != null -> rejected += RejectedRow(
                        "${day.date}: ${bad.second}",
                        "${bad.first} вне шкалы ${Wellbeing.MIN}..${Wellbeing.MAX} или не целое",
                    )
                    values == ImportWellbeing() -> rejected += RejectedRow(day.date, "в строке нет показателей")
                    // Пустая шкала в новой отметке стала бы ложным нулём в графике:
                    // неполная строка годится только как замена существующей отметки.
                    existingWellbeing[date] == null && anyScaleMissing(values) ->
                        rejected += RejectedRow(day.date, "для нового дня нужны все три шкалы")
                    else -> byWellbeingDate[date] = values
                }
            }
        }
    }

    for (u in reply.unparsed) {
        if (u.row != null) rejected += RejectedRow(u.row, u.problem ?: "не разобралась")
    }

    // «Без изменений» = запись ничего не меняет: предлагаем только то, что реально перепишет.
    val entries = byDate.mapNotNull { (date, values) ->
        val old = existing[date]
        val oldValues = ImportValues(
            weightKg = old?.weightKg,
            eatenKcal = old?.eatenKcal,
            proteinG = old?.proteinG,
            fatG = old?.fatG,
            carbsG = old?.carbsG,
            bodyFatPct = old?.bodyFatPct,
            boneMassKg = old?.boneMassKg,
            steps = old?.steps,
            burnedKcal = old?.burnedKcal,
            sleepMinutes = old?.sleepMinutes,
        )
        ImportEntry(date, values, oldValues).takeIf { entry ->
            old == null || entry.toSlice(old) != old
        }
    }.sortedBy { it.date }

    val wellbeingEntries = byWellbeingDate.mapNotNull { (date, values) ->
        val old = existingWellbeing[date]
        val oldValues = ImportWellbeing(old?.energy, old?.mood, old?.sleepQuality)
        ImportWellbeingEntry(date, values, oldValues).takeIf { entry ->
            old == null || entry.toWellbeing(old) != old
        }
    }.sortedBy { it.date }

    return ImportPreview(entries = entries, rejected = rejected, wellbeing = wellbeingEntries)
}

private fun anyScaleMissing(values: ImportWellbeing): Boolean =
    values.energy == null || values.mood == null || values.sleepQuality == null

/**
 * Причина отбраковки строки дня (что показывать в Предпросмотре, по чему править файл)
 * или null, если строка годится. Сон проверяется в часах: в файле часы, а не минуты.
 */
private fun dayProblem(day: ImportDayDto, values: ImportValues): Pair<String, String>? {
    val label = day.date ?: "?"
    day.steps?.takeIf { it != it.roundToLong().toDouble() }?.let { return "$label: $it" to "steps не целое" }
    day.sleep_h?.takeIf { it < 0.0 || it > 24.0 }?.let { return "$label: $it" to "sleep_h вне диапазона 0..24" }
    val bad = VALUE_RANGES.firstOrNull { (key, range) ->
        val v = values.field(key)
        v != null && v !in range
    }
    if (bad != null) {
        return "${day.date}: ${values.field(bad.first)}" to
            "${bad.first} вне диапазона ${bad.second.start}..${bad.second.endInclusive}"
    }
    if (values == ImportValues()) return label to "в строке нет показателей"
    return null
}

/** Запись одного дня: значения разбора сильнее существующих локальных (ADR-0006). */
fun ImportEntry.toSlice(existing: DaySlice?): DaySlice =
    (existing ?: DaySlice(date)).copy(
        weightKg = values.weightKg ?: existing?.weightKg,
        eatenKcal = values.eatenKcal ?: existing?.eatenKcal,
        proteinG = values.proteinG ?: existing?.proteinG,
        fatG = values.fatG ?: existing?.fatG,
        carbsG = values.carbsG ?: existing?.carbsG,
        bodyFatPct = values.bodyFatPct ?: existing?.bodyFatPct,
        boneMassKg = values.boneMassKg ?: existing?.boneMassKg,
        bmrKcal = values.bmrKcal ?: existing?.bmrKcal,
        steps = values.steps ?: existing?.steps,
        burnedKcal = values.burnedKcal ?: existing?.burnedKcal,
        sleepMinutes = values.sleepMinutes ?: existing?.sleepMinutes,
    )

/**
 * Запись отметки Самочувствия: заменяются только указанные шкалы, заметка старой целa.
 * Новый день создаётся только полной строкой: неполная строка без существующей отметки
 * подставила бы нули вместо неуказанных шкал - ложные нули в графике.
 */
fun ImportWellbeingEntry.toWellbeing(existing: Wellbeing?): Wellbeing {
    check(existing != null || !anyScaleMissing(values)) { "новый день Самочувствия - только полной строкой" }
    return Wellbeing(
        date = date,
        energy = values.energy ?: existing?.energy ?: Wellbeing.MIN,
        mood = values.mood ?: existing?.mood ?: Wellbeing.MIN,
        sleepQuality = values.sleepQuality ?: existing?.sleepQuality ?: Wellbeing.MIN,
        note = existing?.note,
    )
}

/** Человекочитаемая строка значений дня для Предпросмотра, например «вес 62.4, съедено 1850 ккал». */
fun ImportValues.describe(): String {
    val parts = mutableListOf<String>()
    weightKg?.let { parts += "вес ${fmt(it)} кг" }
    eatenKcal?.let { parts += "съедено ${fmt(it)} ккал" }
    if (proteinG != null || fatG != null || carbsG != null) {
        parts += "Б ${fmt(proteinG ?: 0.0)} / Ж ${fmt(fatG ?: 0.0)} / У ${fmt(carbsG ?: 0.0)} г"
    }
    bodyFatPct?.let { parts += "процент жира ${fmt(it)}%" }
    boneMassKg?.let { parts += "костная масса ${fmt(it)} кг" }
    bmrKcal?.let { parts += "базовый расход ${fmt(it)} ккал/дн" }
    steps?.let { parts += "шаги ${fmt(it.toDouble())}" }
    burnedKcal?.let { parts += "сожжено ${fmt(it)} ккал" }
    sleepMinutes?.let { parts += "сон ${fmt(it / 60.0)} ч" }
    return parts.joinToString(", ")
}

/** Человекочитаемая строка шкал Самочувствия для Предпросмотра, например «энергия 7, настроение 6». */
fun ImportWellbeing.describe(): String {
    val parts = mutableListOf<String>()
    energy?.let { parts += "энергия $it" }
    mood?.let { parts += "настроение $it" }
    sleepQuality?.let { parts += "качество сна $it" }
    return parts.joinToString(", ")
}

/** Дата из разных записей: ISO, русские «05.01.2025», «5.1.25». */
fun parseLooseDate(s: String): LocalDate? {
    val cleaned = s.trim().trimEnd('.', ',').replace('/', '.')
    val parts = cleaned.split('.', '-')
    if (parts.size != 3) return null
    val n = parts.map { it.trim() }
    return try {
        when {
            n[0].length == 4 -> LocalDate.of(n[0].toInt(), n[1].toInt(), n[2].toInt())
            else -> {
                val year = n[2].toInt().let { if (it < 100) 2000 + it else it }
                LocalDate.of(year, n[1].toInt(), n[0].toInt())
            }
        }
    } catch (e: Exception) {
        null
    }
}

private fun ImportValues.field(key: String): Double? = when (key) {
    "weight" -> weightKg
    "eaten_kcal" -> eatenKcal
    "protein" -> proteinG
    "fat" -> fatG
    "carbs" -> carbsG
    "body_fat" -> bodyFatPct
    "burned_kcal" -> burnedKcal
    "steps" -> steps?.toDouble()
    else -> boneMassKg
}

/** Число для Предпросмотра: целое без «.0», нецелое с одним знаком. */
fun fmt(v: Double): String = if (v == v.toLong().toDouble()) "${v.toLong()}" else String.format("%.1f", v)
