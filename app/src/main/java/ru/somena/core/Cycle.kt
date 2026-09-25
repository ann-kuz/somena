package ru.somena.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Отслеживание цикла (спека 0003): чистая логика Записей цикла, склейки Менструальных
 * периодов, Прогноза и статистики. Без Android-зависимостей. Данные только ручные,
 * Health Connect не участвует (ADR-0005).
 */

/** Запись цикла одного дня. flow - интенсивность выделений 0..3, pain - боль 0..3. */
data class CycleDay(
    val date: LocalDate,
    val menstruation: Boolean = false,
    val flow: Int = 0,
    val pain: Int = 0,
) {
    companion object {
        const val MAX_FLOW = 3
        const val MAX_PAIN = 3
    }
}

/** Менструальный период: серия отмеченных дней, склеенная по правилам спеки 0003. */
data class PeriodBlock(val start: LocalDate, val endInclusive: LocalDate) {
    fun contains(d: LocalDate): Boolean = !d.isBefore(start) && !d.isAfter(endInclusive)
    val lengthDays: Int get() = ChronoUnit.DAYS.between(start, endInclusive).toInt() + 1
}

/** Диапазон дат включительно: Фертильное окно и прогнозируемый период. */
data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    fun contains(d: LocalDate): Boolean = !d.isBefore(start) && !d.isAfter(endInclusive)
}

/** Прогноз следующего Цикла: все даты расчётные, записями не являются. */
data class CyclePrediction(
    val nextStart: LocalDate,
    val nextEndInclusive: LocalDate,
    val ovulation: LocalDate,
    val fertileWindow: DateRange,
    val cycleLengthDays: Int,
    val periodLengthDays: Int,
    val approximate: Boolean,
)

enum class CyclePhase { MENSTRUATION, PREDICTED, FERTILE, OVULATION, ORDINARY, NONE }

const val MIN_CYCLE_DAYS = 15
const val MAX_CYCLE_DAYS = 60
const val DEFAULT_CYCLE_DAYS = 28
const val DEFAULT_PERIOD_DAYS = 5
const val OVULATION_LUTEAL_DAYS = 14L
const val FERTILE_BEFORE_OVULATION = 5L

private const val BRIDGE_GAPS = 1L // сколько неотмеченных дней подряд ещё склеивает период
private const val RECENT_WINDOW = 6 // расчёт и статистика по последним шести Циклам

/**
 * Склейка отмеченных дней в Менструальные периоды. Разрыв не больше BRIDGE_GAPS
 * неотмеченных дней - всё ещё один период («забыла отметить»); новый Цикл начинается,
 * только если от начала предыдущего прошло не меньше MIN_CYCLE_DAYS, более ранний
 * блок - продолжение того же периода.
 */
fun buildPeriods(days: Collection<CycleDay>): List<PeriodBlock> {
    val marked = days.filter { it.menstruation }.map { it.date }.distinct().sorted()
    if (marked.isEmpty()) return emptyList()
    val blocks = mutableListOf(PeriodBlock(marked.first(), marked.first()))
    for (d in marked.drop(1)) {
        val last = blocks.last()
        val bridged = ChronoUnit.DAYS.between(last.endInclusive, d) <= BRIDGE_GAPS + 1
        val continuation = d.isBefore(last.start.plusDays(MIN_CYCLE_DAYS.toLong()))
        if (bridged || continuation) {
            blocks[blocks.lastIndex] = last.copy(endInclusive = maxOf(last.endInclusive, d))
        } else {
            blocks += PeriodBlock(d, d)
        }
    }
    return blocks
}

/** Длины Циклов между соседними началами; вне 15-60 дней отбрасываются (спека 0003). */
fun cycleLengths(periods: List<PeriodBlock>): List<Int> =
    periods.sortedBy { it.start }.zipWithNext { a, b ->
        ChronoUnit.DAYS.between(a.start, b.start).toInt()
    }.filter { it in MIN_CYCLE_DAYS..MAX_CYCLE_DAYS }

/**
 * Прогноз следующего Цикла; null - нет ни одной записи. От одного периода - дефолт
 * 28/5 с пометкой «приблизительно»; с трёх валидных длин - взвешенное среднее
 * последних RECENT_WINDOW циклов, свежие весят больше.
 */
fun predictCycle(periods: List<PeriodBlock>, today: LocalDate): CyclePrediction? {
    if (periods.isEmpty()) return null
    val lengths = cycleLengths(periods).takeLast(RECENT_WINDOW)
    val approximate = lengths.size < 3
    val cycleLen = if (approximate) DEFAULT_CYCLE_DAYS else weightedAverage(lengths)
    val periodLen =
        if (approximate) DEFAULT_PERIOD_DAYS
        else periods.takeLast(RECENT_WINDOW).map { it.lengthDays }.average().roundToInt().coerceAtLeast(1)
    var next = periods.last().start
    while (next.isBefore(today)) next = next.plusDays(cycleLen.toLong())
    val ovulation = next.minusDays(OVULATION_LUTEAL_DAYS)
    return CyclePrediction(
        nextStart = next,
        nextEndInclusive = next.plusDays(periodLen.toLong() - 1),
        ovulation = ovulation,
        fertileWindow = DateRange(ovulation.minusDays(FERTILE_BEFORE_OVULATION), ovulation),
        cycleLengthDays = cycleLen,
        periodLengthDays = periodLen,
        approximate = approximate,
    )
}

