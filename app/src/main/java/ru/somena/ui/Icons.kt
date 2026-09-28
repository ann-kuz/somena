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
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
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
