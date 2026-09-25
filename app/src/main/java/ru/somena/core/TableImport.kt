package ru.somena.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Разбор таблицы (спека 0004, ADR-0006): чистая логика без Android-зависимостей.
 * Вход - ответ ИИ (строгий JSON) и существующие Дневные срезы; выход - Предпросмотр:
 * какие дни добавятся, какие значения заменят существующие, какие строки не разобрались.
 * Заносятся только рукотворные метрики: вес, состав тела, еда. Шаги, сон и расход
 * таблицей не записываются никогда - их схема разбора просто не содержит (ADR-0001).
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
)

/** Один разобранный день и то, что он заменяет (null - поле было пустым). */
data class ImportEntry(
    val date: LocalDate,
    val values: ImportValues,
    val old: ImportValues,
)

data class RejectedRow(val raw: String, val reason: String)

data class ImportPreview(
    val entries: List<ImportEntry> = emptyList(),
    val rejected: List<RejectedRow> = emptyList(),
) {
    val replacedCount: Int get() = entries.count { e ->
        listOf(e.old.weightKg, e.old.eatenKcal, e.old.proteinG, e.old.fatG, e.old.carbsG, e.old.bodyFatPct, e.old.boneMassKg)
            .any { it != null }
    }
}

/** Промпт разбора: отдельный от Чата по данным, требует только JSON. */
const val IMPORT_SYSTEM_PROMPT = """Ты - разборщик таблиц приложения здоровья Somena. Пользователь пришлёт текст таблицы: колонки с датами и показателями (вес, съеденные калории, БЖУ, состав тела). Твоя задача - вернуть строгий JSON и вообще никакой другой текст.

Формат ответа:
{"days":[{"date":"ГГГГ-ММ-ДД","weight":62.4,"eaten_kcal":1850,"protein":90,"fat":70,"carbs":180,"body_fat":28.1,"bone":2.6}],"unparsed":[{"row":"исходная строка","problem":"что не так"}]}

Правила:
- Даты приведи к виду ГГГГ-ММ-ДД. Русская запись 05.01.2025 означает 5 января 2025.
- Пустая ячейка - поле не включай. Ноль не подставляй: пустое место - это пропуск, а не ноль.
- weight - вес в кг; eaten_kcal - съеденные калории за день; protein, fat, carbs - белки, жиры, углеводы в граммах; body_fat - процент жира; bone - костная масса в кг.
- Запятую как десятичный разделитель (62,4) меняй на точку (62.4).
- Строки, которые не удалось разобрать или не относятся к показателям (заголовок, итоги, пустые), перечисли в unparsed с причиной.
- Ничего не придумывай: нет данных - нет поля. Шаги, сон и расход калорий в таблице игнорируй."""

/** Диапазоны значений (спека 0004): за границей строка целиком уходит в отброшенные. */
private val VALUE_RANGES = listOf(
    "weight" to (25.0..350.0),
    "eaten_kcal" to (0.0..15000.0),
    "protein" to (0.0..2000.0),
    "fat" to (0.0..2000.0),
    "carbs" to (0.0..2000.0),
    "body_fat" to (1.0..70.0),
    "bone" to (0.5..10.0),
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
)

@Serializable
private data class UnparsedDto(val row: String? = null, val problem: String? = null)

@Serializable
private data class ImportReplyDto(val days: List<ImportDayDto> = emptyList(), val unparsed: List<UnparsedDto> = emptyList())

private val importJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Разбор ответа ИИ в Предпросмотр. null - ответ вообще не JSON и не про таблицу.
 * Одинаковые по значениям дни (повторный импорт без изменений) в Предпросмотр не попадают.
 */
fun parseImportReply(raw: String, existing: Map<LocalDate, DaySlice>): ImportPreview? {
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
        if (date == null) {
            rejected += RejectedRow(day.date ?: "?", "дата не разобралась")
            continue
        }
        val values = ImportValues(
            weightKg = day.weight,
            eatenKcal = day.eaten_kcal,
            proteinG = day.protein,
            fatG = day.fat,
            carbsG = day.carbs,
            bodyFatPct = day.body_fat,
            boneMassKg = day.bone,
        )
        val bad = VALUE_RANGES.firstOrNull { (key, range) ->
            val v = values.field(key)
            v != null && v !in range
        }
        if (bad != null) {
            rejected += RejectedRow(
                "${day.date}: ${values.field(bad.first)}",
                "${bad.first} вне диапазона ${bad.second.start}..${bad.second.endInclusive}",
            )
            continue
        }
        if (values == ImportValues()) {
            rejected += RejectedRow(day.date, "в строке нет показателей")
            continue
        }
        byDate[date] = values
    }
    for (u in reply.unparsed) {
        if (u.row != null) rejected += RejectedRow(u.row, u.problem ?: "не разобралась")
    }

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
        )
        if (values == oldValues) null else ImportEntry(date, values, oldValues)
    }.sortedBy { it.date }

    return ImportPreview(entries = entries, rejected = rejected)
}

/** Запись одного дня: значения импорта сильнее существующих локальных (ADR-0006). */
fun ImportEntry.toSlice(existing: DaySlice?): DaySlice =
    (existing ?: DaySlice(date)).copy(
        weightKg = values.weightKg ?: existing?.weightKg,
        eatenKcal = values.eatenKcal ?: existing?.eatenKcal,
        proteinG = values.proteinG ?: existing?.proteinG,
        fatG = values.fatG ?: existing?.fatG,
        carbsG = values.carbsG ?: existing?.carbsG,
        bodyFatPct = values.bodyFatPct ?: existing?.bodyFatPct,
        boneMassKg = values.boneMassKg ?: existing?.boneMassKg,
    )

/** Человекочитаемая строка значений дня для Предпросмотра, например «вес 62.4, съедено 1850 ккал». */
fun ImportValues.describe(): String {
    val parts = mutableListOf<String>()
    weightKg?.let { parts += "вес ${fmt(it)} кг" }
    eatenKcal?.let { parts += "съедено ${fmt(it)} ккал" }
    if (proteinG != null || fatG != null || carbsG != null) {
        parts += "Б ${fmt(proteinG ?: 0.0)} / Ж ${fmt(fatG ?: 0.0)} / У ${fmt(carbsG ?: 0.0)} г"
    }
    bodyFatPct?.let { parts += "жир ${fmt(it)}%" }
    boneMassKg?.let { parts += "кости ${fmt(it)} кг" }
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
    else -> boneMassKg
}

/** Число для Предпросмотра: целое без «.0», нецелое с одним знаком. */
fun fmt(v: Double): String = if (v == v.toLong().toDouble()) "${v.toLong()}" else String.format("%.1f", v)
