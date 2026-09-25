package ru.somena.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.somena.core.Profile

/** Профиль хранится локально (ADR-0002) и переживает перезапуск. */
class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("somena", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Profile =
        prefs.getString("profile", null)?.let { runCatching { json.decodeFromString<Profile>(it) }.getOrNull() }
            ?: Profile()

    fun save(profile: Profile) {
        prefs.edit().putString("profile", json.encodeToString(profile)).apply()
    }
}
