package ru.somena.core

import java.time.LocalDate

/**
 * Чистая математика графиков (тикет 06). Без Android-зависимостей, тестируется на сервере.
 */

/** Тренд веса: скользящее среднее за окно до [windowDays] дней, требует минимум [minValues] значений. */
fun weightTrend(weights: List<Double?>, windowDays: Int = 7, minValues: Int = 3): List<Double?> =
    weights.indices.map { i ->
        val window = weights.subList(maxOf(0, i - windowDays + 1), i + 1).filterNotNull()
        window.takeIf { it.size >= minValues }?.sumOf { it }?.div(window.size)
    }

/** Последние [days] календарных дней, кончая [endInclusive]: общая форма окна для графиков и контекста. */
fun lastDays(endInclusive: LocalDate, days: Int): List<LocalDate> =
    (days - 1 downTo 0).map { endInclusive.minusDays(it.toLong()) }

/** Период, за который строим графики, от даты-конца включительно назад. */
fun chartRange(endInclusive: LocalDate, period: ChartPeriod): List<LocalDate> =
    when (period) {
        ChartPeriod.WEEK -> lastDays(endInclusive, 7)
        ChartPeriod.MONTH -> lastDays(endInclusive, 30)
        ChartPeriod.ALL -> emptyList() // задаётся данными, не диапазоном
    }

enum class ChartPeriod { WEEK, MONTH, ALL }

/**
 * Копит пиксели перетаскивания между событиями: события по несколько пикселей
 * по отдельности дня не набирают, а вместе - листают окно.
 */
class PanAccumulator {
    private var remainderPx = 0f

    /** Добавляет пиксели жеста со знаком, отдаёт целые дни (0 - день ещё не набрался). */
    fun add(dragPx: Float, pxPerDay: Float): Int {
        if (pxPerDay <= 0f) return 0
        remainderPx += dragPx
        val days = (remainderPx / pxPerDay).toInt()
        if (days != 0) remainderPx -= days * pxPerDay
        return days
    }
}

/** Конец окна не уходит в будущее от [today] и не раньше первого дня данных. */
fun clampWindowEnd(candidate: LocalDate, today: LocalDate, firstDataDate: LocalDate): LocalDate =
    when {
        candidate > today -> today
        candidate < firstDataDate -> firstDataDate
        else -> candidate
    }
