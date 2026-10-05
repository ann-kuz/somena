package ru.somena.data

import android.content.Context

/**
 * Порог съеденного (Настройки → Данные, спека 0019): дни, где «Съедено» меньше
 * порога, считаются днями без еды. 0 - порог не задан (выключено), хранится
 * локально (ADR-0002).
 */
class EatenFloorStore(context: Context) {

    private val prefs = context.getSharedPreferences("somena", Context.MODE_PRIVATE)

    fun threshold(): Double = prefs.getFloat("eaten_floor_kcal", 0f).toDouble()

    fun setThreshold(value: Double) {
        prefs.edit().putFloat("eaten_floor_kcal", value.toFloat()).apply()
    }
}
