package ru.somena.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Пузырь чата в стиле Material filled: в core-наборе иконок такой нет, а тащить
 * material-icons-extended ради одной иконки нельзя (спека 0001: минимум зависимостей).
 */
val ChatBubbleIcon: ImageVector by lazy {
    materialIcon(name = "Filled.ChatBubble") {
        materialPath {
            moveTo(20.0f, 2.0f)
            lineTo(4.0f, 2.0f)
            curveTo(2.9f, 2.0f, 2.0f, 2.9f, 2.0f, 4.0f)
            lineTo(2.0f, 22.0f)
            lineTo(6.0f, 18.0f)
            lineTo(20.0f, 18.0f)
            curveTo(21.1f, 18.0f, 22.0f, 17.1f, 22.0f, 16.0f)
            lineTo(22.0f, 4.0f)
            curveTo(22.0f, 2.9f, 21.1f, 2.0f, 20.0f, 2.0f)
            close()
        }
    }
}
