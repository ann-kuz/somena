package ru.somena.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CycleTest {

    private fun d(iso: String): LocalDate = LocalDate.parse(iso)

    private fun day(iso: String, menstruation: Boolean = true, flow: Int = 0, pain: Int = 0) =
        CycleDay(d(iso), menstruation, flow, pain)

    private fun block(start: String, end: String) = PeriodBlock(d(start), d(end))

    // ---------- buildPeriods: склейка отмеченных дней ----------

    @Test
    fun `соседние дни образуют один период`() {
        val periods = buildPeriods(listOf(day("2026-09-04"), day("2026-09-05"), day("2026-09-06")))
        assertEquals(listOf(block("2026-09-04", "2026-09-06")), periods)
    }

    @Test
    fun `разрыв в один неотмеченный день склеивается`() {
        val periods = buildPeriods(listOf(day("2026-09-04"), day("2026-09-06")))
        assertEquals(listOf(block("2026-09-04", "2026-09-06")), periods)
    }

    @Test
    fun `разрыв в два неотмеченных дня в начале цикла склеивается как продолжение`() {
        // 07.09 раньше чем 04.09 + 15 дней, поэтому это тот же период, а не новый цикл.
        val periods = buildPeriods(listOf(day("2026-09-04"), day("2026-09-07")))
        assertEquals(listOf(block("2026-09-04", "2026-09-07")), periods)
    }

    @Test
    fun `мостик спасает длинный период с дыркой`() {
        // Период 01.09-14.09, затем отметка 16.09: продолжением по порогу не будет,
        // но разрыв в два дня от конца периода - всё ещё тот же период.
        val periods = buildPeriods(
            listOf(
                day("2026-09-01"), day("2026-09-14"), day("2026-09-16"),
            )
        )
        assertEquals(listOf(block("2026-09-01", "2026-09-16")), periods)
    }

    @Test
    fun `блок раньше пятнадцати дней от начала это продолжение периода`() {
        // Период 01.09-02.09, затем отметка 10.09: от начала прошло 9 дней - тот же период.
        val periods = buildPeriods(listOf(day("2026-09-01"), day("2026-09-02"), day("2026-09-10")))
        assertEquals(listOf(block("2026-09-01", "2026-09-10")), periods)
    }

    @Test
    fun `блок через пятнадцать дней от начала это новый цикл`() {
        val periods = buildPeriods(listOf(day("2026-09-01"), day("2026-09-16")))
        assertEquals(listOf(block("2026-09-01", "2026-09-01"), block("2026-09-16", "2026-09-16")), periods)
    }

    @Test
    fun `дни без менструации и дубликаты не учитываются`() {
        val periods = buildPeriods(
            listOf(
                day("2026-09-04", menstruation = true),
                day("2026-09-05", menstruation = false, pain = 2),
                day("2026-09-04", menstruation = true),
            )
        )
        assertEquals(listOf(block("2026-09-04", "2026-09-04")), periods)
    }

    @Test
    fun `пустой ввод даёт пустой список`() {
        assertTrue(buildPeriods(emptyList()).isEmpty())
        assertTrue(buildPeriods(listOf(day("2026-09-04", menstruation = false))).isEmpty())
    }

    // ---------- cycleLengths: чистые длины ----------

    @Test
    fun `длины вне пятнадцати шестидесяти дней отбрасываются`() {
        val periods = listOf(
            block("2026-06-01", "2026-06-05"),
            block("2026-06-20", "2026-06-24"), // цикл 19 - годен
            block("2026-06-30", "2026-07-02"), // цикл 10 - мусор
            block("2026-09-15", "2026-09-18"), // цикл 77 - мусор
        )
        assertEquals(listOf(19), cycleLengths(periods))
    }

    // ---------- predictCycle ----------

    @Test
    fun `без записей прогноза нет`() {
        assertNull(predictCycle(emptyList(), d("2026-09-25")))
    }

    @Test
    fun `от одного периода прогноз по дефолту 28 на 5 и приблизительно`() {
        val prediction = predictCycle(listOf(block("2026-09-01", "2026-09-05")), d("2026-09-25"))!!
        assertEquals(d("2026-09-29"), prediction.nextStart)
        assertEquals(d("2026-10-03"), prediction.nextEndInclusive)
        assertEquals(28, prediction.cycleLengthDays)
        assertEquals(5, prediction.periodLengthDays)
        assertTrue(prediction.approximate)
        assertEquals(d("2026-09-15"), prediction.ovulation)
        assertEquals(d("2026-09-10"), prediction.fertileWindow.start)
    }

    @Test
    fun `прогноз прокатывается вперёд до сегодняшнего дня`() {
        val prediction = predictCycle(listOf(block("2026-06-01", "2026-06-05")), d("2026-09-25"))!!
        assertEquals(d("2026-10-19"), prediction.nextStart) // 01.06 + 28 * 5
        assertFalse(prediction.nextStart.isBefore(d("2026-09-25")))
    }

    @Test
    fun `с трёх валидных циклов прогноз по взвешенному среднему без пометки`() {
        // Длины 30, 28, 26 с весами 1, 2, 3: (30 + 56 + 78) / 6 = 27,33 -> 27.
        val periods = listOf(
            block("2026-07-01", "2026-07-05"),
            block("2026-07-31", "2026-08-04"), // цикл 30
            block("2026-08-28", "2026-08-31"), // цикл 28, период 4
            block("2026-09-23", "2026-09-25"), // цикл 26
        )
        val prediction = predictCycle(periods, d("2026-09-25"))!!
        assertEquals(27, prediction.cycleLengthDays)
        assertFalse(prediction.approximate)
        assertEquals(d("2026-10-20"), prediction.nextStart)
        // Средний период (5 + 5 + 4 + 3) / 4 = 4,25 -> 4.
        assertEquals(4, prediction.periodLengthDays)
    }

    @Test
    fun `один мусорный цикл не портит прогноз по истории`() {
        // 19 и 10 дней: 10 - мусор, остаётся одна валидная длина, значит дефолт.
        val periods = listOf(
            block("2026-09-01", "2026-09-05"),
            block("2026-09-20", "2026-09-22"),
            block("2026-09-30", "2026-10-02"),
        )
        val prediction = predictCycle(periods, d("2026-10-02"))!!
        assertEquals(28, prediction.cycleLengthDays)
        assertTrue(prediction.approximate)
    }

    // ---------- cycleStats ----------

    @Test
    fun `статистика считает средние и отклонение последнего цикла`() {
        val periods = listOf(
            block("2026-08-01", "2026-08-04"), // период 4
            block("2026-08-30", "2026-09-03"), // цикл 29, период 5
            block("2026-09-28", "2026-10-01"), // цикл 29, период 4
        )
        val stats = cycleStats(periods)
        assertEquals(3, stats.recent.size)
        assertNull(stats.recent.last().cycleLengthDays) // у последнего периода следующее начало неизвестно
        assertEquals(29, stats.recent[1].cycleLengthDays)
        assertEquals(29, stats.averageCycleDays)
        assertEquals(4, stats.averagePeriodDays)
        assertEquals(0, stats.lastDeviationDays)
    }

    @Test
    fun `отклонение со знаком - короче среднего значит раньше`() {
        val periods = listOf(
            block("2026-08-01", "2026-08-04"),
            block("2026-08-30", "2026-09-02"), // цикл 29
            block("2026-09-26", "2026-09-29"), // цикл 27
        )
        val stats = cycleStats(periods)
        assertEquals(28, stats.averageCycleDays)
        assertEquals(-1, stats.lastDeviationDays)
    }

    @Test
    fun `статистика держит последние шесть циклов`() {
        var start = d("2026-01-01")
        val periods = mutableListOf<PeriodBlock>()
        repeat(9) {
            val end = start.plusDays(3)
            periods += PeriodBlock(start, end)
            start = start.plusDays(30)
        }
        val stats = cycleStats(periods)
        assertEquals(6, stats.recent.size)
        assertEquals(30, stats.averageCycleDays)
    }

    @Test
    fun `пустая история даёт пустую статистику`() {
        val stats = cycleStats(emptyList())
        assertTrue(stats.recent.isEmpty())
        assertNull(stats.averageCycleDays)
        assertNull(stats.lastDeviationDays)
    }

    // ---------- фазы и статусные строки ----------

    @Test
    fun `запись дня важнее прогноза`() {
        val periods = buildPeriods(listOf(day("2026-09-24"), day("2026-09-25")))
        val prediction = predictCycle(periods, d("2026-09-25"))!!
        assertEquals(CyclePhase.MENSTRUATION, cyclePhase(periods, prediction, d("2026-09-25")))
        assertEquals(CyclePhase.PREDICTED, cyclePhase(periods, prediction, prediction.nextStart))
        assertEquals(CyclePhase.FERTILE, cyclePhase(periods, prediction, prediction.ovulation.minusDays(3)))
        assertEquals(CyclePhase.OVULATION, cyclePhase(periods, prediction, prediction.ovulation))
        assertEquals(CyclePhase.ORDINARY, cyclePhase(periods, prediction, d("2026-09-20")))
        assertEquals(CyclePhase.NONE, cyclePhase(emptyList(), null, d("2026-09-25")))
    }

    @Test
    fun `номер дня цикла считается от последнего начала`() {
        val periods = buildPeriods(listOf(day("2026-09-23"), day("2026-09-24")))
        assertEquals(3, cycleDayNumber(periods, d("2026-09-25")))
        assertNull(cycleDayNumber(periods, d("2026-09-20")))
    }

    @Test
    fun `статусные строки дни менструации цикла и ожидания`() {
        val periods = buildPeriods(listOf(day("2026-09-23"), day("2026-09-25")))
        assertEquals("Период, день 3", cycleHeadline(periods, null, d("2026-09-25")))
        assertEquals("День 5 цикла", cycleHeadline(periods, null, d("2026-09-27")))
        assertEquals("Отметь первый день менструации", cycleHeadline(emptyList(), null, d("2026-09-25")))
        val prediction = predictCycle(periods, d("2026-10-30"))!!
        assertEquals(
            "До прогноза ${java.time.temporal.ChronoUnit.DAYS.between(d("2026-10-30"), prediction.nextStart) + 1} дн.",
            cycleHeadline(periods, prediction, d("2026-10-30")),
        )
    }

    @Test
    fun `прогнозная строка с пометкой и датами`() {
        val prediction = predictCycle(listOf(block("2026-09-01", "2026-09-05")), d("2026-09-25"))!!
        assertEquals("Прогноз: 29.09 - 03.10, овуляция 15.09 (приблизительно)", cycleForecastLine(prediction))
    }
}
