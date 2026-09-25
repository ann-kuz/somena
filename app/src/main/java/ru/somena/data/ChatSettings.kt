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
        get() = (prefs.getString(KEY_URL, null) ?: "").trim().trimEnd('/').ifEmpty { DEFAULT_URL }
        set(value) = prefs.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

    var appToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /**
     * Выбранная Ступень (спека 0004): fast или max. Бэкенд сам знает, какая модель
     * за Ступенью стоит; приложение хранит только выбор. Чужое значение читается как fast.
     */
    var modelStep: String
        get() = if (prefs.getString(KEY_STEP, STEP_FAST) == STEP_MAX) STEP_MAX else STEP_FAST
        set(value) = prefs.edit().putString(KEY_STEP, if (value == STEP_MAX) STEP_MAX else STEP_FAST).apply()

    /** Чат готов к работе, когда токен задан: адрес имеет осмысленное значение по умолчанию. */
    val isConfigured: Boolean get() = appToken.isNotEmpty()

    /** Конфигурация одного запроса для ChatClient. */
    fun endpoint(): ChatEndpoint = ChatEndpoint(backendUrl, appToken)

    private companion object {
        const val KEY_URL = "chat_backend_url"
        const val KEY_TOKEN = "chat_app_token"
        const val KEY_STEP = "chat_model_step"
        /** Адрес из README Бэкенда; владелица может поменять на свой при переезде сервера. */
        const val DEFAULT_URL = "http://77.239.99.15:8787"
    }
}
