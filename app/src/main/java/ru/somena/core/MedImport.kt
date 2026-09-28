package ru.somena.core

import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Разбор документа в запись Медкарты (спека 0010, тикет 03): чистая логика без
 * Android-зависимостей. Вход - строгий JSON от ИИ, выход - черновик записи для
 * Предпросмотра: дата обязательна (нет в документе - поставить руками), будущее
 * запрещено, существующая запись того же вида и даты предупреждает о дубле.
 */

/** Промпт разбора: отдельный от Чата по данным, требует только JSON. */
const val MED_IMPORT_SYSTEM_PROMPT = """Ты - разборщик медицинских документов приложения здоровья Somena. Пользователь пришлёт текст или изображения медицинского документа: лабораторный анализ, заключение обследования или протокол приёма врача. Верни строгий JSON и никакой другой текст.

Формат ответа:
{"kind":"analysis","date":"ГГГГ-ММ-ДД","items":[{"name":"Гемоглобин","value":134,"unit":"г/л","ref_low":120,"ref_high":150}],"exam_type":null,"conclusion":null,"specialty":null,"diagnoses":[],"recommendations":null,"unparsed":[{"row":"фрагмент","problem":"что не так"}]}

Правила:
- kind: "analysis" - лабораторный бланк с показателями; "exam" - заключение обследования (УЗИ, МРТ, КТ, ЭКГ, рентген и подобные); "protocol" - приём врача с диагнозами и рекомендациями.
- date - дата документа (сдачи, обследования или приёма) в виде ГГГГ-ММ-ДД; русская запись 05.01.2025 означает 5 января 2025. Даты в документе нет - оставь null, не выдумывай.
- items - только для analysis: каждый показатель отдельной строкой; value - число, запятую как десятичный разделитель меняй на точку; unit - единицы измерения; ref_low и ref_high - границы референса из документа, если указаны.
- exam_type - вид обследования, например «УЗИ щитовидной железы»; conclusion - заключение текстом по существу, близко к оригиналу.
- specialty - специальность врача; diagnoses - список диагнозов; recommendations - рекомендации текстом.
- Служебные фрагменты (шапки клиник, логотипы, реклама) пропускай; то, что не разобралось, перечисли в unparsed с причиной.
- Ничего не придумывай: нет данных - нет поля."""

/** Порог плотности текстового слоя pdf (ADR-0009): меньше - считать сканом и звать зрение. */
const val MIN_PDF_TEXT_CHARS = 200

fun pdfTextIsDense(text: String): Boolean =
    text.count { !it.isWhitespace() } >= MIN_PDF_TEXT_CHARS

/** Предпросмотр Разбора документа: черновик записи и всё, что предупредить. */
data class MedImportResult(
    val draft: MedRecord,
    val dateProblem: String? = null,
    val duplicateOf: MedRecord? = null,
    val rejected: List<RejectedRow> = emptyList(),
)

@Serializable
private data class MedItemDto(
    val name: String? = null,
    val value: Double? = null,
    val unit: String? = null,
    val ref_low: Double? = null,
    val ref_high: Double? = null,
)

@Serializable
private data class MedUnparsedDto(val row: String? = null, val problem: String? = null)

@Serializable
private data class MedReplyDto(
    val kind: String? = null,
    val date: String? = null,
    val items: List<MedItemDto> = emptyList(),
    val exam_type: String? = null,
    val conclusion: String? = null,
    val specialty: String? = null,
    val diagnoses: List<String> = emptyList(),
    val recommendations: String? = null,
    val unparsed: List<MedUnparsedDto> = emptyList(),
)

private val medJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Разбор ответа ИИ в черновик записи. null - ответ не JSON или документа не разобрать
 * (вид не определился, содержимого нет). Дата без документа - заглушка «сегодня»
 * с напоминанием поставить вручную; будущее не запрещаем на входе - редактор
 * покажет ошибку и не сохранит до правки.
 */
fun parseMedReply(
    raw: String,
    today: LocalDate = LocalDate.now(),
    existing: List<MedRecord> = emptyList(),
): MedImportResult? {
    val body = raw.substringAfter("{", "").substringBeforeLast("}", "")
    if (body.isBlank()) return null
    val reply = try {
        medJson.decodeFromString<MedReplyDto>("{$body}")
    } catch (e: Exception) {
        return null
    }

    val kind = medKindByWire(reply.kind) ?: reply.let { r ->
        // Вид не назван, но содержимое однозначное: доверяем содержимому.
        when {
            r.items.isNotEmpty() -> MedKind.ANALYSIS
            !r.conclusion.isNullOrBlank() || !r.exam_type.isNullOrBlank() -> MedKind.EXAM
            !r.specialty.isNullOrBlank() || r.diagnoses.isNotEmpty() || !r.recommendations.isNullOrBlank() -> MedKind.PROTOCOL
            else -> null
        }
    } ?: return null

    val rejected = mutableListOf<RejectedRow>()
    val items = reply.items.mapNotNull { item ->
        when {
            item.name.isNullOrBlank() -> {
                rejected += RejectedRow(item.value?.toString() ?: "?", "показатель без названия")
                null
            }
            item.value == null || !item.value.isFinite() -> {
                rejected += RejectedRow(item.name, "значение не разобралось")
                null
            }
            else -> AnalyteRow(
                name = item.name.trim(),
                value = item.value,
                unit = item.unit?.trim()?.takeIf { it.isNotBlank() },
                refLow = item.ref_low,
                refHigh = item.ref_high,
            )
        }
    }
    for (u in reply.unparsed) {
        if (u.row != null) rejected += RejectedRow(u.row, u.problem ?: "не разобрался")
    }

    val diagnoses = reply.diagnoses.map { it.trim() }.filter { it.isNotEmpty() }
    val draft = MedRecord(
        kind = kind,
        date = reply.date?.let { parseLooseDate(it) } ?: today,
        items = items,
        examType = reply.exam_type?.trim()?.takeIf { it.isNotBlank() },
        conclusion = reply.conclusion?.trim()?.takeIf { it.isNotBlank() },
        specialty = reply.specialty?.trim()?.takeIf { it.isNotBlank() },
        diagnoses = diagnoses,
        recommendations = reply.recommendations?.trim()?.takeIf { it.isNotBlank() },
    )

    // Совсем пустая запись - это «не разобралось», а не черновик.
    if (draft.items.isEmpty() && draft.conclusion == null && draft.examType == null &&
        draft.diagnoses.isEmpty() && draft.recommendations == null && draft.specialty == null
    ) {
        return null
    }

    val parsedDate = reply.date?.let { parseLooseDate(it) }
    val dateProblem = when {
        reply.date != null && parsedDate == null -> "дата не разобралась - поставь вручную"
        reply.date != null && parsedDate != null && parsedDate.isAfter(today) -> "дата из будущего - проверь"
        reply.date == null -> "даты в документе не было - поставь вручную"
        else -> null
    }
    val duplicateOf = parsedDate?.let { d -> existing.firstOrNull { it.kind == kind && it.date == d } }

    return MedImportResult(
        draft = draft,
        dateProblem = dateProblem,
        duplicateOf = duplicateOf,
        rejected = rejected,
    )
}
