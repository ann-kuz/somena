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
    BODY_FAT("Процент жира", "%", "body_fat"),
    BONE("Костная масса", "кг", "bone"),
    BMR("Базовый расход", "ккал/дн", "bmr"),
    WELLBEING("Самочувствие", "из 10", "wellbeing"),
}

/** Диапазоны ручного ввода - те же границы, что у Разбора таблицы (TableImport). */
private val MANUAL_RANGES = mapOf(
    ManualMetric.BURNED to (0.0..15000.0),
    ManualMetric.EATEN to (0.0..15000.0),
    ManualMetric.WEIGHT to (25.0..350.0),
    ManualMetric.STEPS to (0.0..100000.0),
    ManualMetric.SLEEP to (0.0..24.0),
    ManualMetric.BODY_FAT to (1.0..70.0),
    ManualMetric.BONE to (0.5..10.0),
    ManualMetric.BMR to (500.0..10000.0),
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
    ManualMetric.BODY_FAT -> "27.5"
    ManualMetric.BONE -> "2.6"
    ManualMetric.BMR -> "1450"
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
        ManualMetric.BODY_FAT -> ImportValues(bodyFatPct = value)
        ManualMetric.BONE -> ImportValues(boneMassKg = value)
        ManualMetric.BMR -> ImportValues(bmrKcal = value)
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
        bmrKcal = existing?.bmrKcal,
        steps = existing?.steps,
        burnedKcal = existing?.burnedKcal,
        sleepMinutes = existing?.sleepMinutes,
    )
    return ImportPreview(entries = listOf(ImportEntry(date, values, old)))
}

/** Причина негодности грамма БЖУ (0..2000, как у Разбора таблицы) или null; пустое поле допустимо. */
fun manualMacroProblem(raw: String): String? {
    if (raw.isBlank()) return null
    val v = parseManualNumber(raw) ?: return "введи число, например 90"
    if (v < 0.0 || v > 2000.0) return "от 0 до 2000 г"
    return null
}

/** Текущее значение категории в срезе: строка «сейчас в базе» до ввода. */
fun DaySlice?.manualOldValue(metric: ManualMetric): Double? {
    val s = this ?: return null
    return when (metric) {
        ManualMetric.BURNED -> s.burnedKcal
        ManualMetric.EATEN -> s.eatenKcal
        ManualMetric.WEIGHT -> s.weightKg
        ManualMetric.STEPS -> s.steps?.toDouble()
        ManualMetric.SLEEP -> s.sleepMinutes?.let { it / 60.0 }
        ManualMetric.BODY_FAT -> s.bodyFatPct
        ManualMetric.BONE -> s.boneMassKg
        ManualMetric.BMR -> s.bmrKcal
        ManualMetric.WELLBEING -> null
    }
}

/** Предпросмотр Съедено с БЖУ: калории обязательны, граммы - по желанию, пустое не ноль. */
fun manualEatenPreview(
    kcal: Double,
    proteinG: Double?,
    fatG: Double?,
    carbsG: Double?,
    date: LocalDate,
    existing: DaySlice?,
): ImportPreview {
    val values = ImportValues(
        eatenKcal = kcal,
        proteinG = proteinG,
        fatG = fatG,
        carbsG = carbsG,
    )
    val old = ImportValues(
        eatenKcal = existing?.eatenKcal,
        proteinG = existing?.proteinG,
        fatG = existing?.fatG,
        carbsG = existing?.carbsG,
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
