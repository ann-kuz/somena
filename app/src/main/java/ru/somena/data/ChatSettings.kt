package ru.somena.data

import android.content.Context

/**
 * Режимы Чата по данным без Бэкенда (Настройки → Чат): «Популярные API» - каталог
 * proxyapi с адресами и ценами; «Свой API» - адрес, ключ и модель целиком вручную.
 * Серверный режим владелицы из UI убран, но сохранённые настройки продолжают работать.
 */
const val MODE_POPULAR = "popular"
const val MODE_CUSTOM = "custom"

/** Внутренние режимы прежних версий: читаются, в интерфейсе не показываются. */
private const val MODE_SERVER = "server"
private const val MODE_DIRECT = "direct"

/**
 * Настройки Чата по данным: режим, ключ API и модели Ступеней. Пустые настройки не
 * ломают остальное приложение: чат просто сообщает, что не настроен.
 */
class ChatSettings(context: Context) {
    private val prefs = context.getSharedPreferences("somena", Context.MODE_PRIVATE)

    // --- Серверный режим (владелица): ключи ИИ живут на Бэкенде. ----------------

    var backendUrl: String
        get() = (prefs.getString(KEY_URL, null) ?: "").trim().trimEnd('/').ifEmpty { DEFAULT_URL }
        set(value) = prefs.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

    var appToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    // --- Режим -------------------------------------------------------------------

    /**
     * Режим Чата: [MODE_POPULAR], [MODE_CUSTOM] или серверный (легаси владелицы:
     * читается и работает, в настройках больше не выбирается). Прежний «прямой»
     * режим читается как «Свой API». Новый пользователь начинает с «Популярных API».
     */
    var mode: String
        get() = when (prefs.getString(KEY_MODE, null) ?: MODE_POPULAR) {
            MODE_SERVER -> MODE_SERVER
            MODE_CUSTOM -> MODE_CUSTOM
            MODE_DIRECT -> MODE_CUSTOM
            else -> MODE_POPULAR
        }
        set(value) = prefs.edit().putString(KEY_MODE, if (value == MODE_CUSTOM) MODE_CUSTOM else value).apply()

    /** Ключ API: живёт только на этом телефоне, в журнал не попадает никогда. */
    var proxyApiKey: String
        get() = (prefs.getString(KEY_PROXY_KEY, "") ?: "").trim()
        set(value) = prefs.edit().putString(KEY_PROXY_KEY, value.trim()).apply()

    // --- «Популярные API»: модель каталога приносит свой адрес и протокол. -------

    /** Модель Быстрой Ступени из каталога; незнакомый id читается как умолчание. */
    var popularFastId: String
        get() = ProxyModels.byId(prefs.getString(KEY_POPULAR_FAST, null) ?: "")?.id
            ?: ProxyModels.DEFAULT_FAST_ID
        set(value) = prefs.edit().putString(KEY_POPULAR_FAST, value.trim()).apply()

    /** Модель Максимальной Ступени из каталога. */
    var popularMaxId: String
        get() = ProxyModels.byId(prefs.getString(KEY_POPULAR_MAX, null) ?: "")?.id
            ?: ProxyModels.DEFAULT_MAX_ID
        set(value) = prefs.edit().putString(KEY_POPULAR_MAX, value.trim()).apply()

    // --- «Свой API»: всё вручную, обе Ступени на одной модели. -------------------

    /**
     * Формат запроса «Своего API»: OpenAI, Anthropic (Claude) или Google (Gemini).
     * Решает, как клиент собирает запрос; незнакомое сохранённое читается как OpenAI.
     */
    var customProtocol: String
        get() = ProxyModels.protocolOf(prefs.getString(KEY_CUSTOM_PROTOCOL, null))
        set(value) = prefs.edit().putString(KEY_CUSTOM_PROTOCOL, ProxyModels.protocolOf(value)).apply()

    /** Название модели у сервиса, например gpt-4o-mini или qwen/qwen3.8-27b. */
    var customModel: String
        get() = (prefs.getString(KEY_CUSTOM_MODEL, "") ?: "").trim()
        set(value) = prefs.edit().putString(KEY_CUSTOM_MODEL, value.trim()).apply()

