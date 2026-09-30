package ru.somena.data

/**
 * Каталог «Популярных API»: модели proxyapi с их адресами и протоколами запроса.
 * proxyapi держит провайдеров на разных адресах: семейство GPT - на /openai/v1,
 * Claude - на /anthropic/v1 в родном формате Anthropic, Gemini - на /google/v1beta
 * в родном формате Google, прочие модели (Qwen, DeepSeek, Grok и другие) - на
 * /openrouter/v1 в OpenAI-формате (проверено живыми вызовами, 30.09.2026;
 * /openai/v1 чужие модели не пускает). Протокол решает клиент: [PROTOCOL_OPENAI] -
 * POST {адрес}/chat/completions, [PROTOCOL_ANTHROPIC] - POST {адрес}/messages,
 * [PROTOCOL_GEMINI] - POST {адрес}/models/{модель}:generateContent.
 */
const val PROTOCOL_OPENAI = "openai"
const val PROTOCOL_ANTHROPIC = "anthropic"
const val PROTOCOL_GEMINI = "gemini"

/** Модель каталога: id у proxyapi, цена в ₽ за 1М токенов (ввод/вывод), адрес и протокол. */
data class CatalogModel(
    val id: String,
    val title: String,
    val inputRub: Double,
    val outputRub: Double,
    val protocol: String,
    val baseUrl: String,
)

/**
 * Пять лёгких и пять тяжёлых моделей (сентябрь 2026), дешёвые сверху. Цены - за
 * миллион токенов: лёгкая модель на повседневных вопросах тратит копейки, тяжёлая -
 * заметно дороже, зато разбирает сложный анализ.
 */
object ProxyModels {

    const val OPENAI_URL = "https://api.proxyapi.ru/openai/v1"
    const val ANTHROPIC_URL = "https://api.proxyapi.ru/anthropic/v1"
    const val GEMINI_URL = "https://api.proxyapi.ru/google/v1beta"
    const val OPENROUTER_URL = "https://api.proxyapi.ru/openrouter/v1"

    /** Адрес proxyapi по протоколу: умолчание и подсказка адреса в «Своём API». */
    fun defaultUrlFor(protocol: String): String = when (protocol) {
        PROTOCOL_ANTHROPIC -> ANTHROPIC_URL
        PROTOCOL_GEMINI -> GEMINI_URL
        else -> OPENAI_URL
    }

    /** Известный протокол по сохранённому значению: незнакомое читается как OpenAI. */
    fun protocolOf(stored: String?): String = when (stored) {
        PROTOCOL_ANTHROPIC -> PROTOCOL_ANTHROPIC
        PROTOCOL_GEMINI -> PROTOCOL_GEMINI
        else -> PROTOCOL_OPENAI
    }

    private fun gpt(id: String, title: String, input: Double, output: Double) =
        CatalogModel(id, title, input, output, PROTOCOL_OPENAI, OPENAI_URL)

    private fun claude(id: String, title: String, input: Double, output: Double) =
        CatalogModel(id, title, input, output, PROTOCOL_ANTHROPIC, ANTHROPIC_URL)

    /** Лёгкие (Быстрая Ступень): повседневные вопросы, копейки за ответ. */
    val FAST: List<CatalogModel> = listOf(
        gpt("gpt-5-nano", "GPT-5 Nano", 13.0, 104.0),
        gpt("gpt-4.1-nano", "GPT-4.1 Nano", 26.0, 104.0),
        gpt("gpt-4o-mini", "GPT-4o Mini", 39.0, 155.0),
        gpt("gpt-5-mini", "GPT-5 Mini", 65.0, 516.0),
        claude("claude-haiku-4-5-20251001", "Claude Haiku 4.5", 295.0, 1474.0),
    )

    /** Тяжёлые (Максимальная Ступень): сложный анализ и длинные разборы. */
    val MAX: List<CatalogModel> = listOf(
        gpt("gpt-5.1", "GPT-5.1", 323.0, 2577.0),
        gpt("gpt-6-sol", "GPT-6 Sol", 420.0, 2100.0),
        claude("claude-sonnet-5-5", "Claude Sonnet 5.5", 500.0, 2500.0),
        claude("claude-opus-5-5", "Claude Opus 5.5", 840.0, 4200.0),
        gpt("gpt-5.5", "GPT-5.5", 1520.0, 9100.0),
    )

    fun byId(id: String): CatalogModel? = (FAST + MAX).firstOrNull { it.id == id }

    /** Умолчания «Популярных API»: классическая лёгкая и знакомая тяжёлая. */
    const val DEFAULT_FAST_ID = "gpt-4o-mini"
    const val DEFAULT_MAX_ID = "gpt-5.1"

    /** Подпись цены для пилюли каталога: «13 ₽ за 1М» (токенов). */
    fun priceLabel(m: CatalogModel): String = "${shortPriceLabel(m)} за 1М"

    /** Короткая подпись для пилюли: «13 ₽» (за миллион токенов ввода), без локали. */
    fun shortPriceLabel(m: CatalogModel): String = "${rubLabel(m.inputRub)} ₽"

    /** Тысячи с пробелом: «1 520». Дробная цена - с запятой, как в каталоге. */
    private fun rubLabel(v: Double): String {
        if (v != v.toLong().toDouble()) {
            return String.format(java.util.Locale.US, "%.1f", v).replace('.', ',')
        }
        val s = v.toLong().toString()
        return if (s.length > 3) s.dropLast(3) + " " + s.takeLast(3) else s
    }
}
