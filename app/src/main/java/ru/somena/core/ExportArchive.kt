package ru.somena.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Формат файла Экспорта (спека 0021). Несовместимая перемена формата поднимает число. */
const val EXPORT_FORMAT = 1

/**
 * Имена записей архива. Манифест пишется первым, чтобы предпросмотр восстановления
 * читал один маленький кусок файла, не распаковывая остальное.
 */
const val EXPORT_ENTRY_MANIFEST = "manifest.json"
const val EXPORT_ENTRY_DB = "somena.db"
const val EXPORT_ENTRY_SETTINGS = "settings.json"
const val EXPORT_ENTRY_MED_DIR = "medcard/"

/**
 * Ключи настроек, которые в копию не попадают: адрес папки Хранилища живёт
 * на телефоне (после восстановления папку выбирают заново), а Журнал -
 * местная диагностика, а не данные Пользователя.
 */
val EXCLUDED_PREF_KEYS = setOf("med_folder_uri", "app_log")

/** Сколько записей каждого вида лежит в копии: цифры для предпросмотра восстановления. */
@Serializable
data class ExportCounts(
    val days: Int = 0,
    val wellbeing: Int = 0,
    val cycle: Int = 0,
    val chat: Int = 0,
    val medRecords: Int = 0,
)

/** Манифист Экспорта: первая запись файла-архива. */
@Serializable
data class ExportManifest(
    val format: Int = EXPORT_FORMAT,
    val appVersion: String = "",
    val dbVersion: Int = 0,
    val createdAt: Long = 0,
    val counts: ExportCounts = ExportCounts(),
    val medFiles: Int = 0,
    val medBytes: Long = 0,
)

/** Имя файла копии по дате создания: somena-export-2026-10-08.zip. */
fun exportFileName(date: LocalDate): String =
    "somena-export-${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}.zip"

/** Почему архив нельзя восстановить; null - можно. */
sealed interface ExportProblem {
    /** Это не файл копии Somena: манифеста нет или он не читается. */
    data object NotAnArchive : ExportProblem

    /** Копия сделана более новой версией приложения. */
    data object TooNew : ExportProblem

    /** Манифест читается, но обязательных записей в архиве не хватает. */
    data object Damaged : ExportProblem

    val text: String
        get() = when (this) {
            NotAnArchive -> "Это не файл копии Somena."
            TooNew -> "Копия сделана более новой версией приложения: обнови приложение и попробуй снова."
            Damaged -> "Файл копии неполный: внутри не хватает данных."
        }
}

/**
 * Проверка архива перед восстановлением: манифест на месте и читаем, формат
 * и версия базы в копии не новее самого приложения, обязательные записи
 * (база и настройки) присутствуют. Файлы Медкарты не обязательны: копия
 * без них легальна.
 */
fun exportProblem(
    hasManifest: Boolean,
    manifest: ExportManifest?,
    presentEntries: Set<String>,
    currentDbVersion: Int,
): ExportProblem? {
    if (!hasManifest || manifest == null) return ExportProblem.NotAnArchive
    if (manifest.format > EXPORT_FORMAT) return ExportProblem.TooNew
    if (manifest.dbVersion > currentDbVersion) return ExportProblem.TooNew
    if (EXPORT_ENTRY_DB !in presentEntries || EXPORT_ENTRY_SETTINGS !in presentEntries) {
        return ExportProblem.Damaged
    }
    return null
}

/** Человекочитаемый размер: 340 МБ, 512 КБ, 40 байт. Дробная часть через запятую. */
fun humanBytes(bytes: Long): String {
    val kb = 1024.0
    return when {
        bytes >= kb * kb * kb -> oneDecimal(bytes / (kb * kb * kb)) + " ГБ"
        bytes >= kb * kb -> oneDecimal(bytes / (kb * kb)) + " МБ"
        bytes >= kb -> "${(bytes / kb).toLong()} КБ"
        else -> "$bytes ${pluralRu(bytes.toInt(), "байт", "байта", "байт")}"
    }
}

