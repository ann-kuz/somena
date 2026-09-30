package ru.somena.data

import android.content.Context
import ru.somena.core.LayoutCard
import ru.somena.core.CardLayout
import ru.somena.core.cardLayout

/**
 * Порядок и скрытость карточек (ADR-0002: настройки Пользователя живут на телефоне
 * и переживают перезапуск). Одно хранилище на оба экрана, различается ключами.
 * Хранятся идентификаторы через запятую: незнакомые при чтении выбрасываются,
 * забытые дописываются в конец.
 */
class CardLayoutStore<T : LayoutCard>(
    context: Context,
    private val keyOrder: String,
    private val keyHidden: String,
    private val byId: (String) -> T?,
) {
    private val prefs = context.getSharedPreferences("somena", Context.MODE_PRIVATE)

    fun load(available: List<T>): CardLayout<T> = cardLayout(
        orderIds = ids(keyOrder),
        hiddenIds = ids(keyHidden).toSet(),
        available = available,
        byId = byId,
    )

    fun save(layout: CardLayout<T>) {
        prefs.edit()
            .putString(keyOrder, layout.order.joinToString(",") { it.id })
            .putString(keyHidden, layout.hidden.joinToString(",") { it.id })
            .apply()
    }

    private fun ids(key: String): List<String> =
        prefs.getString(key, null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
}
