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

/**
 * Пометка «Внести данные» (кнопка в Чате ставит её сама): сообщение, начинающееся с неё,
 * - всегда просьба положить данные в долговременное хранение, даже совсем без глаголов.
 * Промпт обязывает ИИ отвечать на неё блоком ```данные``` в том же ответе.
 */
const val DATA_ENTRY_MARKER = "Внести данные"

fun isDataEntryRequest(text: String): Boolean = text.trim().startsWith(DATA_ENTRY_MARKER, ignoreCase = true)

/**
 * Промпт выделенного разборщика фразы «Внести данные» - по контракту как Разбор таблицы:
 * строгий JSON и ничего другого. Существует потому, что чатовая модель на пометку
 * отвечала «Записываю...» без блока (инцидент 29.09): намерение держит приложение,
 * у модели только одна задача - понять показатель, значение и дату.
 */
const val DATA_ENTRY_SYSTEM_PROMPT = """Ты - разборщик коротких записей приложения здоровья Somena. Пользователь одной фразой диктует показатели за прошедшие даты: сожжённые и съеденные калории, вес, состав тела, БЖУ, шаги, сон, самочувствие. Верни строгий JSON и вообще никакой другой текст.

Формат ответа:
{"days":[{"date":"ГГГГ-ММ-ДД","weight":62.4,"eaten_kcal":1850,"protein":90,"fat":70,"carbs":180,"body_fat":28.1,"bone":2.6,"steps":12000,"burned_kcal":2100,"sleep_h":7.5}],"wellbeing":[{"date":"ГГГГ-ММ-ДД","energy":7,"mood":6,"sleep_quality":8}]}

Правила:
- Сегодня указан в первой строке сообщения. «Вчера», «позавчера», «на выходных» считай от него; даты из будущего запрещены.
- Служебную пометку «Внести данные» в начале сообщения игнорируй: это метка приложения.
- Один показатель - одно поле; пустое место не заполняй нулём. Запятую как десятичный разделитель меняй на точку.
- weight - вес в кг; eaten_kcal - съеденные калории за день; protein, fat, carbs - белки, жиры, углеводы в граммах; body_fat - процент жира; bone - костная масса в кг; steps - шаги за день, целое число; burned_kcal - сожжённые калории за день; sleep_h - сон в часах, дробь допустима.
- Если дату или значение понять нельзя, верни {"days":[],"wellbeing":[]} - ничего не выдумывай."""

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
