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

/** Локальное хранилище Дневных срезов и Самочувствия (ADR-0002: данные живут на телефоне). */
class SliceDb(context: Context) : SQLiteOpenHelper(context, "somena.db", null, 2) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_DAY_SLICES)
        db.execSQL(SQL_CREATE_WELLBEING)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(SQL_CREATE_WELLBEING)
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
    }
}
