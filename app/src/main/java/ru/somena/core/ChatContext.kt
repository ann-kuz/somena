package ru.somena.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Чат по данным (тикет 07): системный промпт и компактный контекст вопроса.
 * В ИИ уходит только срез за период, а не сырые почасовые записи (спека 0001).
 * Видимые строки без типографских тире: правило копирайта спеки 0002.
 */
const val CHAT_SYSTEM_PROMPT = """Ты - помощник личного приложения здоровья Somena. Отвечай по-русски, тепло, коротко и по делу, обращайся на «ты».

Медицина: ты не врач, но это личное приложение владелицы, которая сама принимает решения о своём здоровье. По вопросам о данных Медкарты выдвигай гипотезы, предлагай проверки и конкретные рекомендации - вплоть до дозировок добавок и схем приёма, если они уместны по данным, - и одной строкой добавляй, что окончательное решение за врачом. От медицинских тем не отказывайся.

Данные пользователя придут отдельным сообщением с пометкой [Данные пользователя на момент вопроса] прямо перед вопросом: дневные срезы за последние 30 дней (шаги, сон, пульс, калории, БЖУ, вес, состав тела), Самочувствие (шкалы 0-10: энергия, настроение, качество сна, заметки), Записи цикла (менструация, интенсивность выделений, боль, а в строке «Цикл» - прогноз), профиль и Медкарта. Медкарта - сводка: список всех записей (даты и виды), все диагнозы за всё время и полные записи за последние полгода; содержимое записей старше окна не показано - если спрашивают о них, скажи об этом прямо. «Дней без данных» перечислены явно: это пропуски, а не нули. Не выдумывай значения и не вычисляй «средние» по дням, которых нет. Прогноз цикла приблизительный: подавай его как оценку, а не факт. К вопросам про цикл относись так же бережно.

Графики: если к ответу уместен график, вставь один или несколько блоков точно в таком виде:
```chart
{"title": "Вес", "days": 30, "metrics": ["weight"]}
```
Коды метрик: steps, sleep, burned, pulse, eaten, protein, fat, carbs, weight, body_fat, bone, bmr, deficit, energy, mood, sleep_quality. Поля days - от 7 до 180, title - короткий заголовок по-русски. Приложение само построит график по настоящей локальной истории пользователя, числа для графика придумывать не нужно и нельзя; окно графика может быть шире контекста, который ты видишь. Если график не уместен - не вставляй блок. Смешивай в одном графике только метрики с близкими единицами.

Занесение данных: если пользователь просит записать или поправить показатели за дату (сожжённые, съеденные, вес, шаги, сон, самочувствие и другие), ответь коротко и вставь один блок точно в таком виде:
```данные
{"days":[{"date":"ГГГГ-ММ-ДД","burned_kcal":2100}],"wellbeing":[{"date":"ГГГГ-ММ-ДД","energy":7,"mood":6,"sleep_quality":8}]}
```
Ключи те же, что у разбора таблицы: weight, eaten_kcal, protein, fat, carbs, body_fat, bone, steps, burned_kcal, sleep_h (сон в часах, дробь допустима), pulse (средний пульс за день в ударах в минуту); wellbeing - целые от 0 до 10; пустые поля не включай. В блок идут только показатели, которые пользователь назвал явно, и только прошедшие даты: будущего в истории не бывает. Значения - только продиктованные пользователем, не твои оценки: данных, которых не хватало - спроси, а не выдумывай. Приложение покажет Предпросмотр, и пользователь запишет его явным «Записать», поэтому в тексте ответа значения не дублируй.

Стиль: короткие абзацы и списки через дефис, без Markdown-разметки (звёздочек, решёток) и без типографских тире - только дефисы и двоеточия."""

/** Компактный контекст: по строке на день, дни без данных перечислены отдельно. */
fun buildChatContext(
    today: LocalDate,
    daysBack: Int = 30,
    data: DayData = DayData(),
    profile: Profile? = null,
    cycle: List<CycleDay> = emptyList(),
    medcard: List<MedRecord> = emptyList(),
): String {
    val fmt = DateTimeFormatter.ofPattern("dd.MM")
    val dates = lastDays(today, daysBack)
    val cycleByDate = cycle.associateBy { it.date }
    val periods = buildPeriods(cycle)
    val prediction = predictCycle(periods, today)

    val dayLines = mutableListOf<String>()
    val emptyDates = mutableListOf<String>()
    // Базовый расход для Дефицита: ручное значение из Профиля, пока весы не дали своего.
    var carriedBmr = profile?.bmrKcal

    for (d in dates) {
        val line = dayLine(
            data.slicesByDate[d],
            data.wellbeingByDate[d].orEmpty(),
            cycleByDate[d],
            cyclePhase(periods, prediction, d),
            carriedBmr,
        )
        if (line == null) emptyDates += d.format(fmt) else dayLines += "${d.format(fmt)}: $line"
        data.slicesByDate[d]?.bmrKcal?.let { carriedBmr = it }
    }

    return buildString {
        append("Данные пользователя за ${dates.first().format(fmt)} - ${dates.last().format(fmt)} ($daysBack дней):\n")
        append(dayLines.joinToString("\n"))
        if (emptyDates.isNotEmpty()) {
            append("\nДней без данных: ${emptyDates.size} (${emptyDates.joinToString(", ")}). Это пропуски, а не нули.")
        }
        append("\nПрофиль: ").append(profileLine(profile, today))
        append("\nЦикл: ").append(cycleSummaryLine(periods, prediction, today, fmt))
        append("\nМедкарта: ").append(medCardContextLine(medcard, today))
    }
}

