package ru.somena.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.somena.core.AnalyteRow
import ru.somena.core.DaySlice
import ru.somena.core.MedRecord
import ru.somena.core.medKindByWire

@Serializable
private data class SliceDto(
    val date: String,
    val steps: Long? = null,
    val sleepMinutes: Long? = null,
    val burnedKcal: Double? = null,
    val pulseAvg: Long? = null,
    val pulseMin: Long? = null,
    val pulseMax: Long? = null,
    val eatenKcal: Double? = null,
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val carbsG: Double? = null,
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val boneMassKg: Double? = null,
    val bmrKcal: Double? = null,
)

private fun DaySlice.toDto() = SliceDto(
    date = date.format(ISO_LOCAL_DATE),
    steps = steps, sleepMinutes = sleepMinutes, burnedKcal = burnedKcal,
    pulseAvg = pulseAvg, pulseMin = pulseMin, pulseMax = pulseMax,
    eatenKcal = eatenKcal,
    proteinG = proteinG, fatG = fatG, carbsG = carbsG,
    weightKg = weightKg, bodyFatPct = bodyFatPct, boneMassKg = boneMassKg, bmrKcal = bmrKcal,
)

private fun SliceDto.toDomain() = DaySlice(
    date = LocalDate.parse(date),
    steps = steps, sleepMinutes = sleepMinutes, burnedKcal = burnedKcal,
    pulseAvg = pulseAvg, pulseMin = pulseMin, pulseMax = pulseMax,
    eatenKcal = eatenKcal,
    proteinG = proteinG, fatG = fatG, carbsG = carbsG,
    weightKg = weightKg, bodyFatPct = bodyFatPct, boneMassKg = boneMassKg, bmrKcal = bmrKcal,
)

/** Сообщение диалога Чата по данным (тикет 07): роль user/assistant и текст. */
@Serializable
data class ChatMessage(val role: String, val content: String, val sentAt: Long) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

/** Носимое содержимое записи Медкарта (спека 0010): поля по виду записи. */
@Serializable
private data class MedPayloadDto(
    val title: String? = null,
    val examType: String? = null,
    val conclusion: String? = null,
    val specialty: String? = null,
    val diagnoses: List<String> = emptyList(),
    val recommendations: String? = null,
    val items: List<MedItemDto> = emptyList(),
)

@Serializable
private data class MedItemDto(
    val name: String,
    val value: Double,
    val unit: String? = null,
    val refLow: Double? = null,
    val refHigh: Double? = null,
)

private fun MedRecord.toMedPayload() = MedPayloadDto(
    title = title?.takeIf { it.isNotBlank() },
    examType = examType?.takeIf { it.isNotBlank() },
    conclusion = conclusion?.takeIf { it.isNotBlank() },
    specialty = specialty?.takeIf { it.isNotBlank() },
    diagnoses = diagnoses,
    recommendations = recommendations?.takeIf { it.isNotBlank() },
    items = items.map {
        MedItemDto(it.name, it.value, it.unit?.takeIf { s -> s.isNotBlank() }, it.refLow, it.refHigh)
    },
)

private fun medPayloadToRecord(
    id: Long,
    kind: String,
    date: String,
    fileUri: String?,
    fileName: String?,
    createdAt: Long,
    mark: String?,
    payload: MedPayloadDto,
): MedRecord = MedRecord(
    id = id,
    kind = medKindByWire(kind) ?: ru.somena.core.MedKind.ANALYSIS,
    date = LocalDate.parse(date),
    createdAt = createdAt,
    fileUri = fileUri,
    fileName = fileName,
    title = payload.title,
    items = payload.items.map {
        AnalyteRow(it.name, it.value, it.unit, it.refLow, it.refHigh)
    },
    examType = payload.examType,
    conclusion = payload.conclusion,
    specialty = payload.specialty,
    diagnoses = payload.diagnoses,
    recommendations = payload.recommendations,
    mark = mark,
)

/** Локальное хранилище Дневных срезов, Самочувствия, Записей цикла, истории чата и Медкарты (ADR-0002: данные живут на телефоне). */
class SliceDb(context: Context) : SQLiteOpenHelper(context, "somena.db", null, 7) {

    private val json = Json { ignoreUnknownKeys = true }

    // Контекст для Журнала (Настройки → Отладка): каждая запись базы оставляет строку.
    private val logContext = context

