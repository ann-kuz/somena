package ru.somena.core

import java.time.LocalDate

/**
 * Ручное внесение показателей кнопкой «Внести данные» (меню: категория - дата - значение).
 * Чистая логика без модели и без сети: Быстрая ступень на просьбу «записала» отвечала
 * текстом без Предпросмотра (инцидент 29.09), поэтому кнопка вообще не зависит от ИИ.
 * Результат - тот же ImportPreview, что у Разбора таблицы и внесения из Чата:
 * запись только по явному «Записать».
 */
enum class ManualMetric(val label: String, val unitHint: String, val wireKey: String) {
    BURNED("Сожжено", "ккал", "burned_kcal"),
    EATEN("Съедено", "ккал", "eaten_kcal"),
    WEIGHT("Вес", "кг", "weight"),
    STEPS("Шаги", "шаг.", "steps"),
    SLEEP("Сон", "ч", "sleep_h"),
    WELLBEING("Самочувствие", "из 10", "wellbeing"),
}

/** Диапазоны ручного ввода - те же границы, что у Разбора таблицы (TableImport). */
private val MANUAL_RANGES = mapOf(
    ManualMetric.BURNED to (0.0..15000.0),
    ManualMetric.EATEN to (0.0..15000.0),
    ManualMetric.WEIGHT to (25.0..350.0),
    ManualMetric.STEPS to (0.0..100000.0),
    ManualMetric.SLEEP to (0.0..24.0),
)

/** Число из поля ввода: запятая и точка равноправны, всё неразборчивое - null. */
fun parseManualNumber(raw: String): Double? =
    raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

/** Причина, по которой значение не годится (для подписи под полем), или null. */
fun manualProblem(metric: ManualMetric, raw: String): String? {
    val v = parseManualNumber(raw) ?: return "введи число, например ${exampleFor(metric)}"
    val range = MANUAL_RANGES[metric] ?: return null
    if (metric == ManualMetric.STEPS && v != Math.round(v).toDouble()) return "шаги - целое число"
    if (v < range.start || v > range.endInclusive) {
        return "от ${fmt(range.start)} до ${fmt(range.endInclusive)} ${metric.unitHint}"
    }
    return null
}

private fun exampleFor(metric: ManualMetric): String = when (metric) {
    ManualMetric.BURNED, ManualMetric.EATEN -> "400"
    ManualMetric.WEIGHT -> "62.4"
    ManualMetric.STEPS -> "8000"
    ManualMetric.SLEEP -> "7.5"
    ManualMetric.WELLBEING -> "7"
}

/** Предпросмотр одного показателя среза: новое значение и то, что оно заменяет. */
fun manualSlicePreview(metric: ManualMetric, value: Double, date: LocalDate, existing: DaySlice?): ImportPreview {
    val values = when (metric) {
        ManualMetric.BURNED -> ImportValues(burnedKcal = value)
        ManualMetric.EATEN -> ImportValues(eatenKcal = value)
        ManualMetric.WEIGHT -> ImportValues(weightKg = value)
        ManualMetric.STEPS -> ImportValues(steps = value.toLong())
        ManualMetric.SLEEP -> ImportValues(sleepMinutes = (value * 60).let { Math.round(it) })
        ManualMetric.WELLBEING -> ImportValues()
    }
    val old = ImportValues(
        weightKg = existing?.weightKg,
        eatenKcal = existing?.eatenKcal,
        proteinG = existing?.proteinG,
        fatG = existing?.fatG,
        carbsG = existing?.carbsG,
        bodyFatPct = existing?.bodyFatPct,
        boneMassKg = existing?.boneMassKg,
        steps = existing?.steps,
        burnedKcal = existing?.burnedKcal,
        sleepMinutes = existing?.sleepMinutes,
    )
    return ImportPreview(entries = listOf(ImportEntry(date, values, old)))
}

/** Причина негодности шкал Самочувствия (все три обязательны, целые 0..10) или null. */
fun manualWellbeingProblem(energy: String, mood: String, sleepQuality: String): String? {
    fun scaleProblem(raw: String): Boolean {
        val v = parseManualNumber(raw) ?: return true
        return v != Math.round(v).toDouble() || v < Wellbeing.MIN || v > Wellbeing.MAX
    }
    return when {
        scaleProblem(energy) || scaleProblem(mood) || scaleProblem(sleepQuality) ->
            "три шкалы, целые от ${Wellbeing.MIN} до ${Wellbeing.MAX}"
        else -> null
    }
}

/** Предпросмотр отметки Самочувствия: полная строка трёх шкал. */
fun manualWellbeingPreview(
    date: LocalDate,
    energy: Int,
    mood: Int,
    sleepQuality: Int,
    existing: Wellbeing?,
): ImportPreview {
    val values = ImportWellbeing(energy, mood, sleepQuality)
    val old = ImportWellbeing(existing?.energy, existing?.mood, existing?.sleepQuality)
    return ImportPreview(wellbeing = listOf(ImportWellbeingEntry(date, values, old)))
}
