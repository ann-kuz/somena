package ru.somena.data

import android.content.Context
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Журнал Чата по данным для вкладки «Отладка HC»: последние события запросов к Бэкенду
 * с таймстампами. Хранится в prefs и переживает перезапуск, объём ограничен хвостом:
 * для диагностики важны свежие строки. Токен сюда не пишется никогда.
 */
object ChatLog {
    private const val KEY = "chat_log"
    private const val MAX_CHARS = 8000
    private val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")

    fun append(context: Context, line: String) {
        val prefs = context.getSharedPreferences("somena", Context.MODE_PRIVATE)
        val stamp = LocalDateTime.now().format(fmt)
        val updated = (get(context) + "\n$stamp $line").trim('\n')
        prefs.edit().putString(KEY, updated.takeLast(MAX_CHARS)).apply()
    }

    fun get(context: Context): String =
        context.getSharedPreferences("somena", Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun clear(context: Context) {
        context.getSharedPreferences("somena", Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
