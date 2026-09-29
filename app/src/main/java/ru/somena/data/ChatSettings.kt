package ru.somena.data

import android.content.Context

/** Источник ИИ Чата по данным: Бэкенд-прокси владелицы (по умолчанию) или свой proxyapi. */
const val MODE_SERVER = "server"
const val MODE_DIRECT = "direct"

/**
 * Источник ИИ для Чата по данным (тикет 04/07): сервер-прокси владелицы по умолчанию
 * или свой ключ proxyapi напрямую (для друзей со своим ключом: сервер не нужен).
 * Пустые настройки не ломают остальное приложение: чат просто сообщает, что не настроен.
 */
class ChatSettings(context: Context) {
    private val prefs = context.getSharedPreferences("somena", Context.MODE_PRIVATE)

    var backendUrl: String
        get() = (prefs.getString(KEY_URL, null) ?: "").trim().trimEnd('/').ifEmpty { DEFAULT_URL }
        set(value) = prefs.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

    var appToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** Источник ИИ: Бэкенд-прокси (по умолчанию) или свой proxyapi напрямую. */
    var mode: String
        get() = if (prefs.getString(KEY_MODE, MODE_SERVER) == MODE_DIRECT) MODE_DIRECT else MODE_SERVER
        set(value) = prefs.edit().putString(KEY_MODE, if (value == MODE_DIRECT) MODE_DIRECT else MODE_SERVER).apply()

    /** Ключ proxyapi прямого режима: живёт только на этом телефоне, никуда не отправляется. */
    var proxyApiKey: String
        get() = (prefs.getString(KEY_PROXY_KEY, "") ?: "").trim()
        set(value) = prefs.edit().putString(KEY_PROXY_KEY, value.trim()).apply()

    /** Модель Быстрой Ступени прямого режима: каталог или свой id из списка proxyapi. */
    var proxyFastModel: String
        get() = prefs.getString(KEY_PROXY_FAST, null)?.trim().orEmpty().ifEmpty { ProxyModels.DEFAULT_FAST }
        set(value) = prefs.edit().putString(KEY_PROXY_FAST, value.trim()).apply()

    /** Модель Максимальной Ступени прямого режима. */
    var proxyMaxModel: String
        get() = prefs.getString(KEY_PROXY_MAX, null)?.trim().orEmpty().ifEmpty { ProxyModels.DEFAULT_MAX }
        set(value) = prefs.edit().putString(KEY_PROXY_MAX, value.trim()).apply()

    /**
     * Выбранная Ступень (спека 0004): fast или max. Бэкенд и прямой proxyapi сами знают,
     * какая модель за Ступенью стоит; приложение хранит только выбор. Чужое значение - fast.
     */
    var modelStep: String
        get() = if (prefs.getString(KEY_STEP, STEP_FAST) == STEP_MAX) STEP_MAX else STEP_FAST
        set(value) = prefs.edit().putString(KEY_STEP, if (value == STEP_MAX) STEP_MAX else STEP_FAST).apply()

    /** Чат готов к работе, когда задан секрет выбранного режима: токен или ключ proxyapi. */
    val isConfigured: Boolean
        get() = if (mode == MODE_DIRECT) proxyApiKey.isNotEmpty() else appToken.isNotEmpty()

    /** Подсказка «чат не настроен» словами выбранного режима: одно место для Чата и Медкарты. */
    val notConfiguredHint: String
        get() = if (mode == MODE_DIRECT) {
            "введи ключ proxyapi на вкладке «Ещё»"
        } else {
            "введи токен приложения на вкладке «Ещё»"
        }

    /** Конфигурация одного запроса для ChatClient. */
    fun transport(): ChatTransport = if (mode == MODE_DIRECT) {
        ChatTransport.Direct(proxyApiKey, proxyFastModel, proxyMaxModel)
    } else {
        ChatTransport.Server(backendUrl, appToken)
    }

    private companion object {
        const val KEY_URL = "chat_backend_url"
        const val KEY_TOKEN = "chat_app_token"
        const val KEY_MODE = "chat_mode"
        const val KEY_PROXY_KEY = "chat_proxy_key"
        const val KEY_PROXY_FAST = "chat_proxy_fast_model"
        const val KEY_PROXY_MAX = "chat_proxy_max_model"
        const val KEY_STEP = "chat_model_step"
        /** Адрес из README Бэкенда; владелица может поменять на свой при переезде сервера. */
        const val DEFAULT_URL = "http://77.239.99.15:8787"
    }
}

/**
 * Каталог моделей Ступеней для «Своего proxyapi»: популярные лёгкие и тяжёлые модели
 * из списка api.proxyapi.ru (сентябрь 2026). Свой id из списка можно вписать и мимо
 * каталога. У серверного режима каталога нет: там Ступень→модель решает Бэкенд.
 */
object ProxyModels {
    /** Лёгкие (Быстрая Ступень): дёшево и быстро на повседневные вопросы. */
    val FAST: List<String> = listOf("gpt-4.1-mini", "gpt-4o-mini", "gpt-5-mini")

    /** Тяжёлые (Максимальная Ступень): сложный анализ и длинные разборы, дороже и дольше. */
    val MAX: List<String> = listOf("gpt-5.1", "gpt-4.1", "gpt-5")

    const val DEFAULT_FAST = "gpt-4.1-mini"
    const val DEFAULT_MAX = "gpt-5.1"
}
