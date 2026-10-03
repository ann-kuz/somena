package ru.somena.core

import java.time.LocalDate

/**
 * Расход от шагов (спека 0017): переключатель «Настройки → Данные» заменяет
 * показатель «Сожжено» расчётом от количества шагов - как обычная ходьба по
 * ровной местности. Калории минуты ходьбы - МЕТ 3,5 (умеренный темп, компендиум
 * физической активности) от личного обмена: базовый расход считается формулой
 * Mifflin-St Jeor от веса, роста, возраста и пола. Возраст берётся полных лет
 * на день расчёта, рост и пол - из Профиля; пустой рост берётся средним
 * (168 см), пустой пол - женским: к ним формула малочувствительна. Шаги
 * переводятся в минуты темпом 100 шагов/мин.
 */
object StepsBurn {

    /** МЕТ обычной ходьбы по ровной местности (умеренный темп, около 5 км/ч). */
    const val MET = 3.5

    /** Темп обычной ходьбы: шагов в минуту. */
    const val CADENCE = 100.0

    /** Средний рост, пока в Профиле пусто: слабо влияет на результат. */
    const val DEFAULT_HEIGHT_CM = 168.0

    /**
     * Ккал ходьбы от шагов одного дня. null - дата рождения не заполнена,
     * возраст неизвестен: день остаётся на записанном значении Источников.
     */
    fun kcal(steps: Long, weightKg: Double, profile: Profile, on: LocalDate): Double? {
        val age = profile.ageYears(on) ?: return null
        val heightCm = profile.heightCm?.toDouble() ?: DEFAULT_HEIGHT_CM
        val bmr = 10.0 * weightKg +
            6.25 * heightCm -
            5.0 * age +
            (if (profile.sex == Sex.MALE) 5.0 else -161.0)
        return Math.round(bmr * MET * steps.toDouble() / (24.0 * 60.0 * CADENCE)).toDouble()
    }
}

/**
 * Срезы с Расходом от шагов (переключатель включён): вес дня - последнее
 * взвешивание на этот день и раньше, несётся вперёд до нового взвешивания.
 * День без шагов, день без известного веса вовсе или без даты рождения в
 * Профиле оставляет записанный расход - прочитанный из Health Connect или
 * внесённый вручную.
 */
fun List<DaySlice>.withStepsBurn(profile: Profile): List<DaySlice> {
    var lastWeight: Double? = null
    return sortedBy { it.date }.map { s ->
        s.weightKg?.let { lastWeight = it }
        val weight = lastWeight
        val burn = if (s.steps != null && weight != null) {
            StepsBurn.kcal(s.steps, weight, profile, s.date)
        } else {
            null
        }
        if (burn == null) s else s.copy(burnedKcal = burn)
    }
}