private fun oneDecimal(value: Double): String {
    val whole = value.toLong()
    val tenth = ((value - whole) * 10).toInt()
    return if (tenth == 0) "$whole" else "$whole,$tenth"
}

/**
 * Строки предпросмотра восстановления (спека 0021): что лежит в копии, по-русски
 * и с правильными формами счёта. Нулевые виды записей не упоминаются.
 */
fun exportSummary(manifest: ExportManifest): List<String> = buildList {
    if (manifest.createdAt > 0) {
        val day = java.time.Instant.ofEpochMilli(manifest.createdAt)
            .atZone(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        val version = if (manifest.appVersion.isNotBlank()) " (приложение ${manifest.appVersion})" else ""
        add("Копия от $day$version")
    }
    val c = manifest.counts
    if (c.days > 0) add("${c.days} ${pluralRu(c.days, "день", "дня", "дней")} с данными")
    if (c.wellbeing > 0) {
        add("${c.wellbeing} ${pluralRu(c.wellbeing, "отметка", "отметки", "отметок")} самочувствия")
    }
    if (c.cycle > 0) add("${c.cycle} ${pluralRu(c.cycle, "запись", "записи", "записей")} цикла")
    if (c.chat > 0) add("${c.chat} ${pluralRu(c.chat, "сообщение", "сообщения", "сообщений")} чата")
    if (c.medRecords > 0) {
        add("${c.medRecords} ${pluralRu(c.medRecords, "запись", "записи", "записей")} Медкарты")
    }
    if (manifest.medFiles > 0) {
        add(
            "${manifest.medFiles} ${pluralRu(manifest.medFiles, "файл", "файла", "файлов")} " +
                "Медкарты, ${humanBytes(manifest.medBytes)}"
        )
    }
    if (isEmpty()) add("В копии пока нет записей")
}

/**
 * Настройки в копии (спека 0021): все ключи SharedPreferences, кроме
 * [EXCLUDED_PREF_KEYS]. Значение помечается типом, чтобы восстановление
 * вернуло ровно тот тип, каким ключ был записан.
 */
fun prefsToJson(prefs: Map<String, Any?>): JsonObject = JsonObject(
    prefs
        .filterKeys { it !in EXCLUDED_PREF_KEYS }
        .mapValues { (_, value) ->
            when (value) {
                null -> JsonNull
                is String -> tagged("s", JsonPrimitive(value))
                is Boolean -> tagged("b", JsonPrimitive(value))
                is Int -> tagged("i", JsonPrimitive(value))
                is Long -> tagged("l", JsonPrimitive(value))
                is Float -> tagged("f", JsonPrimitive(value.toDouble()))
                is Set<*> -> tagged("S", JsonArray(value.map { JsonPrimitive(it.toString()) }))
                else -> JsonNull
            }
        }
        .filterValues { it != JsonNull }
)

private fun tagged(type: String, value: kotlinx.serialization.json.JsonElement): JsonObject =
    JsonObject(mapOf("t" to JsonPrimitive(type), "v" to value))

/** Обратная сборка настроек из копии: ключ -> значение исходного типа. */
fun jsonToPrefs(json: JsonObject): Map<String, Any?> = json.mapValues { (_, entry) ->
    val obj = entry.jsonObject
    val value = obj["v"]
    when (obj["t"]?.jsonPrimitive?.content) {
        "s" -> value?.jsonPrimitive?.content
        "b" -> value?.jsonPrimitive?.booleanOrNull
        "i" -> value?.jsonPrimitive?.intOrNull
        "l" -> value?.jsonPrimitive?.longOrNull
        "f" -> value?.jsonPrimitive?.doubleOrNull?.toFloat()
        "S" -> value?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
        else -> null
    }
}.filterValues { it != null }
