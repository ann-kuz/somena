package ru.somena.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Чистая логика Журнала: строки «epoch|тег|сообщение», формат отображения и удержание
 * хвоста в 7 дней. Android-часть (хранение в prefs) тонкая и тестируется вручную.
 */
class AppLogTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val now = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli()
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `строка журнала кодируется и разбирается без потерь`() {
        val line = AppLogCore.encode(at("2026-09-30T09:00:00Z"), "Чат", "→ POST /chat")
        val decoded = AppLogCore.decode(line)
        assertEquals(Triple(at("2026-09-30T09:00:00Z"), "Чат", "→ POST /chat"), decoded)
    }

    @Test
    fun `отображение подставляет дату и тег`() {
        val raw = AppLogCore.encode(at("2026-09-30T09:00:00Z"), "БД", "срез 30.09")
        val shown = AppLogCore.display(raw, zone)
        assertEquals("30.09 12:00:00 [БД] срез 30.09", shown)
    }

    @Test
    fun `удержание срезает записи старше семи дней и оставляет свежие`() {
        val old = AppLogCore.encode(now - 8 * AppLogCore.DAY_MS, "Чат", "старый")
        val edge = AppLogCore.encode(now - 7 * AppLogCore.DAY_MS, "Чат", "ровно семь дней: жив")
        val fresh = AppLogCore.encode(now - 1000, "БД", "свежий")
        val kept = AppLogCore.trimOld(listOf(old, edge, fresh).joinToString("\n"), now)
        assertTrue("осталось: $kept", kept.contains("ровно семь дней"))
        assertTrue("осталось: $kept", kept.contains("свежий"))
        assertTrue("осталось: $kept", !kept.contains("старый"))
    }

    @Test
    fun `мусорные и пустые строки не роняют удержание`() {
        val raw = "не-журнал\n\n" + AppLogCore.encode(now, "Чат", "жив") + "\nосколок|без|времени"
        val kept = AppLogCore.trimOld(raw, now)
        assertTrue(kept.contains("жив"))
        assertTrue(!kept.contains("не-журнал"))
    }

    @Test
    fun `лимит длины оставляет хвост`() {
        // Строки идут от «строка 1» к «строка 500»: хвост - самые поздние записи.
        val raw = (1..500).joinToString("\n") { AppLogCore.encode(now - it, "Чат", "строка $it") }
        val capped = AppLogCore.cap(raw, maxChars = 500)
        assertTrue("хвост: ${capped.takeLast(60)}", capped.endsWith("строка 500"))
        assertTrue(capped.length <= 500)
        // Первая строка хвоста - всегда целая запись, не огрызок.
        assertTrue(capped.lines().first().contains("строка"))
    }
}