    /** Запись в Журнал о работе базы: содержимое данных не пишется, только факт записи. */
    private fun logged(line: String) {
        AppLog.append(logContext, AppLog.DB, line)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_DAY_SLICES)
        db.execSQL(SQL_CREATE_WELLBEING)
        db.execSQL(SQL_CREATE_CHAT)
        db.execSQL(SQL_CREATE_CYCLE_DAYS)
        db.execSQL(SQL_CREATE_MED_RECORDS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(SQL_CREATE_WELLBEING)
        if (oldVersion < 3) {
            // Шкалы Самочувствия стали 0–10: прежние значения 1–5 переносятся удвоением (1→2 … 5→10).
            db.execSQL(
                "UPDATE wellbeing SET energy = energy * 2, mood = mood * 2, sleep_quality = sleep_quality * 2"
            )
            db.execSQL(SQL_CREATE_CHAT)
        }
        if (oldVersion < 4) db.execSQL(SQL_CREATE_CYCLE_DAYS)
        if (oldVersion < 5) {
            // Самочувствие дважды в день: ключ (дата, слот), прежние записи становятся первыми.
            db.execSQL("ALTER TABLE wellbeing RENAME TO wellbeing_old")
            db.execSQL(SQL_CREATE_WELLBEING)
            db.execSQL(
                "INSERT INTO wellbeing (date, slot, energy, mood, sleep_quality, note, updated_at) " +
                    "SELECT date, ${ru.somena.core.Wellbeing.SLOT_FIRST}, energy, mood, sleep_quality, note, updated_at " +
                    "FROM wellbeing_old"
            )
            db.execSQL("DROP TABLE wellbeing_old")
        }
        if (oldVersion < 6) db.execSQL(SQL_CREATE_MED_RECORDS)
        if (oldVersion < 7) db.execSQL("ALTER TABLE med_records ADD COLUMN mark TEXT")
    }

