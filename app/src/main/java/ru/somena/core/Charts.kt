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

/** Период, за который строим графики, от даты-конца включительно назад. */
fun chartRange(endInclusive: LocalDate, period: ChartPeriod): List<LocalDate> =
    when (period) {
        ChartPeriod.WEEK -> 6L
        ChartPeriod.MONTH -> 29L
        ChartPeriod.ALL -> 0L // задаётся данными, не диапазоном
    }.let { back ->
        if (period == ChartPeriod.ALL) emptyList()
        else (back downTo 0).map { endInclusive.minusDays(it) }
    }

enum class ChartPeriod { WEEK, MONTH, ALL }
