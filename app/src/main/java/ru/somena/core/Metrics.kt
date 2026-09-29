package ru.somena.core

import java.time.LocalDate

/**
 * Метрики для графиков: ключи, подписи и единицы в одном месте.
 * Используются экраном «Графики» и Чатом по данным (графики, которые строит ИИ).
 */
enum class AiMetric(val key: String, val label: String, val unit: String) {
    STEPS("steps", "Шаги", "шаг."),
    SLEEP("sleep", "Сон", "ч"),
    BURNED("burned", "Сожжено", "ккал"),
    EATEN("eaten", "Съедено", "ккал"),
    PROTEIN("protein", "Белки", "г"),
    FAT("fat", "Жиры", "г"),
    CARBS("carbs", "Углеводы", "г"),
    WEIGHT("weight", "Вес", "кг"),
    BODY_FAT("body_fat", "Процент жира", "%"),
    BONE("bone", "Костная масса", "кг"),
    BMR("bmr", "Базовый расход", "ккал/дн"),
    DEFICIT("deficit", "Дефицит", "ккал"),
    ENERGY("energy", "Энергия", "из 10"),
    MOOD("mood", "Настроение", "из 10"),
    SLEEP_QUALITY("sleep_quality", "Качество сна", "из 10");

    companion object {
        fun byKey(key: String): AiMetric? = entries.firstOrNull { it.key == key }
    }
}

/** Метрики Самочувствия: на общем графике их ось фиксирована 0–10. */
val WELLBEING_METRICS = setOf(AiMetric.ENERGY, AiMetric.MOOD, AiMetric.SLEEP_QUALITY)

/**
 * Данные по дням в одном типе: срезы и Самочувствие по дате. Ходят вместе везде,
 * где метрики встречаются с календарём: графики, графики ИИ, контекст Чата по данным.
 * Самочувствие хранится списком: отметок в день бывает две, значение дня - последняя.
 */
data class DayData(
    val slicesByDate: Map<LocalDate, DaySlice> = emptyMap(),
    val wellbeingByDate: Map<LocalDate, List<Wellbeing>> = emptyMap(),
)

/**
 * Значения метрики по датам окна. Производные (сон в часах, дефицит) считаются здесь;
 * день без данных остаётся null и не превращается в ноль (спека 0001).
 *
 * Дефицит (спека 0009): сожжено минус съедено плюс базовый расход. Базовый расход
 * берётся с умных весов - последнее значение на дату (даты идут по возрастанию,
 * значение несётся вперёд); [defaultBmr] - ручное значение из Профиля, работает
 * только пока весы не дали своего.
 */
fun metricSeries(
    metric: AiMetric,
    dates: List<LocalDate>,
    data: DayData = DayData(),
    defaultBmr: Double? = null,
): List<Double?> {
    var lastBmr = defaultBmr
    return dates.map { d ->
        val s = data.slicesByDate[d]
        val w = data.wellbeingByDate[d]?.latestOfDay()
        s?.bmrKcal?.let { lastBmr = it }
        when (metric) {
            AiMetric.STEPS -> s?.steps?.toDouble()
            AiMetric.SLEEP -> s?.sleepMinutes?.let { it / 60.0 }
            AiMetric.BURNED -> s?.burnedKcal
            AiMetric.EATEN -> s?.eatenKcal
            AiMetric.PROTEIN -> s?.proteinG
            AiMetric.FAT -> s?.fatG
            AiMetric.CARBS -> s?.carbsG
            AiMetric.WEIGHT -> s?.weightKg
            AiMetric.BODY_FAT -> s?.bodyFatPct
            AiMetric.BONE -> s?.boneMassKg
            AiMetric.BMR -> s?.bmrKcal
            AiMetric.DEFICIT ->
                if (s?.burnedKcal != null && s.eatenKcal != null) {
                    s.burnedKcal - s.eatenKcal + (lastBmr ?: 0.0)
                } else {
                    null
                }
            AiMetric.ENERGY -> w?.energy?.toDouble()
            AiMetric.MOOD -> w?.mood?.toDouble()
            AiMetric.SLEEP_QUALITY -> w?.sleepQuality?.toDouble()
        }
    }
}
