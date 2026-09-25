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
    date = date.format(ISO_LOCAL_DATE), steps, sleepMinutes, burnedKcal, eatenKcal,
    proteinG, fatG, carbsG, weightKg, bodyFatPct, boneMassKg, bmrKcal,
)

private fun SliceDto.toDomain() = DaySlice(
    date = LocalDate.parse(date), steps, sleepMinutes, burnedKcal, eatenKcal,
    proteinG, fatG, carbsG, weightKg, bodyFatPct, boneMassKg, bmrKcal,
)

/** Локальное хранилище Дневных срезов (ADR-0002: данные живут на телефоне). */
class SliceDb(context: Context) : SQLiteOpenHelper(context, "somena.db", null, 1) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE day_slices (" +
                "date TEXT PRIMARY KEY NOT NULL, " +
                "data TEXT NOT NULL, " +
                "updated_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
}
