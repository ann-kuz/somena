package ru.somena.data

import android.content.Context
import ru.somena.core.AppLogCore

/**
 * Журнал приложения (Настройки → Отладка): запросы Чата, запись базы и ошибки
 * приложения в одном месте. Хранится 7 дней, более ранние части стираются при каждой
 * записи; объём ограничен хвостом. Ключи и токены сюда не пишутся никогда.
 */
object AppLog {
    private const val KEY = "app_log"
    private const val MAX_CHARS = 20_000

    /** Теги строк журнала: единые для всех точек записи. */
    const val CHAT = "Чат"
    const val DB = "БД"
    const val ERROR = "Ошибка"
    const val HC = "HC"

    fun append(context: Context, tag: String, line: String) {
        val prefs = context.getSharedPreferences("somena", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val updated = (raw(context) + "\n" + AppLogCore.encode(now, tag, line)).trim('\n')
        prefs.edit()
            .putString(KEY, AppLogCore.cap(AppLogCore.trimOld(updated, now), MAX_CHARS))
            .apply()
    }

    /** Журнал для экрана и копирования: строки «dd.MM HH:mm:ss [тег] сообщение». */
    fun get(context: Context): String = AppLogCore.displayAll(raw(context))

    fun clear(context: Context) {
        context.getSharedPreferences("somena", Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun raw(context: Context): String =
        context.getSharedPreferences("somena", Context.MODE_PRIVATE).getString(KEY, "") ?: ""
}