/** Окно полных записей Медкарты в контексте (спека 0010): полгода. */
const val MED_FULL_WINDOW_DAYS = 180L

/** Бюджет подробной части Медкарты: не вытеснять срезы и Самочувствие из контекста. */
private const val MED_FULL_BUDGET = 6000

/**
 * Сводка Медкарты для Чата по данным (спека 0010): список всех записей (дата и вид),
 * все диагнозы за всё время и полные записи за окно [MED_FULL_WINDOW_DAYS]. Содержимое
 * старше окна в запрос не входит - ИИ видит дату и вид и обязан сказать об этом прямо.
 */
fun medCardContextLine(records: List<MedRecord>, today: LocalDate): String {
    if (records.isEmpty()) return "записей нет"
    val full = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    val recent = records.sortedWith(compareByDescending<MedRecord> { it.date }.thenByDescending { it.id })

    val parts = mutableListOf<String>()
    parts += "записи: " + recent.joinToString("; ") {
        "${it.date.format(full)} ${it.title ?: it.kind.label.lowercase()}${it.markSuffix()}"
    }
    val diagnoses = recent.sortedBy { it.date }.flatMap { r -> r.diagnoses.map { it to r.date } }
    if (diagnoses.isNotEmpty()) {
        parts += "диагнозы за всё время: " + diagnoses.joinToString("; ") { (name, date) ->
            "$name (${date.format(full)})"
        }
    }

    val cutoff = today.minusDays(MED_FULL_WINDOW_DAYS)
    val olderCount = recent.count { it.date < cutoff }
    if (olderCount > 0) {
        parts += "записей старше полугода: $olderCount, содержимого в этом запросе нет - " +
            "скажи об этом прямо, если спросят об их деталях"
    }

    val freshLines = mutableListOf<String>()
    var spent = 0
    var truncated = 0
    for (r in recent.filter { it.date >= cutoff }) {
        val line = medRecordFullLine(r, full)
        if (spent + line.length > MED_FULL_BUDGET) {
            truncated++
            continue
        }
        freshLines += line
        spent += line.length
    }
    if (freshLines.isNotEmpty()) {
        parts += "подробно за полгода: " + freshLines.joinToString("; ")
    }
    if (truncated > 0) {
        parts += "ещё $truncated свежих записей показаны только списком выше"
    }
    return parts.joinToString(". ")
}

/** Пометка записи для строк контекста: «, пометка «до операции»»; без пометки - пусто. */
private fun MedRecord.markSuffix(): String =
    mark?.takeIf { it.isNotBlank() }?.let { ", пометка «$it»" } ?: ""

/** Полная строка записи для контекста: значения с референсами и флагом, заключения, протоколы. */
private fun medRecordFullLine(r: MedRecord, full: DateTimeFormatter): String {
    val date = r.date.format(full)
    return when (r.kind) {
        MedKind.ANALYSIS -> "$date ${r.title ?: "анализ"}${r.markSuffix()}: " + r.items.joinToString(", ") { row ->
            val ref = if (row.refLow != null || row.refHigh != null) {
                " (реф ${row.refLow ?: ""}-${row.refHigh ?: ""})"
            } else {
                ""
            }
            val flag = if (row.outOfRange) " ВНЕ РЕФЕРЕНСА" else ""
            "${row.name} ${fmt(row.value)}${row.unit?.let { " $it" } ?: ""}$ref$flag"
        }
        MedKind.EXAM -> "$date ${r.examType ?: "обследование"}${r.markSuffix()}: ${r.conclusion ?: ""}"
        MedKind.PROTOCOL -> buildString {
            append("$date приём ${r.specialty ?: "врача"}${r.markSuffix()}")
            if (r.diagnoses.isNotEmpty()) append(": диагнозы ${r.diagnoses.joinToString(", ")}")
            if (!r.recommendations.isNullOrBlank()) {
                if (r.diagnoses.isNotEmpty()) append("; ") else append(": ")
                append("рекомендации: ${r.recommendations}")
            }
        }
    }
}

