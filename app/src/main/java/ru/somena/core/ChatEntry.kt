package ru.somena.core

import java.time.LocalDate

/**
 * Ввод данных через Чат по данным: ИИ предлагает значения блоком ```данные``` с тем же
 * строгим JSON, что у Разбора таблицы. Блоки вырезаются из ответа и прогоняются через
 * parseImportReply - та же валидация (будущее отбрасывается, диапазоны проверяются) и тот
 * же Предпросмотр: ничего не записывается без явного «Записать». Сломанный блок не ломает
 * ответ: текст остаётся, а количество разбитых блоков уходит наверх как сигнал ошибки.
 */
data class AiDataReply(
    val text: String,
    val preview: ImportPreview?,
    val brokenBlocks: Int,
)

private val DATA_BLOCK = Regex("```данные\\s*([\\s\\S]*?)```")

fun parseAiDataEntries(
    reply: String,
    existing: Map<LocalDate, DaySlice>,
    existingWellbeing: Map<LocalDate, Wellbeing> = emptyMap(),
    today: LocalDate = LocalDate.now(),
): AiDataReply {
    var broken = 0
    val entries = linkedMapOf<LocalDate, ImportEntry>()
    val wellbeing = linkedMapOf<LocalDate, ImportWellbeingEntry>()
    val rejected = mutableListOf<RejectedRow>()
    val text = DATA_BLOCK.replace(reply) { match ->
        val preview = parseImportReply(match.groupValues[1], existing, existingWellbeing, today)
        if (preview == null) {
            broken++
        } else {
            preview.entries.forEach { entries[it.date] = it }
            preview.wellbeing.forEach { wellbeing[it.date] = it }
            rejected += preview.rejected
        }
        ""
    }.replace(Regex("\n{3,}"), "\n\n").trim()

    val hasBlocks = entries.isNotEmpty() || wellbeing.isNotEmpty() || rejected.isNotEmpty()
    val preview = if (hasBlocks) {
        ImportPreview(entries = entries.values.sortedBy { it.date }, rejected = rejected, wellbeing = wellbeing.values.sortedBy { it.date })
    } else {
        null
    }
    return AiDataReply(text = text, preview = preview, brokenBlocks = broken)
}
