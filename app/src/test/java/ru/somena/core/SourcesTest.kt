package ru.somena.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Группировка Источников для настроек (ADR-0010): объединение по типам группы,
 * стабильный порядок, сброс выбора пропавшего Источника.
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
        assertNull(activity.selected)
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
    fun `выбор передаётся в свою группу а выбор пропавшего Источника сбрасывается`() {
        val groups = mergeSources(
            mapOf(
                SourceKind.STEPS to listOf(mi, phone),
                SourceKind.SLEEP to listOf(mi),
            ),
            mapOf(
                SourceGroup.ACTIVITY.key to mi.packageName,
                SourceGroup.SLEEP.key to "com.gone.app",
            ),
        )
        assertEquals(mi.packageName, groups.first { it.group == SourceGroup.ACTIVITY }.selected)
        assertNull(groups.first { it.group == SourceGroup.SLEEP }.selected)
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
    }
}
