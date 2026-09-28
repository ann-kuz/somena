package ru.somena.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Окно чтения Health Connect для HcImporter: у каждого дня есть неделя на «долечивание» -
 * дозапись задним числом (приём пищи во FatSecret, досинхронизация браслета) подхватывается
 * при любом импорте в течение семи календарных дней. Дни старше недели считаются
 * окончательными, но непрочитанный промежуток после долгого перерыва читается целиком:
 * он важнее недели долечивания.
 */
object ImportWindow {

    /** Сколько календарных дней назад день остаётся доступным для перечитывания. */
    const val HEAL_DAYS = 7L

    /** Начальное окно пустой базы: первая установка читает две недели истории. */
    const val INITIAL_DAYS = 14L

    fun start(now: Instant, zone: ZoneId, lastStored: LocalDate?, initialDays: Long = INITIAL_DAYS): Instant {
        val healStart = now.atZone(zone).toLocalDate().minusDays(HEAL_DAYS).atStartOfDay(zone).toInstant()
        val storedStart = lastStored?.atStartOfDay(zone)?.toInstant() ?: return now.minus(Duration.ofDays(initialDays))
        return minOf(storedStart, healStart)
    }
}
