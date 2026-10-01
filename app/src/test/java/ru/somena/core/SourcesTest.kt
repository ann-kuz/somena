package ru.somena.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Группировка Источников для настроек (ADR-0010): объединение по типам группы,
 * стабильный порядок, приоритет вместо пропавшего Источника сбрасывается.
 */
class SourcesTest {

    private val mi = HcSource("com.xiaomi.wear", "Mi Fitness")
    private val phone = HcSource("com.samsung.health", "Samsung Health")
    private val fitdays = HcSource("cn.fitdays.fitdays", "Fitdays")

    @Test
    fun `несколько Источников одного типа требуют выбора`() {
        val groups = mergeSources(
            mapOf(SourceKind.STEPS to listOf(mi, phone)),
            emptyMap(),
        )
        val activity = groups.first { it.group == SourceGroup.ACTIVITY }
        assertTrue(activity.needsChoice)
        assertTrue(activity.priority.isEmpty())
    }

    @Test
    fun `один Источник выбора не требует`() {
        val groups = mergeSources(
            mapOf(SourceKind.STEPS to listOf(mi)),
            emptyMap(),
        )
        val activity = groups.first { it.group == SourceGroup.ACTIVITY }
        assertFalse(activity.needsChoice)
        assertEquals(listOf(mi), activity.sources)
    }

    @Test
    fun `состав тела объединяет Источников своих типов без повторов`() {
        val groups = mergeSources(
            mapOf(
                SourceKind.WEIGHT to listOf(fitdays),
                SourceKind.BODY_FAT to listOf(fitdays),
                SourceKind.BMR to listOf(fitdays, mi),
            ),
            emptyMap(),
        )
        val body = groups.first { it.group == SourceGroup.BODY }
        // Порядок стабильный по имени: Fitdays раньше Mi Fitness.
        assertEquals(listOf(fitdays, mi).map { it.packageName }, body.sources.map { it.packageName })
        assertTrue(body.needsChoice)
    }

    @Test
    fun `приоритет передается в свою группу а пропавшие Источники из него выпадают`() {
        val groups = mergeSources(
            mapOf(
                SourceKind.STEPS to listOf(mi, phone),
                SourceKind.SLEEP to listOf(mi),
            ),
            mapOf(
                SourceGroup.ACTIVITY.key to listOf(phone.packageName, mi.packageName, "com.gone.app"),
                SourceGroup.SLEEP.key to listOf("com.gone.app"),
            ),
        )
        assertEquals(
            listOf(phone.packageName, mi.packageName),
            groups.first { it.group == SourceGroup.ACTIVITY }.priority,
        )
        assertTrue(groups.first { it.group == SourceGroup.SLEEP }.priority.isEmpty())
    }

    @Test
    fun `порядок Источников стабильный по имени`() {
        val groups = mergeSources(
            mapOf(SourceKind.STEPS to listOf(phone, mi, fitdays)),
            emptyMap(),
        )
        assertEquals(
            listOf("Fitdays", "Mi Fitness", "Samsung Health"),
            groups.first { it.group == SourceGroup.ACTIVITY }.sources.map { it.label },
        )
    }

    @Test
    fun `группа по типу находит владельца каждого типа`() {
        assertEquals(SourceGroup.BODY, SourceGroup.byKind(SourceKind.BONE))
        assertEquals(SourceGroup.ACTIVITY, SourceGroup.byKind(SourceKind.STEPS))
        assertEquals(SourceGroup.BURN, SourceGroup.byKind(SourceKind.BURN))
        assertEquals(SourceGroup.PULSE, SourceGroup.byKind(SourceKind.PULSE))
    }
}

/**
 * Приоритет Источников при чтении (ADR-0010): за каждый день данные берутся у
 * первого Источника порядка, у которого они в этот день есть; дни, где у старших
 * Источников записей нет, падают на следующих - как выбор приложения в Google Fit.
 */
class SourcePriorityTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val d1 = LocalDate.of(2026, 9, 24)
    private val d2 = LocalDate.of(2026, 9, 25)
    private val mi = "com.xiaomi.wear"
    private val phone = "com.samsung.health"

    private fun stepsAt(day: LocalDate, source: String) =
        StepEntry(
            day.atTime(9, 0).atZone(zone).toInstant(),
            day.atTime(10, 0).atZone(zone).toInstant(),
            1000,
            source,
        )

    private fun prioritize(entries: List<StepEntry>, priority: List<String>): List<StepEntry> =
        applySourcePriority(
            entries, priority, zone,
            sourceOf = { it.source },
            timeOf = { it.start },
        )

    @Test
    fun `пустой приоритет - учитываются все Источники`() {
        val entries = listOf(stepsAt(d1, mi), stepsAt(d1, phone), stepsAt(d2, phone))
        assertEquals(entries, prioritize(entries, emptyList()))
    }

    @Test
    fun `за день берется первый Источник порядка у которого есть записи`() {
        val filtered = prioritize(
            listOf(stepsAt(d1, mi), stepsAt(d1, phone), stepsAt(d2, phone)),
            listOf(mi, phone),
        )
        // 24-го писали оба - остался браслет; 25-го только телефон - он и взят.
        assertEquals(listOf(d1 to mi, d2 to phone), filtered.map { it.start.atZone(zone).toLocalDate() to it.source })
    }

    @Test
    fun `день без записей приоритетных Источников падает на непоставленных в порядок`() {
        // Браслет не писал 25-го: телефон пусть будет лучше, чем пустота.
        val filtered = prioritize(listOf(stepsAt(d2, phone)), listOf(mi))
        assertEquals(listOf(phone), filtered.map { it.source })
    }

    @Test
    fun `дни не зависят друг от друга`() {
        // 24-го писал только телефон - берется он; 25-го появились записи браслета - берется браслет.
        val filtered = prioritize(
            listOf(stepsAt(d1, phone), stepsAt(d2, mi), stepsAt(d2, phone)),
            listOf(mi, phone),
        )
        assertEquals(listOf(d1 to phone, d2 to mi), filtered.map { it.start.atZone(zone).toLocalDate() to it.source })
    }
}
