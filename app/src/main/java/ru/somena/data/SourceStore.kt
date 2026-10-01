package ru.somena.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.somena.core.SourceGroup

/**
 * Приоритет Источников (ADR-0010): ключ группы типов данных -> пакеты приложений
 * по убыванию приоритета. Пустая карта - учитывать все Источники. Хранится
 * локально (ADR-0002). Прежний формат «один выбранный пакет» переносится в
 * порядок из одного Источника: остальные достраиваются в конец как запас.
 */
class SourceStore(context: Context) {

    private val prefs = context.getSharedPreferences("somena", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Map<String, List<String>> {
        val raw = prefs.getString("source_choices", null) ?: return emptyMap()
        runCatching { json.decodeFromString<Map<String, List<String>>>(raw) }.getOrNull()?.let { return it }
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
            ?.mapValues { (_, pkg) -> listOf(pkg) }
            ?: emptyMap()
    }

    fun save(choices: Map<String, List<String>>) {
        prefs.edit().putString("source_choices", json.encodeToString(choices)).apply()
    }

    /** Задать порядок приоритета группы; пустой список возвращает группу к «все Источники». */
    fun choose(group: SourceGroup, priority: List<String>) {
        val choices = load().toMutableMap()
        if (priority.isEmpty()) choices.remove(group.key) else choices[group.key] = priority
        save(choices)
    }

    fun priorityOf(group: SourceGroup): List<String> = load()[group.key].orEmpty()
}
