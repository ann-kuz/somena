package ru.somena.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * График, который строит ИИ в Чате по данным (тикет 07): модель выбирает заголовок,
 * метрики и окно в днях, а приложение рисует линии по локальным данным — числа из
 * ответа модели графику не доверяются.
 */
@Serializable
data class AiChartSpec(
    val title: String = "График",
    val days: Int = DEFAULT_DAYS,
    val metrics: List<String> = emptyList(),
) {
    /** Окно ограничено разумными пределами, чтобы модель не просила «всё с 2019 года». */
    val windowDays: Int get() = days.coerceIn(MIN_DAYS, MAX_DAYS)

    companion object {
        const val DEFAULT_DAYS = 30
        const val MIN_DAYS = 7
        const val MAX_DAYS = 180
    }
}

private val CHART_BLOCK = Regex("```chart\\s*([\\s\\S]*?)```")
private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Разбор ответа ИИ: блоки ```chart``` вырезаются из текста и возвращаются спецификациями.
 * Сломанный JSON или неизвестные метрики не ломают текст: блок просто выбрасывается.
 */
fun parseAiCharts(reply: String): Pair<String, List<AiChartSpec>> {
    val specs = mutableListOf<AiChartSpec>()
    val text = CHART_BLOCK.replace(reply) { match ->
        val spec = runCatching {
            lenientJson.decodeFromString<AiChartSpec>(match.groupValues[1])
        }.getOrNull()
        val metrics = spec?.metrics
            ?.mapNotNull { AiMetric.byKey(it.trim()) }
            ?.distinct()
            .orEmpty()
        if (spec != null && metrics.isNotEmpty()) {
            specs.add(spec.copy(days = spec.windowDays, metrics = metrics.map { it.key }))
        }
        ""
    }
    val clean = text.replace(Regex("\n{3,}"), "\n\n").trim()
    return clean to specs
}