/** Среднее с весами по свежести: чем свежее цикл, тем больше вес (1, 2, 3...). */
private fun weightedAverage(values: List<Int>): Int {
    var num = 0.0
    var den = 0
    values.forEachIndexed { i, v ->
        val w = i + 1
        num += w * v
        den += w
    }
    return (num / den).roundToInt()
}

/** Статистика последних Циклов: длины, средние и отклонение последнего от среднего. */
data class CycleStat(val start: LocalDate, val periodLengthDays: Int, val cycleLengthDays: Int?)

data class CycleStats(
    val recent: List<CycleStat>,
    val averageCycleDays: Int?,
    val averagePeriodDays: Int?,
    /** Минус - последний Цикл короче среднего, то есть период начался раньше. */
    val lastDeviationDays: Int?,
)

fun cycleStats(periods: List<PeriodBlock>): CycleStats {
    val sorted = periods.sortedBy { it.start }
    if (sorted.isEmpty()) return CycleStats(emptyList(), null, null, null)
    val recent = sorted.takeLast(RECENT_WINDOW)
    val stats = recent.mapIndexed { i, p ->
        val nextStart = sorted.getOrNull(sorted.size - recent.size + i + 1)?.start
        CycleStat(
            start = p.start,
            periodLengthDays = p.lengthDays,
            cycleLengthDays = nextStart?.let { ChronoUnit.DAYS.between(p.start, it).toInt() },
        )
    }
    val cleanLengths = stats.mapNotNull { it.cycleLengthDays?.takeIf { len -> len in MIN_CYCLE_DAYS..MAX_CYCLE_DAYS } }
    val averageCycle = cleanLengths.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    val averagePeriod = recent.map { it.lengthDays }.average().roundToInt()
    // «Последний период наступил раньше/позже»: длина Цикла, завершившегося последним
    // началом, против средней. У самого свежего периода следующего начала пока нет.
    val lastDeviation = stats.dropLast(1).lastOrNull()?.cycleLengthDays
        ?.takeIf { it in MIN_CYCLE_DAYS..MAX_CYCLE_DAYS && averageCycle != null }
        ?.let { it - averageCycle!! }
    return CycleStats(stats, averageCycle, averagePeriod, lastDeviation)
}

/** Номер дня Цикла для даты (от последнего начала до неё включительно); null - начала нет. */
fun cycleDayNumber(periods: List<PeriodBlock>, date: LocalDate): Int? =
    periods.lastOrNull { !it.start.isAfter(date) }
        ?.let { ChronoUnit.DAYS.between(it.start, date).toInt() + 1 }

/** Фаза дня: записи всегда честнее прогноза; NONE - данных о цикле нет вовсе. */
fun cyclePhase(periods: List<PeriodBlock>, prediction: CyclePrediction?, date: LocalDate): CyclePhase {
    if (periods.any { it.contains(date) }) return CyclePhase.MENSTRUATION
    val p = prediction ?: return CyclePhase.NONE
    return when {
        date == p.ovulation -> CyclePhase.OVULATION
        p.fertileWindow.contains(date) -> CyclePhase.FERTILE
        DateRange(p.nextStart, p.nextEndInclusive).contains(date) -> CyclePhase.PREDICTED
        else -> CyclePhase.ORDINARY
    }
}

private val cycleDayFormat = DateTimeFormatter.ofPattern("dd.MM")

/** Статусная строка дня: «Период, день 3», «День 12 цикла», «До прогноза 5 дн.» */
fun cycleHeadline(periods: List<PeriodBlock>, prediction: CyclePrediction?, today: LocalDate): String {
    val inPeriod = periods.lastOrNull { it.contains(today) }
    if (inPeriod != null) return "Период, день ${ChronoUnit.DAYS.between(inPeriod.start, today).toInt() + 1}"
    if (prediction != null && today == prediction.nextStart) return "Прогнозируемый первый день"
    val dayNumber = cycleDayNumber(periods, today)
    if (prediction == null) {
        return if (dayNumber == null) "Отметь первый день менструации" else "День $dayNumber цикла"
    }
    return if (dayNumber != null && dayNumber <= prediction.cycleLengthDays) "День $dayNumber цикла"
    else "До прогноза ${ChronoUnit.DAYS.between(today, prediction.nextStart).toInt() + 1} дн."
}

/** Прогнозная строка: «Прогноз: 11.10 - 15.10, овуляция 27.09 (приблизительно)». */
fun cycleForecastLine(prediction: CyclePrediction): String = buildString {
    append("Прогноз: ${prediction.nextStart.format(cycleDayFormat)} - ${prediction.nextEndInclusive.format(cycleDayFormat)}")
    append(", овуляция ${prediction.ovulation.format(cycleDayFormat)}")
    if (prediction.approximate) append(" (приблизительно)")
}
