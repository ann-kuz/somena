package ru.somena.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.somena.core.SourceGroup

/**
 * Выбор Источников (ADR-0010): ключ группы типов данных -> пакет приложения.
 * Пустая карта - учитывать все Источники. Хранится локально (ADR-0002).
 */
class SourceStore(context: Context) {

    private val prefs = context.getSharedPreferences("somena", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Map<String, String> =
        prefs.getString("source_choices", null)
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            ?: emptyMap()

    fun save(choices: Map<String, String>) {
        prefs.edit().putString("source_choices", json.encodeToString(choices)).apply()
    }

    /** Выбрать Источник группы; null возвращает группу к «все Источники». */
    fun choose(group: SourceGroup, packageName: String?) {
        val choices = load().toMutableMap()
        if (packageName == null) choices.remove(group.key) else choices[group.key] = packageName
        save(choices)
    }

    fun chosenPackage(group: SourceGroup): String? = load()[group.key]
}
