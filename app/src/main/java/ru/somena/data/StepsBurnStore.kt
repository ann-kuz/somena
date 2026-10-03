package ru.somena.data

import android.content.Context

/**
 * Переключатель «Расход от шагов» (Настройки → Данные, спека 0017): считать
 * показатель «Сожжено» от количества шагов вместо записей Источников.
 * По умолчанию выключен, хранится локально (ADR-0002).
 */
class StepsBurnStore(context: Context) {

    private val prefs = context.getSharedPreferences("somena", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean("burn_from_steps", false)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("burn_from_steps", value).apply()
    }
}