/** Строка одного дня; null значит «данных нет вообще». [priorBmr] - обмен с прошлых дней. */
private fun dayLine(s: DaySlice?, ws: List<Wellbeing>, cyc: CycleDay?, phase: CyclePhase, priorBmr: Double?): String? {
    val parts = mutableListOf<String>()
    if (s != null) {
        s.steps?.let { parts += "шаги $it" }
        s.sleepMinutes?.let { parts += "сон ${it / 60} ч ${it % 60} м" }
        s.pulseAvg?.let { avg ->
            val bounds = if (s.pulseMin != null && s.pulseMax != null) " (мин ${s.pulseMin}, макс ${s.pulseMax})" else ""
            parts += "пульс $avg$bounds уд/мин"
        }
        s.burnedKcal?.let { parts += "сожжено ${fmtNum(it)} ккал" }
        s.eatenKcal?.let { eaten ->
            parts += "съедено ${fmtNum(eaten)} ккал"
            if (s.proteinG != null || s.fatG != null || s.carbsG != null) {
                parts += "Б ${fmtNum(s.proteinG ?: 0.0)} / Ж ${fmtNum(s.fatG ?: 0.0)} / У ${fmtNum(s.carbsG ?: 0.0)} г"
            }
        }
        if (s.burnedKcal != null && s.eatenKcal != null) {
            val bmr = s.bmrKcal ?: priorBmr
            parts += "дефицит ${fmtNum(s.burnedKcal - s.eatenKcal + (bmr ?: 0.0))} ккал"
        }
        s.weightKg?.let { parts += "вес ${fmtNum(it)} кг" }
        s.bodyFatPct?.let { parts += "процент жира ${fmtNum(it)}%" }
        s.boneMassKg?.let { parts += "костная масса ${fmtNum(it)} кг" }
        s.bmrKcal?.let { parts += "базовый расход ${fmtNum(it)} ккал/дн" }
    }
    if (ws.isNotEmpty()) {
        val first = ws.minByOrNull { it.slot }!!
        val second = ws.firstOrNull { it.slot > first.slot }
        if (second == null) {
            parts += "самочувствие: энергия ${first.energy}/10, настроение ${first.mood}/10, сон ${first.sleepQuality}/10"
            first.note?.takeIf { it.isNotBlank() }?.let { parts += "заметка: «$it»" }
        } else {
            parts += "самочувствие (первая и вторая отметки): энергия ${first.energy} и ${second.energy} из 10, " +
                "настроение ${first.mood} и ${second.mood} из 10, сон ${first.sleepQuality} и ${second.sleepQuality} из 10"
            val notes = listOf(first, second).mapNotNull { it.note?.takeIf { n -> n.isNotBlank() } }
            if (notes.isNotEmpty()) parts += "заметки: " + notes.joinToString(", ") { "«$it»" }
        }
    }
    cyc?.let { c ->
        if (c.menstruation) {
            parts += "менструация"
            if (c.flow > 0) parts += "выделения ${flowWord(c.flow)}"
        } else if (phase == CyclePhase.PREDICTED) {
            parts += "прогноз менструации"
        }
        when (c.pain) {
            CycleDay.PAIN_NONE -> parts += "боли нет"
            CycleDay.LEVEL_UNMARKED -> {}
            else -> parts += "боль ${painWord(c.pain)}"
        }
    }
    when (phase) {
        CyclePhase.FERTILE -> parts += "фертильное окно (прогноз)"
        CyclePhase.OVULATION -> parts += "овуляция (прогноз)"
        else -> {}
    }
    return if (parts.isEmpty()) null else parts.joinToString(", ")
}

/** Итоговая строка цикла для контекста: последний период, длина и прогноз начала. */
private fun cycleSummaryLine(
    periods: List<PeriodBlock>,
    prediction: CyclePrediction?,
    today: LocalDate,
    fmt: DateTimeFormatter,
): String {
    if (periods.isEmpty()) return "записей нет"
    val last = periods.last()
    val parts = mutableListOf("последний период ${last.start.format(fmt)} - ${last.endInclusive.format(fmt)}")
    cycleLengths(periods).takeLast(6).takeIf { it.size >= 3 }?.let {
        parts += "длина цикла около ${it.average().roundToInt()} дн"
    }
    prediction?.let {
        parts += "прогноз начала ${it.nextStart.format(fmt)}"
        if (it.approximate) parts += "приблизительно"
    }
    return parts.joinToString(", ")
}

private fun flowWord(flow: Int) = when (flow) {
    1 -> "скудные"
    2 -> "умеренные"
    else -> "обильные"
}

private fun painWord(pain: Int) = when (pain) {
    1 -> "слабая"
    2 -> "средняя"
    else -> "сильная"
}

private fun profileLine(p: Profile?, today: LocalDate): String {
    if (p == null ||
        (p.heightCm == null && p.birthDate == null && p.goalWeightKg == null && p.bmrKcal == null && p.sex == null)
    ) {
        return "не заполнен"
    }
    val parts = mutableListOf<String>()
    p.sex?.let { parts += "пол ${it.labelGenitive}" }
    p.heightCm?.let { parts += "рост $it см" }
    p.ageYears(today)?.let { parts += "полных лет $it" }
    p.goalWeightKg?.let { parts += "цель по весу ${fmtNum(it)} кг" }
    p.bmrKcal?.let { parts += "базовый расход ${fmtNum(it)} ккал/дн (если весы не дали своего)" }
    return parts.joinToString(", ")
}

/** Число для ИИ-контекста: целое без хвоста «.0», нецелое с одним знаком. */
private fun fmtNum(v: Double): String =
    if (v == v.toLong().toDouble()) "${v.toLong()}" else String.format("%.1f", v)