    /**
     * Запись Дневного среза. Неизменившийся срез не пишется и не попадает в Журнал:
     * импорт переписывает всё окно при каждом «Обновить», а записью базы считается
     * только реальное изменение. Возврат - была ли запись.
     */
    fun upsert(slice: DaySlice): Boolean {
        if (get(slice.date) == slice) return false
        val values = android.content.ContentValues().apply {
            put("date", slice.date.format(ISO_LOCAL_DATE))
            put("data", json.encodeToString(slice.toDto()))
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("day_slices", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        logged("срез ${slice.date}")
        return true
    }

    fun get(date: LocalDate): DaySlice? =
        readableDatabase.rawQuery(
            "SELECT data FROM day_slices WHERE date = ?", arrayOf(date.format(ISO_LOCAL_DATE))
        ).use { c ->
            if (c.moveToFirst()) json.decodeFromString<SliceDto>(c.getString(0)).toDomain() else null
        }

    /** Все сохранённые срезы по возрастанию даты — для графиков и экспорта. */
    fun all(): List<DaySlice> =
        readableDatabase.rawQuery("SELECT data FROM day_slices ORDER BY date", null).use { c ->
            buildList {
                while (c.moveToNext()) add(json.decodeFromString<SliceDto>(c.getString(0)).toDomain())
            }
        }

    fun lastStoredDate(): LocalDate? =
        readableDatabase.rawQuery("SELECT MAX(date) FROM day_slices", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) LocalDate.parse(c.getString(0)) else null
        }

    /** Запись Самочувствия; неизменившаяся отметка не пишется и не логируется. */
    fun upsert(w: ru.somena.core.Wellbeing): Boolean {
        if (getWellbeing(w.date, w.slot) == w) return false
        val values = android.content.ContentValues().apply {
            put("date", w.date.format(ISO_LOCAL_DATE))
            put("slot", w.slot)
            put("energy", w.energy)
            put("mood", w.mood)
            put("sleep_quality", w.sleepQuality)
            put("note", w.note)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("wellbeing", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        logged("самочувствие ${w.date}, отметка №${w.slot + 1}")
        return true
    }

    /** Последняя отметка дня: значение дня для напоминания и «Сегодня». */
    fun getLatestWellbeing(date: LocalDate): ru.somena.core.Wellbeing? =
        readableDatabase.rawQuery(
            "SELECT slot, energy, mood, sleep_quality, note FROM wellbeing " +
                "WHERE date = ? ORDER BY slot DESC LIMIT 1",
            arrayOf(date.format(ISO_LOCAL_DATE))
        ).use { c -> if (c.moveToFirst()) readWellbeing(c, date) else null }

    /** Отметка конкретного слота дня: для редактора и Разбора таблицы. */
    fun getWellbeing(date: LocalDate, slot: Int): ru.somena.core.Wellbeing? =
        readableDatabase.rawQuery(
            "SELECT slot, energy, mood, sleep_quality, note FROM wellbeing WHERE date = ? AND slot = ?",
            arrayOf(date.format(ISO_LOCAL_DATE), slot.toString())
        ).use { c -> if (c.moveToFirst()) readWellbeing(c, date) else null }

    /** Отметки одного дня по порядку: для плашки Самочувствия на «Сегодня». */
    fun dayWellbeing(date: LocalDate): List<ru.somena.core.Wellbeing> =
        readableDatabase.rawQuery(
            "SELECT slot, energy, mood, sleep_quality, note FROM wellbeing WHERE date = ? ORDER BY slot",
            arrayOf(date.format(ISO_LOCAL_DATE))
        ).use { c -> buildList { while (c.moveToNext()) add(readWellbeing(c, date)) } }

    /** Всё Самочувствие по возрастанию даты и слота: для графика и контекста Чата по данным. */
    fun allWellbeing(): List<ru.somena.core.Wellbeing> =
        readableDatabase.rawQuery(
            "SELECT slot, energy, mood, sleep_quality, note, date FROM wellbeing ORDER BY date, slot", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    ru.somena.core.Wellbeing(
                        date = LocalDate.parse(c.getString(5)),
                        energy = c.getInt(1),
                        mood = c.getInt(2),
                        sleepQuality = c.getInt(3),
                        note = if (c.isNull(4)) null else c.getString(4),
                        slot = c.getInt(0),
                    )
                )
            }
        }

    private fun readWellbeing(c: android.database.Cursor, date: LocalDate): ru.somena.core.Wellbeing =
        ru.somena.core.Wellbeing(
            date = date,
            energy = c.getInt(1),
            mood = c.getInt(2),
            sleepQuality = c.getInt(3),
            note = if (c.isNull(4)) null else c.getString(4),
            slot = c.getInt(0),
        )

    // ---- Записи цикла (спека 0003, ADR-0005: только локально) ----

    fun upsertCycleDay(e: ru.somena.core.CycleDay) {
        val values = android.content.ContentValues().apply {
            put("date", e.date.format(ISO_LOCAL_DATE))
            put("menstruation", if (e.menstruation) 1 else 0)
            put("flow", e.flow)
            put("pain", e.pain)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("cycle_days", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        logged("запись цикла ${e.date}")
    }

    fun getCycleDay(date: LocalDate): ru.somena.core.CycleDay? =
        readableDatabase.rawQuery(
            "SELECT menstruation, flow, pain FROM cycle_days WHERE date = ?",
            arrayOf(date.format(ISO_LOCAL_DATE))
        ).use { c ->
            if (!c.moveToFirst()) null else ru.somena.core.CycleDay(
                date = date,
                menstruation = c.getInt(0) == 1,
                flow = c.getInt(1),
                pain = c.getInt(2),
            )
        }

    /** Все Записи цикла по возрастанию даты: календарь, прогноз и контекст Чата. */
    fun allCycleDays(): List<ru.somena.core.CycleDay> =
        readableDatabase.rawQuery(
            "SELECT date, menstruation, flow, pain FROM cycle_days ORDER BY date", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    ru.somena.core.CycleDay(
                        date = LocalDate.parse(c.getString(0)),
                        menstruation = c.getInt(1) == 1,
                        flow = c.getInt(2),
                        pain = c.getInt(3),
                    )
                )
            }
        }

    /** Полное снятие отметки дня: отдельный delete, потому что REPLACE не умеет «удалить». */
    fun deleteCycleDay(date: LocalDate) {
        writableDatabase.delete("cycle_days", "date = ?", arrayOf(date.format(ISO_LOCAL_DATE)))
        logged("запись цикла ${date}: снята")
    }

    fun addChatMessage(role: String, content: String) {
        val values = android.content.ContentValues().apply {
            put("role", role)
            put("content", content)
            put("sent_at", System.currentTimeMillis())
        }
        writableDatabase.insert("chat_messages", null, values)
        // Содержимое сообщения в Журнал не пишется: только факт строки истории.
        logged("история чата: +$role")
    }

    /** История диалога по возрастанию: то, что показываем и отправляем модели. */
    fun chatHistory(): List<ChatMessage> =
        readableDatabase.rawQuery(
            "SELECT role, content, sent_at FROM chat_messages ORDER BY sent_at, id", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(ChatMessage(c.getString(0), c.getString(1), c.getLong(2)))
            }
        }

