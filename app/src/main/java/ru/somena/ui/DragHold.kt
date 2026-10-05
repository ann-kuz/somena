package ru.somena.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Захват строки удержанием с коротким порогом (спека 0013): системное долгое
 * нажатие (~0,4 с) заменено своими 0,2 с, чтобы палец не ждал. Прокрутка списка
 * по-прежнему выигрывает у захвата: движение пальца дальше порога касания или
 * потреблённое списком касание отменяют ждущий захват. Порог - константа, не
 * настройка Пользователя. Живёт в дизайн-системе: так его делят перенос плашек
 * «Сегодня» и порядок Источников на экране показателя (спека 0018).
 */
const val REORDER_HOLD_MILLIS = 200L

/** Ожидание захвата: null - палец ушёл/сорвался, иначе - изменение в момент захвата. */
internal suspend fun AwaitPointerEventScope.awaitHoldOrCancellation(
    down: PointerInputChange,
    holdMillis: Long,
): PointerInputChange? {
    var last: PointerInputChange = down
    // null от withTimeoutOrNull - время вышло при живом пальце (удержание!);
    // false внутри - палец ушёл, движение съедено прокруткой или касание чужое.
    val held: Boolean? = withTimeoutOrNull(holdMillis) {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            last = change
            val consumedByOthers = event.changes.any { it.isConsumed && it.id != down.id }
            if (!change.pressed || change.isConsumed || consumedByOthers) break
        }
        false
    }
    return if (held == null) last else null
}

/** Перетаскивание после короткого удержания: образец detectDragGesturesAfterLongPress. */
internal suspend fun PointerInputScope.detectDragAfterHold(
    holdMillis: Long = REORDER_HOLD_MILLIS,
    onDragStart: () -> Unit,
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val held = awaitHoldOrCancellation(down, holdMillis)
    if (held == null) return@awaitEachGesture
    onDragStart()
    var drag: PointerInputChange? = held
    while (drag != null) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == held.id }
        if (change == null) {
            // Палец исчез без поднятия (система отобрала касание).
            drag = null
            onDragCancel()
        } else if (!change.pressed) {
            drag = null
            onDragEnd()
        } else {
            drag = change
            val delta = change.positionChangeIgnoreConsumed()
            if (delta != Offset.Zero) {
                onDrag(change, delta)
                change.consume()
            }
        }
    }
}
