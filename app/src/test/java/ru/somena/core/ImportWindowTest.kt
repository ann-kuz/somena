package ru.somena.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Окно чтения Health Connect: дни в пределах недели перечитываются всегда
 * (долечивание задним числом), более старые - от последнего сохранённого дня.
 */
class ImportWindowTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    // 28.09.2026, 15:00 по местному.
    private val now: Instant = LocalDate.of(2026, 9, 28).atTime(15, 0).atZone(zone).toInstant()

    private fun dayStart(date: LocalDate): Instant = date.atStartOfDay(zone).toInstant()

    @Test
    fun `пустая база - начальное окно 14 дней назад`() {
        val start = ImportWindow.start(now, zone, lastStored = null)
        assertEquals(now.minus(java.time.Duration.ofDays(14)), start)
    }

    @Test
    fun `свежая база - долечивание за 7 дней вместо одной попытки`() {
        // Последний сохранённый день - вчера: раньше окно начиналось бы с него,
        // теперь у каждого дня есть неделя на дозапись задним числом.
        val start = ImportWindow.start(now, zone, lastStored = LocalDate.of(2026, 9, 27))
        assertEquals(dayStart(LocalDate.of(2026, 9, 21)), start)
    }

    @Test
    fun `сохранённый день сегодня - окно всё равно неделя`() {
        val start = ImportWindow.start(now, zone, lastStored = LocalDate.of(2026, 9, 28))
        assertEquals(dayStart(LocalDate.of(2026, 9, 21)), start)
    }

    @Test
    fun `долгий перерыв - непрочитанный промежуток важнее недели`() {
        // Приложение не открывалось 10 дней: читать нужно всё с последнего сохранённого дня.
        val start = ImportWindow.start(now, zone, lastStored = LocalDate.of(2026, 9, 18))
        assertEquals(dayStart(LocalDate.of(2026, 9, 18)), start)
    }

    @Test
    fun `граница - ровно семь дней назад`() {
        val start = ImportWindow.start(now, zone, lastStored = LocalDate.of(2026, 9, 21))
        assertEquals(dayStart(LocalDate.of(2026, 9, 21)), start)
    }
}