    /**
     * Адрес API: у каждого формата своё умолчание proxyapi, подойдёт и сторонний
     * сервис того же формата (Qwen и DeepSeek у proxyapi - на /openrouter/v1).
     * Пустое значение - умолчание формата; случайно вставленный хвост пути
     * (/chat/completions, /messages или /models/…:generateContent) срезается,
     * приложение добавит его само.
     */
    var customBaseUrl: String
        get() = (prefs.getString(KEY_PROXY_URL, null) ?: "")
            .trim()
            .removeSuffix("/chat/completions")
            .removeSuffix("/messages")
            .substringBefore("/models/")
            .removeSuffix(":generateContent")
            .trim().trimEnd('/')
            .ifEmpty { ProxyModels.defaultUrlFor(customProtocol) }
        set(value) = prefs.edit().putString(KEY_PROXY_URL, value.trim()).apply()

    // --- Ступень -----------------------------------------------------------------

    /**
     * Выбранная Ступень (спека 0004): fast или max. Сервис сам знает, какая модель
     * за Ступенью стоит; приложение хранит только выбор. Чужое значение - fast.
     */
    var modelStep: String
        get() = if (prefs.getString(KEY_STEP, STEP_FAST) == STEP_MAX) STEP_MAX else STEP_FAST
        set(value) = prefs.edit().putString(KEY_STEP, if (value == STEP_MAX) STEP_MAX else STEP_FAST).apply()

    // --- Готовность и транспорт ----------------------------------------------------

    /** Чат готов к работе, когда задан секрет режима (и модель у «Своего API»). */
    val isConfigured: Boolean
        get() = when (mode) {
            MODE_SERVER -> appToken.isNotEmpty()
            MODE_CUSTOM -> proxyApiKey.isNotEmpty() && customModel.isNotEmpty()
            else -> proxyApiKey.isNotEmpty()
        }

    /** Подсказка «чат не настроен» словами режима: одно место для Чата и Медкарты. */
    val notConfiguredHint: String
        get() = if (mode == MODE_SERVER) {
            "введи токен приложения в Настройках → Чат"
        } else {
            "введи ключ API в Настройках → Чат"
        }

    /** Конфигурация одного запроса для ChatClient. */
    fun transport(): ChatTransport = when (mode) {
        MODE_SERVER -> ChatTransport.Server(backendUrl, appToken)
        MODE_CUSTOM -> ChatTransport.Direct(
            proxyApiKey,
            ChatTransport.ModelTarget(customModel, customBaseUrl, customProtocol),
            ChatTransport.ModelTarget(customModel, customBaseUrl, customProtocol),
        )
        else -> {
            val fast = ProxyModels.byId(popularFastId) ?: error("нет модели $popularFastId в каталоге")
            val max = ProxyModels.byId(popularMaxId) ?: error("нет модели $popularMaxId в каталоге")
            ChatTransport.Direct(
                proxyApiKey,
                ChatTransport.ModelTarget(fast.id, fast.baseUrl, fast.protocol),
                ChatTransport.ModelTarget(max.id, max.baseUrl, max.protocol),
            )
        }
    }

    private companion object {
        const val KEY_URL = "chat_backend_url"
        const val KEY_TOKEN = "chat_app_token"
        const val KEY_MODE = "chat_mode"
        const val KEY_PROXY_KEY = "chat_proxy_key"
        const val KEY_PROXY_URL = "chat_proxy_base_url"
        const val KEY_POPULAR_FAST = "chat_popular_fast_id"
        const val KEY_POPULAR_MAX = "chat_popular_max_id"
        const val KEY_CUSTOM_PROTOCOL = "chat_custom_protocol"
        const val KEY_CUSTOM_MODEL = "chat_custom_model"
        const val KEY_STEP = "chat_model_step"
        /** Адрес из README Бэкенда; владелица может поменять на свой при переезде сервера. */
        const val DEFAULT_URL = "http://77.239.99.15:8787"
    }
}
