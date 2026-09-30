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

/** Скрепка вложений Чата по данным (спека 0004): та же причина - в core-наборе её нет. */
val AttachFileIcon: ImageVector by lazy {
    materialIcon(name = "Filled.AttachFile") {
        materialPath {
            moveTo(16.5f, 6.0f)
            verticalLineToRelative(11.5f)
            curveToRelative(0.0f, 2.21f, -1.79f, 4.0f, -4.0f, 4.0f)
            reflectiveCurveToRelative(-4.0f, -1.79f, -4.0f, -4.0f)
            verticalLineTo(5.0f)
            curveToRelative(0.0f, -1.38f, 1.12f, -2.5f, 2.5f, -2.5f)
            reflectiveCurveToRelative(2.5f, 1.12f, 2.5f, 2.5f)
            verticalLineToRelative(10.5f)
            curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
            reflectiveCurveToRelative(-1.0f, -0.45f, -1.0f, -1.0f)
            verticalLineTo(6.0f)
            horizontalLineTo(10.0f)
            verticalLineToRelative(9.5f)
            curveToRelative(0.0f, 1.38f, 1.12f, 2.5f, 2.5f, 2.5f)
            reflectiveCurveToRelative(2.5f, -1.12f, 2.5f, -2.5f)
            verticalLineTo(5.0f)
            curveToRelative(0.0f, -2.21f, -1.79f, -4.0f, -4.0f, -4.0f)
            reflectiveCurveToRelative(-4.0f, 1.79f, -4.0f, 4.0f)
            verticalLineToRelative(12.5f)
            curveToRelative(0.0f, 3.04f, 2.46f, 5.5f, 5.5f, 5.5f)
            reflectiveCurveToRelative(5.5f, -2.46f, 5.5f, -5.5f)
            verticalLineTo(6.0f)
            horizontalLineToRelative(-1.5f)
            close()
        }
    }
}

/** Папка Хранилища Медкарты (спека 0010): в core-наборе папки нет, а extended не тянем. */
val FolderIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Folder") {
        materialPath {
            moveTo(10.0f, 4.0f)
            horizontalLineTo(4.0f)
            curveTo(2.9f, 4.0f, 2.0f, 4.9f, 2.0f, 6.0f)
            verticalLineToRelative(12.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineTo(20.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(8.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            horizontalLineTo(12.0f)
            lineTo(10.0f, 4.0f)
            close()
        }
    }
}

/** Глаз скрытой плашки «Сегодня»: в core-наборе иконки нет, extended не тянем (спека 0001). */
val EyeIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Visibility") {
        materialPath {
            moveTo(12.0f, 4.5f)
            curveTo(7.0f, 4.5f, 2.73f, 7.61f, 1.0f, 12.0f)
            curveTo(2.73f, 16.39f, 7.0f, 19.5f, 12.0f, 19.5f)
            reflectiveCurveToRelative(9.27f, -3.11f, 11.0f, -7.5f)
            curveTo(21.27f, 7.61f, 17.0f, 4.5f, 12.0f, 4.5f)
            close()
            moveTo(12.0f, 17.0f)
            curveTo(9.24f, 17.0f, 7.0f, 14.76f, 7.0f, 12.0f)
            reflectiveCurveToRelative(2.24f, -5.0f, 5.0f, -5.0f)
            reflectiveCurveToRelative(5.0f, 2.24f, 5.0f, 5.0f)
            reflectiveCurveToRelative(-2.24f, 5.0f, -5.0f, 5.0f)
            close()
            moveTo(12.0f, 9.0f)
            curveTo(10.34f, 9.0f, 9.0f, 10.34f, 9.0f, 12.0f)
            reflectiveCurveToRelative(1.34f, 3.0f, 3.0f, 3.0f)
            reflectiveCurveToRelative(3.0f, -1.34f, 3.0f, -3.0f)
            reflectiveCurveToRelative(-1.34f, -3.0f, -3.0f, -3.0f)
            close()
        }
    }
}

/** Перечёркнутый глаз: пометка скрытой плашки в режиме переноса. */
val EyeOffIcon: ImageVector by lazy {
    materialIcon(name = "Filled.VisibilityOff") {
        materialPath {
            moveTo(12.0f, 7.0f)
            curveToRelative(2.76f, 0.0f, 5.0f, 2.24f, 5.0f, 5.0f)
            curveToRelative(0.0f, 0.65f, -0.13f, 1.26f, -0.36f, 1.83f)
            lineToRelative(2.92f, 2.92f)
            curveToRelative(1.51f, -1.26f, 2.7f, -2.89f, 3.43f, -4.75f)
            curveTo(21.27f, 8.61f, 17.0f, 5.5f, 12.0f, 5.5f)
            curveToRelative(-1.4f, 0.0f, -2.74f, 0.25f, -3.98f, 0.7f)
            lineToRelative(2.16f, 2.16f)
            curveTo(10.74f, 7.13f, 11.35f, 7.0f, 12.0f, 7.0f)
            close()
            moveTo(2.0f, 4.27f)
            lineToRelative(2.28f, 2.28f)
            lineToRelative(0.46f, 0.46f)
            curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1.0f, 12.0f)
            curveToRelative(1.73f, 4.39f, 6.0f, 7.5f, 11.0f, 7.5f)
            curveToRelative(1.55f, 0.0f, 3.03f, -0.3f, 4.38f, -0.84f)
            lineToRelative(0.42f, 0.42f)
            lineTo(19.73f, 22.0f)
            lineTo(21.0f, 20.73f)
            lineTo(3.27f, 3.0f)
            lineTo(2.0f, 4.27f)
            close()
            moveTo(7.53f, 9.8f)
            lineToRelative(1.55f, 1.55f)
            curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
            curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
            curveToRelative(0.22f, 0.0f, 0.44f, -0.03f, 0.65f, -0.08f)
            lineToRelative(1.55f, 1.55f)
            curveToRelative(-0.67f, 0.33f, -1.41f, 0.53f, -2.2f, 0.53f)
            curveToRelative(-2.76f, 0.0f, -5.0f, -2.24f, -5.0f, -5.0f)
            curveToRelative(0.0f, -0.79f, 0.2f, -1.53f, 0.53f, -2.2f)
            close()
            moveTo(11.84f, 9.02f)
            lineToRelative(3.15f, 3.15f)
            lineToRelative(0.02f, -0.16f)
            curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
            lineToRelative(-0.17f, 0.01f)
            close()
        }
    }
}
