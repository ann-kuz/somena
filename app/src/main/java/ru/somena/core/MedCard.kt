package ru.somena.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Записи Медкарты (спека 0010, ADR-0008): Анализы, Обследования и Протоколы.
 * Чистая логика без Android-зависимостей: флаг «вне референса», валидация
 * для редактора и человекочитаемые строки для списка, Предпросмотра и Чата.
 * Запись ссылается на оригинал в Хранилище, если он есть; ручные записи
 * живут без оригинала.
 */

/** Вид записи Медкарты: [wire] - строка в ответе ИИ и столбце базы. */
enum class MedKind(val label: String, val wire: String) {
    ANALYSIS("Анализ", "analysis"),
    EXAM("Обследование", "exam"),
    PROTOCOL("Протокол", "protocol"),
}

/** Вид по строке из ответа ИИ или базы; null - не наш. */
fun medKindByWire(wire: String?): MedKind? = when (wire) {
    MedKind.ANALYSIS.wire -> MedKind.ANALYSIS
    MedKind.EXAM.wire -> MedKind.EXAM
    MedKind.PROTOCOL.wire -> MedKind.PROTOCOL
    else -> null
}

/** Одна строка Анализа: показатель со значением, единицами и границами референса. */
data class AnalyteRow(
    val name: String,
    val value: Double,
    val unit: String? = null,
    val refLow: Double? = null,
    val refHigh: Double? = null,
) {
    /** «Вне референса»: значение за известными границами; без границ флага нет. */
    val outOfRange: Boolean
        get() = (refLow != null && value < refLow) || (refHigh != null && value > refHigh)
}

/** Запись Медкарты: вид задаёт, какие поля несут смысл. */
data class MedRecord(
    val id: Long = 0,
    val kind: MedKind = MedKind.ANALYSIS,
    val date: LocalDate,
    val createdAt: Long = 0,
    val fileUri: String? = null,
    val fileName: String? = null,
    val title: String? = null,                   // Понятное название («Биохимический анализ крови»)
    val items: List<AnalyteRow> = emptyList(),   // Анализ
    val examType: String? = null,                // Обследование: «УЗИ щитовидной железы»
    val conclusion: String? = null,              // Обследование: заключение текстом
    val specialty: String? = null,               // Протокол: специальность врача
    val diagnoses: List<String> = emptyList(),   // Протокол: диагнозы списком
    val recommendations: String? = null,         // Протокол: рекомендации текстом
    val mark: String? = null,                    // Ручная пометка («до операции», «после операции»)
) {
    val outOfRangeCount: Int get() = items.count { it.outOfRange }

    /** Название записи для списков и итогов: понятное название или вид. */
    fun name(): String = title?.takeIf { it.isNotBlank() } ?: kind.label

    /** Подзаголовок записи в списке Медкарты и Предпросмотре. */
    fun describe(): String =
        if (kind == MedKind.ANALYSIS && !title.isNullOrBlank()) "$title: ${contents()}" else contents()

    /** Содержательная часть без названия: для строк «название: содержимое». */
    fun contents(): String = when (kind) {
        MedKind.ANALYSIS -> buildString {
            append("${items.size} ${pluralIndicator(items.size)}")
            if (outOfRangeCount > 0) append(", $outOfRangeCount вне референса")
        }
        MedKind.EXAM -> listOfNotNull(
            examType?.takeIf { it.isNotBlank() },
            conclusion?.takeIf { it.isNotBlank() }?.let { shorten(it, 70) },
        ).joinToString(": ")
        MedKind.PROTOCOL -> buildString {
            specialty?.takeIf { it.isNotBlank() }?.let { append(it) }
            if (diagnoses.isNotEmpty()) {
                if (isNotEmpty()) append(": ")
                append(shorten(diagnoses.joinToString(", "), 70))
            }
        }
    }

    /** Строка итога для истории чата: «Биохимический анализ крови от 12.05.2026 (до операции): 24 показателя, 3 вне референса». */
    fun chatSummary(): String =
        "${name()} от ${date.format(MED_DATE)}${mark?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}: ${contents()}"
}

private val MED_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** «1 показатель, 2 показателя, 5 показателей». */
fun pluralIndicator(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "показатель"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "показателя"
    else -> "показателей"
}

private fun shorten(s: String, max: Int): String =
    s.trim().let { if (it.length <= max) it else it.take(max - 1) + "…" }

/**
 * Список Медкарты для экрана: всегда по дате новые сверху (при одной дате выше
 * записанная позже), фильтр по виду и поиск по всем текстам записи - названию,
 * пометке, показателям, заключениям, диагнозам, рекомендациям и дате. Пустой
 * запрос и фильтр «все» ничего не отсеивают.
 */
fun medRecordsView(records: List<MedRecord>, query: String, kind: MedKind?): List<MedRecord> {
    val q = query.trim().lowercase()
    return records
        .filter { kind == null || it.kind == kind }
        .filter { q.isBlank() || it.searchText().contains(q) }
        .sortedWith(compareByDescending<MedRecord> { it.date }.thenByDescending { it.id })
}

/** Все тексты записи для поиска одним полем, включая дату и вид. */
private fun MedRecord.searchText(): String = buildString {
    append(date.format(MED_DATE)); append(' ')
    append(kind.label)
    title?.let { append(' '); append(it) }
    mark?.let { append(' '); append(it) }
    examType?.let { append(' '); append(it) }
    conclusion?.let { append(' '); append(it) }
    specialty?.let { append(' '); append(it) }
    diagnoses.forEach { append(' '); append(it) }
    recommendations?.let { append(' '); append(it) }
    items.forEach { append(' '); append(it.name) }
}.lowercase()

object MedValidator {

    /** Ошибки записи для редактора и Предпросмотра; пустой список - можно сохранять. */
    fun validate(r: MedRecord, today: LocalDate): List<String> = buildList {
        if (r.date.isAfter(today)) add("Дата из будущего: прошедшее или сегодняшнее, не позже")
        when (r.kind) {
            MedKind.ANALYSIS -> if (r.items.isEmpty()) add("Анализ без показателей: добавь хотя бы одну строку")
            MedKind.EXAM -> if (r.examType.isNullOrBlank() && r.conclusion.isNullOrBlank()) {
                add("Обследование пустое: укажи вид или заключение")
            }
            MedKind.PROTOCOL -> if (r.specialty.isNullOrBlank() && r.diagnoses.isEmpty() &&
                r.recommendations.isNullOrBlank()
            ) {
                add("Протокол пустой: нужны специальность, диагнозы или рекомендации")
            }
        }
    }
}
