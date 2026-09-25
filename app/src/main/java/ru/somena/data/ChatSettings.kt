package ru.somena.data

import android.content.Context

/**
 * Настройки Чата по данным: адрес Бэкенда и токен приложения (тикет 04/07).
 * Приложение знает только их; ключи ИИ живут на Бэкенде. Пустые настройки не ломают
 * остальное приложение: чат просто сообщает, что не настроен.
 */
class ChatSettings(context: Context) {
    private val prefs = context.getSharedPreferences("somena", Context.MODE_PRIVATE)

    var backendUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL)?.trimEnd('/') ?: DEFAULT_URL
        set(value) = prefs.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

    var appToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** Чат готов к работе, когда токен задан: адрес имеет осмысленное значение по умолчанию. */
    val isConfigured: Boolean get() = appToken.isNotEmpty()

    private companion object {
        const val KEY_URL = "chat_backend_url"
        const val KEY_TOKEN = "chat_app_token"
        /** Адрес из README Бэкенда; владелица может поменять на свой при переезде сервера. */
        const val DEFAULT_URL = "http://77.239.99.15:8787"
    }
}
