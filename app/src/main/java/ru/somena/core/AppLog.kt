package ru.somena.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Чистая логика Журнала приложения (Настройки → Отладка): строки «epoch|тег|сообщение»,
 * формат отображения и удержание хвоста. В журнал попадают запросы Чата, запись базы
 * и ошибки; хранится 7 дней, более ранние части стираются при каждой записи.
 */
object AppLogCore {

    const val DAY_MS = 86_400_000L

    private val display = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")

    fun encode(epochMs: Long, tag: String, message: String): String = "$epochMs|$tag|$message"

    /** Разбор строки журнала; null для пустой, мусорной или без времени. */
    fun decode(line: String): Triple<Long, String, String>? {
        val parts = line.split("|", limit = 3)
        val epoch = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val tag = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val message = parts.getOrNull(2) ?: return null
        return Triple(epoch, tag, message)
    }

    fun format(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        display.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** Строка для экрана и копирования: «dd.MM HH:mm:ss [тег] сообщение». */
    fun display(line: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val (epoch, tag, message) = decode(line) ?: return line
        return "${format(epoch, zone)} [$tag] $message"
    }

    fun displayAll(raw: String, zone: ZoneId = ZoneId.systemDefault()): String =
        raw.lines().filter { it.isNotBlank() }.joinToString("\n") { display(it, zone) }

    /**
     * Удержание: остаются записи не старше [keepDays] дней от [nowMs]. Мусорные строки
     * (после сбоя, от старых версий) стираются вместе с просроченными.
     */
    fun trimOld(raw: String, nowMs: Long, keepDays: Int = 7): String {
        val horizon = nowMs - keepDays * DAY_MS
        return raw.lines()
            .filter { line -> decode(line)?.first?.let { it >= horizon } == true }
            .joinToString("\n")
    }

    /** Лимит объёма: остаётся хвост из целых строк. */
    fun cap(raw: String, maxChars: Int): String {
        if (raw.length <= maxChars) return raw
        return raw.takeLast(maxChars).dropWhile { it != '\n' }.trim('\n')
    }
}
