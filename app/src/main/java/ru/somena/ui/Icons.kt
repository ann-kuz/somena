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

/*
 * Иконки групп Источников для списка показателей (спека 0018), стиль Material
 * filled, рисованы вручную: extended ради шести иконок не тянем (спека 0001).
 */

/** Шаги: лестница - «ступени шагов». */
val StepsIcon: ImageVector by lazy {
    materialIcon(name = "Filled.StepsStaircase") {
        materialPath {
            moveTo(3.5f, 20.0f)
            verticalLineToRelative(-1.6f)
            horizontalLineTo(8.0f)
            verticalLineToRelative(-4.0f)
            horizontalLineTo(12.5f)
            verticalLineToRelative(-4.0f)
            horizontalLineTo(17.0f)
            verticalLineToRelative(-4.0f)
            horizontalLineTo(20.5f)
            verticalLineTo(20.0f)
            close()
        }
    }
}

/** Сон: месяц. */
val SleepIcon: ImageVector by lazy {
    materialIcon(name = "Filled.BedtimeCrescent") {
        materialPath {
            moveTo(10.6f, 2.4f)
            curveTo(6.1f, 3.4f, 2.8f, 7.4f, 2.8f, 12.2f)
            curveTo(2.8f, 17.6f, 7.2f, 22.0f, 12.6f, 22.0f)
            curveTo(15.7f, 22.0f, 18.4f, 20.6f, 20.3f, 18.4f)
            curveTo(19.5f, 18.5f, 18.7f, 18.6f, 17.9f, 18.6f)
            curveTo(12.0f, 18.6f, 7.2f, 13.8f, 7.2f, 7.9f)
            curveTo(7.2f, 5.8f, 7.8f, 4.0f, 10.6f, 2.4f)
            close()
        }
    }
}

/** Расход: язык пламени. */
val BurnIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Flame") {
        materialPath {
            moveTo(12.0f, 2.0f)
            curveTo(10.6f, 4.7f, 9.2f, 6.6f, 8.2f, 8.7f)
            curveTo(7.2f, 10.8f, 6.7f, 12.4f, 6.7f, 14.1f)
            curveTo(6.7f, 17.1f, 9.1f, 19.5f, 12.0f, 19.5f)
            curveTo(14.9f, 19.5f, 17.3f, 17.1f, 17.3f, 14.1f)
            curveTo(17.3f, 12.8f, 16.8f, 11.6f, 16.0f, 10.3f)
            curveTo(15.3f, 11.4f, 14.6f, 12.0f, 13.7f, 12.4f)
            curveTo(14.0f, 10.2f, 13.4f, 6.9f, 12.0f, 2.0f)
            close()
        }
    }
}

/** Пульс: сердце. */
val PulseIcon: ImageVector by lazy {
    materialIcon(name = "Filled.FavoriteHeart") {
        materialPath {
            moveTo(12.0f, 21.35f)
            lineToRelative(-1.45f, -1.32f)
            curveTo(5.4f, 15.36f, 2.0f, 12.28f, 2.0f, 8.5f)
            curveTo(2.0f, 5.42f, 4.42f, 3.0f, 7.5f, 3.0f)
            curveTo(9.24f, 3.0f, 10.91f, 3.81f, 12.0f, 5.09f)
            curveTo(13.09f, 3.81f, 14.76f, 3.0f, 16.5f, 3.0f)
            curveTo(19.58f, 3.0f, 22.0f, 5.42f, 22.0f, 8.5f)
            curveTo(22.0f, 12.28f, 18.6f, 15.36f, 13.45f, 20.03f)
            lineTo(12.0f, 21.35f)
            close()
        }
    }
}

/** Еда: вилка и нож. */
val FoodIcon: ImageVector by lazy {
    materialIcon(name = "Filled.RestaurantForkKnife") {
        materialPath {
            // Вилка: три зубца, переходящие в ручку.
            moveTo(7.0f, 2.0f)
            horizontalLineTo(7.7f)
            verticalLineTo(8.4f)
            horizontalLineTo(8.3f)
            verticalLineTo(2.0f)
            horizontalLineTo(9.0f)
            verticalLineTo(8.4f)
            horizontalLineTo(9.6f)
            verticalLineTo(2.0f)
            horizontalLineTo(10.3f)
            verticalLineTo(8.9f)
            curveTo(10.3f, 10.0f, 9.6f, 10.9f, 8.65f, 11.2f)
            verticalLineTo(22.0f)
            horizontalLineTo(7.65f)
            verticalLineTo(11.2f)
            curveTo(7.2f, 11.05f, 7.0f, 10.5f, 7.0f, 9.9f)
            close()
            // Нож: прямой обушок, изогнутое лезвие, узкая ручка.
            moveTo(16.2f, 2.0f)
            curveTo(18.0f, 3.9f, 18.9f, 6.0f, 18.9f, 8.2f)
            curveTo(18.9f, 10.1f, 17.7f, 11.4f, 16.1f, 11.7f)
            verticalLineTo(22.0f)
            horizontalLineTo(14.9f)
            verticalLineTo(2.0f)
            close()
        }
    }
}

/** Вес и состав тела: напольные весы со стрелкой. */
val BodyIcon: ImageVector by lazy {
    materialIcon(name = "Filled.MonitorScale") {
        materialPath {
            // Корпус со скруглениями.
            moveTo(9.4f, 4.6f)
            horizontalLineTo(14.6f)
            curveTo(16.26f, 4.6f, 17.6f, 5.94f, 17.6f, 7.6f)
            verticalLineTo(16.4f)
            curveTo(17.6f, 18.06f, 16.26f, 19.4f, 14.6f, 19.4f)
            horizontalLineTo(9.4f)
            curveTo(7.74f, 19.4f, 6.4f, 18.06f, 6.4f, 16.4f)
            verticalLineTo(7.6f)
            curveTo(6.4f, 5.94f, 7.74f, 4.6f, 9.4f, 4.6f)
            close()
            // Стрелка циферблата.
            moveTo(11.9f, 15.2f)
            lineTo(14.8f, 9.6f)
            lineTo(15.6f, 10.2f)
            lineTo(13.3f, 15.7f)
            close()
        }
    }
}
