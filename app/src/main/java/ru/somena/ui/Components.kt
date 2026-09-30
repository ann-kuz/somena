package ru.somena.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val NavDim = Color(0xFF837FA3)

/** Стеклянная карточка: полупрозрачная поверхность с тонким бордером. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(androidx.compose.foundation.BorderStroke(1.dp, CardBorder), RoundedCornerShape(20.dp))
            .padding(padding),
        content = content,
    )
}

/** Заголовок внутри карточки: маленькая капсула-точка со свечением + название капсом. */
@Composable
fun CardLabel(text: String, accent: Color = Violet) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier
                .neonHalo(accent, cornerRadius = 24.dp, glow = 3.dp, alpha = 0.5f)
                .size(7.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.4.sp,
            color = TextMuted,
        )
    }
}

/**
 * Карточка показателя: заголовок, крупная цифра, единица, подстрочник.
 * Устаревшее значение (staleOn - день получения, не сегодня) показывается
 * приглушённым с подписью «на ДД.ММ»; свежее подсвечено как обычно.
 */
@Composable
fun MetricCard(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    accent: Color = Violet,
    unit: String = "",
    sub: String? = null,
    staleOn: java.time.LocalDate? = null,
) {
    GlassCard(modifier) {
        CardLabel(label, accent)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                value ?: "—",
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = if (value == null || staleOn != null) TextMuted else Color.Unspecified,
            )
            if (unit.isNotEmpty() && value != null) {
                Text(
                    unit,
                    fontSize = 13.sp,
                    color = TextMuted,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = TextMuted) }
        if (value != null && staleOn != null) {
            Text(
                "на ${staleOn.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM"))}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}

/** Заголовок экрана: крупное имя и приглушённый подзаголовок. */
@Composable
fun ScreenHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )
        Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Неоновое стекло для интерактивных и акцентных поверхностей: полупрозрачный градиент
 * фиолет→синева, светящийся бордер, рисованное свечение (neonHalo). Выключенное
 * (active = false) гаснет. Радиус больше половины меньшей стороны даёт круг или пилюлю.
 * Новые элементы собираются из него, а не повторяют рецепт руками.
 */
fun Modifier.neonSurface(active: Boolean, cornerRadius: Dp, glow: Dp = 10.dp): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    val fill =
        if (active) Brush.linearGradient(listOf(Violet.copy(alpha = 0.30f), Indigo.copy(alpha = 0.16f)))
        else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.05f)))
    val stroke =
        if (active) androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Violet.copy(alpha = 0.35f))),
        )
        else androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    return this
        .neonHalo(Violet, cornerRadius = cornerRadius, glow = if (active) glow else 0.dp)
        .clip(shape)
        .background(fill)
        .border(stroke, shape)
}

/** Мини-график последних дней для главной карточки. */
@Composable
fun Sparkline(values: List<Double?>, modifier: Modifier = Modifier, color: Color = Violet) {
    Canvas(modifier) {
        val pts = values.mapIndexedNotNull { i, v -> v?.let { i to it } }
        if (pts.size < 2) return@Canvas
        val lo = pts.minOf { it.second }
        val hi = pts.maxOf { it.second }
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun px(i: Int) = size.width * i / (values.size - 1)
        fun py(v: Double) = 5f + (size.height - 10f) * (1f - ((v - lo) / span).toFloat())

        val line = Path()
        pts.forEachIndexed { k, (i, v) ->
            if (k == 0) line.moveTo(px(i), py(v)) else line.lineTo(px(i), py(v))
        }
        val area = Path().apply {
            moveTo(px(pts.first().first), size.height)
            pts.forEachIndexed { k, (i, v) -> lineTo(px(i), py(v)) }
            lineTo(px(pts.last().first), size.height)
            close()
        }
        drawPath(area, brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent), endY = size.height))
        drawPath(line, color = color.copy(alpha = 0.25f), style = Stroke(width = 9f, cap = StrokeCap.Round))
        drawPath(line, color = color, style = Stroke(width = 4f, cap = StrokeCap.Round))
    }
}

/** Главная кнопка: неоновое стекло — полупрозрачный фиолет, светящийся бордер, рисованное свечение. */
@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(26.dp)
    val fill =
        if (enabled) Brush.linearGradient(listOf(Violet.copy(alpha = 0.30f), Indigo.copy(alpha = 0.16f)))
        else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.05f)))
    // Верхний край бордера светлее — эффект преломления на кромке стекла
    val border =
        if (enabled) androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Violet.copy(alpha = 0.35f))),
        )
        else androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    Box(
        modifier
            .heightIn(min = 52.dp)
            .neonHalo(Violet, cornerRadius = 26.dp, glow = if (enabled) 16.dp else 0.dp, alpha = 0.20f)
            .clip(shape)
            .background(fill)
            .border(border, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) {
                Icon(
                    icon, contentDescription = null,
                    tint = if (enabled) Color(0xFFEFEAFF) else TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) Color(0xFFF3F0FF) else TextMuted,
                style = MaterialTheme.typography.labelLarge.copy(
                    shadow = if (enabled) Shadow(color = Violet.copy(alpha = 0.6f), blurRadius = 10f) else null
                ),
            )
        }
    }
}

/** Вторичная кнопка: тёмное стекло с тонким бордером. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.04f))
            .border(
                androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (enabled) Color.White.copy(alpha = 0.14f) else CardBorder,
                ),
                shape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onBackground else TextMuted,
        )
    }
}

/** Пилюля выбора периода на графиках: выбранная — такое же неоновое стекло. */
@Composable
fun PeriodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .heightIn(min = 40.dp)
            .neonHalo(Violet, cornerRadius = 24.dp, glow = if (selected) 8.dp else 0.dp, alpha = 0.18f)
            .clip(CircleShape)
            .background(
                if (selected) Brush.linearGradient(listOf(Violet.copy(alpha = 0.28f), Indigo.copy(alpha = 0.16f)))
                else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.05f)))
            )
            .border(
                androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.40f), Violet.copy(alpha = 0.30f)))
                    else Brush.linearGradient(listOf(CardBorder, CardBorder)),
                ),
                CircleShape,
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Color(0xFFF3F0FF) else TextMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
    }
}

/** Номер шага онбординга: светящийся градиентный круг. */
@Composable
fun StepBadge(n: Int) {
    Box(
        Modifier
            .neonHalo(Violet, cornerRadius = 24.dp, glow = 5.dp, alpha = 0.4f)
            .size(30.dp)
            .clip(CircleShape)
            .background(AccentBrush),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

data class NavItem(val icon: ImageVector, val label: String)

/** Нижняя навигация: тёмное стекло, пилюля-индикатор градиентом у выбранной вкладки. */
@Composable
fun SomenaNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = Color(0xF50C0A16)) {
        Column {
            Box(Modifier.fillMaxWidth().height(1.dp).background(CardBorder))
            Row(Modifier.navigationBarsPadding().padding(vertical = 6.dp)) {
                items.forEachIndexed { i, item ->
                    val sel = i == selected
                    NavBarItem(item, sel) { onSelect(i) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.NavBarItem(item: NavItem, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .size(width = 18.dp, height = 3.dp)
                .clip(CircleShape)
                .background(
                    if (selected) AccentBrush
                    else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                )
        )
        Icon(
            item.icon,
            contentDescription = null,
            tint = if (selected) Violet else NavDim,
            modifier = Modifier.size(22.dp),
        )
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color(0xFFE9E6FB) else NavDim,
        )
    }
}