    fun clearChat() {
        writableDatabase.delete("chat_messages", null, null)
        logged("история чата очищена")
    }

    // ---- Записи Медкарты (спека 0010, ADR-0008) ----

    fun insertMed(r: MedRecord): Long {
        val values = medValues(r)
        val id = writableDatabase.insert("med_records", null, values)
        logged("запись Медкарты №$id (${r.kind.label.lowercase()})")
        return id
    }

    fun updateMed(r: MedRecord) {
        writableDatabase.update("med_records", medValues(r), "id = ?", arrayOf(r.id.toString()))
        logged("запись Медкарты №${r.id} изменена")
    }

    /** Удаление записи никогда не трогает файл Хранилища (ADR-0008). */
    fun deleteMed(id: Long) {
        writableDatabase.delete("med_records", "id = ?", arrayOf(id.toString()))
        logged("запись Медкарты №$id удалена")
    }

    /** Все записи Медкарты по убыванию даты: список Медкарты и контекст Чата. */
    fun allMed(): List<MedRecord> =
        readableDatabase.rawQuery(
            "SELECT id, kind, date, file_uri, file_name, data, created_at, mark FROM med_records ORDER BY date DESC, id DESC",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(readMedRecord(c)) } }

    fun medById(id: Long): MedRecord? =
        readableDatabase.rawQuery(
            "SELECT id, kind, date, file_uri, file_name, data, created_at, mark FROM med_records WHERE id = ?",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) readMedRecord(c) else null }

    private fun medValues(r: MedRecord): android.content.ContentValues = android.content.ContentValues().apply {
        put("kind", r.kind.wire)
        put("date", r.date.format(ISO_LOCAL_DATE))
        put("file_uri", r.fileUri)
        put("file_name", r.fileName)
        put("data", json.encodeToString(r.toMedPayload()))
        put("created_at", r.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis())
        put("mark", r.mark?.takeIf { it.isNotBlank() })
    }

    private fun readMedRecord(c: android.database.Cursor): MedRecord = medPayloadToRecord(
        id = c.getLong(0),
        kind = c.getString(1),
        date = c.getString(2),
        fileUri = if (c.isNull(3)) null else c.getString(3),
        fileName = if (c.isNull(4)) null else c.getString(4),
        createdAt = c.getLong(6),
        mark = if (c.isNull(7)) null else c.getString(7),
        payload = json.decodeFromString<MedPayloadDto>(c.getString(5)),
    )

    private companion object {
        const val SQL_CREATE_DAY_SLICES =
            "CREATE TABLE day_slices (" +
                "date TEXT PRIMARY KEY NOT NULL, " +
                "data TEXT NOT NULL, " +
                "updated_at INTEGER NOT NULL)"
        const val SQL_CREATE_WELLBEING =
            "CREATE TABLE IF NOT EXISTS wellbeing (" +
                "date TEXT NOT NULL, " +
                "slot INTEGER NOT NULL, " +
                "energy INTEGER NOT NULL, " +
                "mood INTEGER NOT NULL, " +
                "sleep_quality INTEGER NOT NULL, " +
                "note TEXT, " +
                "updated_at INTEGER NOT NULL, " +
                "PRIMARY KEY(date, slot))"
        const val SQL_CREATE_CHAT =
            "CREATE TABLE IF NOT EXISTS chat_messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "role TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "sent_at INTEGER NOT NULL)"
        const val SQL_CREATE_CYCLE_DAYS =
            "CREATE TABLE IF NOT EXISTS cycle_days (" +
                "date TEXT PRIMARY KEY NOT NULL, " +
                "menstruation INTEGER NOT NULL, " +
                "flow INTEGER NOT NULL, " +
                "pain INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL)"
        const val SQL_CREATE_MED_RECORDS =
            "CREATE TABLE IF NOT EXISTS med_records (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "kind TEXT NOT NULL, " +
                "date TEXT NOT NULL, " +
                "file_uri TEXT, " +
                "file_name TEXT, " +
                "data TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "mark TEXT)"
    }
}
