package ru.somena.data

import android.content.Context
import ru.somena.core.TodayLayout
import ru.somena.core.TodayPlate
import ru.somena.core.todayLayout

/**
 * Порядок и скрытость плашек «Сегодня» (ADR-0002: настройки Пользователя живут
 * на телефоне и переживают перезапуск). Хранятся идентификаторы через запятую -
 * незнакомые при чтении выбрасываются, забытые дописываются в конец.
 */
class TodayLayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("somena", Context.MODE_PRIVATE)

    fun load(available: List<TodayPlate>): TodayLayout = todayLayout(
        orderIds = ids("today_order"),
        hiddenIds = ids("today_hidden").toSet(),
        available = available,
    )

    fun save(layout: TodayLayout) {
        prefs.edit()
            .putString("today_order", layout.order.joinToString(",") { it.id })
            .putString("today_hidden", layout.hidden.joinToString(",") { it.id })
            .apply()
    }

    private fun ids(key: String): List<String> =
        prefs.getString(key, null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
}
