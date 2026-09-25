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

Безопасность: ты не врач и не ставишь диагнозов. Не называй болезней и не делай медицинских выводов. Если вопрос медицинский или данные похожи на симптом - мягко советуй: «обрати внимание и спроси врача».

Данные пользователя придут отдельным сообщением с пометкой [Данные пользователя на момент вопроса] прямо перед вопросом: дневные срезы за последние 30 дней (шаги, сон, калории, БЖУ, вес, состав тела), Самочувствие (шкалы 0-10: энергия, настроение, качество сна, заметки), Записи цикла (менструация, интенсивность выделений, боль, а в строке «Цикл» - прогноз) и профиль. «Дней без данных» перечислены явно: это пропуски, а не нули. Не выдумывай значения и не вычисляй «средние» по дням, которых нет. Прогноз цикла приблизительный: подавай его как оценку, а не факт. К вопросам про цикл относись так же бережно: без диагнозов и назойливых советов.

Графики: если к ответу уместен график, вставь один или несколько блоков точно в таком виде:
```chart
{"title": "Вес", "days": 30, "metrics": ["weight"]}
```
Коды метрик: steps, sleep, burned, eaten, protein, fat, carbs, weight, body_fat, bone, bmr, deficit, energy, mood, sleep_quality. Поля days - от 7 до 180, title - короткий заголовок по-русски. Приложение само построит график по настоящей локальной истории пользователя, числа для графика придумывать не нужно и нельзя; окно графика может быть шире контекста, который ты видишь. Если график не уместен - не вставляй блок. Смешивай в одном графике только метрики с близкими единицами.

Стиль: короткие абзацы и списки через дефис, без Markdown-разметки (звёздочек, решёток) и без типографских тире - только дефисы и двоеточия."""

/** Компактный контекст: по строке на день, дни без данных перечислены отдельно. */
fun buildChatContext(
    today: LocalDate,
    daysBack: Int = 30,
    data: DayData = DayData(),
    profile: Profile? = null,
    cycle: List<CycleDay> = emptyList(),
): String {
    val fmt = DateTimeFormatter.ofPattern("dd.MM")
    val dates = lastDays(today, daysBack)
    val cycleByDate = cycle.associateBy { it.date }
    val periods = buildPeriods(cycle)
    val prediction = predictCycle(periods, today)

    val dayLines = mutableListOf<String>()
    val emptyDates = mutableListOf<String>()
    for (d in dates) {
        val line = dayLine(
            data.slicesByDate[d],
            data.wellbeingByDate[d],
            cycleByDate[d],
            cyclePhase(periods, prediction, d),
        )
        if (line == null) emptyDates += d.format(fmt) else dayLines += "${d.format(fmt)}: $line"
    }

    return buildString {
        append("Данные пользователя за ${dates.first().format(fmt)} - ${dates.last().format(fmt)} ($daysBack дней):\n")
        append(dayLines.joinToString("\n"))
        if (emptyDates.isNotEmpty()) {
            append("\nДней без данных: ${emptyDates.size} (${emptyDates.joinToString(", ")}). Это пропуски, а не нули.")
        }
        append("\nПрофиль: ").append(profileLine(profile, today))
        append("\nЦикл: ").append(cycleSummaryLine(periods, prediction, today, fmt))
    }
}

/** Строка одного дня; null значит «данных нет вообще». */
private fun dayLine(s: DaySlice?, w: Wellbeing?, cyc: CycleDay?, phase: CyclePhase): String? {
    val parts = mutableListOf<String>()
    if (s != null) {
        s.steps?.let { parts += "шаги $it" }
        s.sleepMinutes?.let { parts += "сон ${it / 60} ч ${it % 60} м" }
        s.burnedKcal?.let { parts += "сожжено ${fmtNum(it)} ккал" }
        s.eatenKcal?.let { eaten ->
            parts += "съедено ${fmtNum(eaten)} ккал"
            if (s.proteinG != null || s.fatG != null || s.carbsG != null) {
                parts += "Б ${fmtNum(s.proteinG ?: 0.0)} / Ж ${fmtNum(s.fatG ?: 0.0)} / У ${fmtNum(s.carbsG ?: 0.0)} г"
            }
        }
        if (s.burnedKcal != null && s.eatenKcal != null) {
            parts += "дефицит ${fmtNum(s.burnedKcal - s.eatenKcal)} ккал"
        }
        s.weightKg?.let { parts += "вес ${fmtNum(it)} кг" }
        s.bodyFatPct?.let { parts += "жир ${fmtNum(it)}%" }
        s.boneMassKg?.let { parts += "кости ${fmtNum(it)} кг" }
        s.bmrKcal?.let { parts += "обмен ${fmtNum(it)} ккал/дн" }
    }
    if (w != null) {
        parts += "самочувствие: энергия ${w.energy}/10, настроение ${w.mood}/10, сон ${w.sleepQuality}/10"
        w.note?.takeIf { it.isNotBlank() }?.let { parts += "заметка: «$it»" }
    }
    cyc?.let { c ->
        if (c.menstruation) {
            parts += "менструация"
            if (c.flow > 0) parts += "выделения ${flowWord(c.flow)}"
        } else if (phase == CyclePhase.PREDICTED) {
            parts += "прогноз менструации"
        }
        if (c.pain > 0) parts += "боль ${painWord(c.pain)}"
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
    if (p == null || (p.heightCm == null && p.birthDate == null && p.goalWeightKg == null)) {
        return "не заполнен"
    }
    val parts = mutableListOf<String>()
    p.heightCm?.let { parts += "рост $it см" }
    p.ageYears(today)?.let { parts += "полных лет $it" }
    p.goalWeightKg?.let { parts += "цель по весу ${fmtNum(it)} кг" }
    return parts.joinToString(", ")
}

/** Число для ИИ-контекста: целое без хвоста «.0», нецелое с одним знаком. */
private fun fmtNum(v: Double): String =
    if (v == v.toLong().toDouble()) "${v.toLong()}" else String.format("%.1f", v)
