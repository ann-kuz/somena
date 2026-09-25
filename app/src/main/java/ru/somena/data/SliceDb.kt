package ru.somena.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.somena.core.DaySlice

@Serializable
private data class SliceDto(
    val date: String,
    val steps: Long? = null,
    val sleepMinutes: Long? = null,
    val burnedKcal: Double? = null,
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
    steps = steps, sleepMinutes = sleepMinutes, burnedKcal = burnedKcal, eatenKcal = eatenKcal,
    proteinG = proteinG, fatG = fatG, carbsG = carbsG,
    weightKg = weightKg, bodyFatPct = bodyFatPct, boneMassKg = boneMassKg, bmrKcal = bmrKcal,
)

private fun SliceDto.toDomain() = DaySlice(
    date = LocalDate.parse(date),
    steps = steps, sleepMinutes = sleepMinutes, burnedKcal = burnedKcal, eatenKcal = eatenKcal,
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

/** Локальное хранилище Дневных срезов, Самочувствия и истории чата (ADR-0002: данные живут на телефоне). */
class SliceDb(context: Context) : SQLiteOpenHelper(context, "somena.db", null, 3) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_DAY_SLICES)
        db.execSQL(SQL_CREATE_WELLBEING)
        db.execSQL(SQL_CREATE_CHAT)
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
    }

    fun upsert(slice: DaySlice) {
        val values = android.content.ContentValues().apply {
            put("date", slice.date.format(ISO_LOCAL_DATE))
            put("data", json.encodeToString(slice.toDto()))
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("day_slices", null, values, SQLiteDatabase.CONFLICT_REPLACE)
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

    fun upsert(w: ru.somena.core.Wellbeing) {
        val values = android.content.ContentValues().apply {
            put("date", w.date.format(ISO_LOCAL_DATE))
            put("energy", w.energy)
            put("mood", w.mood)
            put("sleep_quality", w.sleepQuality)
            put("note", w.note)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("wellbeing", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getWellbeing(date: LocalDate): ru.somena.core.Wellbeing? =
        readableDatabase.rawQuery(
            "SELECT energy, mood, sleep_quality, note FROM wellbeing WHERE date = ?",
            arrayOf(date.format(ISO_LOCAL_DATE))
        ).use { c ->
            if (!c.moveToFirst()) null else ru.somena.core.Wellbeing(
                date = date,
                energy = c.getInt(0),
                mood = c.getInt(1),
                sleepQuality = c.getInt(2),
                note = if (c.isNull(3)) null else c.getString(3),
            )
        }

    /** Всё Самочувствие по возрастанию даты: для графика и контекста Чата по данным. */
    fun allWellbeing(): List<ru.somena.core.Wellbeing> =
        readableDatabase.rawQuery(
            "SELECT date, energy, mood, sleep_quality, note FROM wellbeing ORDER BY date", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    ru.somena.core.Wellbeing(
                        date = LocalDate.parse(c.getString(0)),
                        energy = c.getInt(1),
                        mood = c.getInt(2),
                        sleepQuality = c.getInt(3),
                        note = if (c.isNull(4)) null else c.getString(4),
                    )
                )
            }
        }

    fun addChatMessage(role: String, content: String) {
        val values = android.content.ContentValues().apply {
            put("role", role)
            put("content", content)
            put("sent_at", System.currentTimeMillis())
        }
        writableDatabase.insert("chat_messages", null, values)
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
    }

    private companion object {
        const val SQL_CREATE_DAY_SLICES =
            "CREATE TABLE day_slices (" +
                "date TEXT PRIMARY KEY NOT NULL, " +
                "data TEXT NOT NULL, " +
                "updated_at INTEGER NOT NULL)"
        const val SQL_CREATE_WELLBEING =
            "CREATE TABLE IF NOT EXISTS wellbeing (" +
                "date TEXT PRIMARY KEY NOT NULL, " +
                "energy INTEGER NOT NULL, " +
                "mood INTEGER NOT NULL, " +
                "sleep_quality INTEGER NOT NULL, " +
                "note TEXT, " +
                "updated_at INTEGER NOT NULL)"
        const val SQL_CREATE_CHAT =
            "CREATE TABLE IF NOT EXISTS chat_messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "role TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "sent_at INTEGER NOT NULL)"
    }
}
