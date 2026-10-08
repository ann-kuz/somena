package ru.somena.core

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportArchiveTest {

    private val entries = setOf(EXPORT_ENTRY_MANIFEST, EXPORT_ENTRY_DB, EXPORT_ENTRY_SETTINGS)

    @Test
    fun fileNameHasDate() {
        assertEquals("somena-export-2026-10-08.zip", exportFileName(LocalDate.of(2026, 10, 8)))
    }

    @Test
    fun validArchiveHasNoProblem() {
        val manifest = ExportManifest(format = 1, dbVersion = 8)
        assertNull(exportProblem(hasManifest = true, manifest, entries, currentDbVersion = 8))
    }

    @Test
    fun archiveWithoutManifestIsNotAnArchive() {
        assertEquals(
            ExportProblem.NotAnArchive,
            exportProblem(hasManifest = false, null, entries, currentDbVersion = 8),
        )
        assertEquals(
            ExportProblem.NotAnArchive,
            exportProblem(hasManifest = true, null, entries, currentDbVersion = 8),
        )
    }

    @Test
    fun newerFormatOrNewerDbIsTooNew() {
        val newerFormat = ExportManifest(format = EXPORT_FORMAT + 1, dbVersion = 8)
        assertEquals(
            ExportProblem.TooNew,
            exportProblem(true, newerFormat, entries, currentDbVersion = 8),
        )
        val newerDb = ExportManifest(format = 1, dbVersion = 9)
        assertEquals(
            ExportProblem.TooNew,
            exportProblem(true, newerDb, entries, currentDbVersion = 8),
        )
    }

    @Test
    fun olderArchiveStillRestores() {
        // Копия от старой версии приложения: база старее - поднимется миграциями.
        val old = ExportManifest(format = 1, dbVersion = 6)
        assertNull(exportProblem(true, old, entries, currentDbVersion = 8))
    }

    @Test
    fun missingRequiredEntryIsDamaged() {
        val manifest = ExportManifest(format = 1, dbVersion = 8)
        assertEquals(
            ExportProblem.Damaged,
            exportProblem(true, manifest, setOf(EXPORT_ENTRY_MANIFEST, EXPORT_ENTRY_DB), currentDbVersion = 8),
        )
    }

    @Test
    fun medcardFilesAreOptional() {
        // Копия без Хранилища (мед-файлов не было) легальна.
        val manifest = ExportManifest(format = 1, dbVersion = 8, medFiles = 0)
        assertNull(exportProblem(true, manifest, entries, currentDbVersion = 8))
    }

    @Test
    fun summaryNamesOnlyFilledKinds() {
        // Дата копии считается в зоне телефона: собираем момент из того же дня,
        // чтобы тест не зависел от часового пояса машины.
        val createdAt = LocalDate.of(2026, 10, 8).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val manifest = ExportManifest(
            format = 1,
            appVersion = "0.38.0",
            createdAt = createdAt,
            counts = ExportCounts(days = 120, wellbeing = 4, cycle = 8, chat = 35, medRecords = 12),
            medFiles = 12,
            medBytes = 356_515_840,
        )
        val lines = exportSummary(manifest)
        assertEquals("Копия от 08.10.2026 (приложение 0.38.0)", lines[0])
        assertEquals("120 дней с данными", lines[1])
        assertEquals("4 отметки самочувствия", lines[2])
        assertEquals("8 записей цикла", lines[3])
        assertEquals("35 сообщений чата", lines[4])
        assertEquals("12 записей Медкарты", lines[5])
        assertEquals("12 файлов Медкарты, 340 МБ", lines[6])
    }

    @Test
    fun summarySkipsEmptyKinds() {
        val lines = exportSummary(ExportManifest(counts = ExportCounts(days = 1)))
        assertEquals(listOf("1 день с данными"), lines)
    }

    @Test
    fun summaryOfEmptyArchive() {
        assertEquals(listOf("В копии пока нет записей"), exportSummary(ExportManifest()))
    }

    @Test
    fun humanSizes() {
        assertEquals("40 байт", humanBytes(40))
        assertEquals("512 КБ", humanBytes(512 * 1024L))
        assertEquals("1,5 МБ", humanBytes((1024 + 512) * 1024L))
        assertEquals("2 ГБ", humanBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun prefsRoundtripKeepsTypes() {
        val prefs: Map<String, Any?> = mapOf(
            "profile" to """{"sex":"f"}""",
            "burn_from_steps" to true,
            "chat_mode" to "popular",
            "reminder_enabled" to false,
            "eaten_floor_kcal" to 1500.5f,
            "count_int" to 7,
            "count_long" to 7_000_000_000L,
            "today_order" to setOf("wellbeing", "steps"),
        )
        val restored = jsonToPrefs(Json.parseToJsonElement(prefsToJson(prefs).toString()).jsonObject)
        assertEquals(prefs, restored)
    }

    @Test
    fun prefsDropExcludedAndNullKeys() {
        val json = prefsToJson(
            mapOf(
                "med_folder_uri" to "content://tree/старая-папка",
                "app_log" to "журнал",
                "useless" to null,
                "profile" to "{}",
            ),
        )
        val keys = json.keys
        assertEquals(setOf("profile"), keys)
    }

    @Test
    fun unknownTypeRestoresAsSkipped() {
        val json = buildJsonObject {
            put(
                "strange",
                buildJsonObject {
                    put("t", "x")
                    put("v", "?")
                },
            )
        }
        assertEquals(emptyMap<String, Any?>(), jsonToPrefs(json))
    }
}
