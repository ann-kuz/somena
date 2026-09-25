package ru.somena

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import ru.somena.core.DaySlice
import ru.somena.core.weightTrend
import ru.somena.data.SliceDb

private const val ALL_DAYS = 0 // «Всё»: без окна, все сохранённые срезы подряд
private const val MONTH_DAYS = 31
private const val WEEK_DAYS = 7

private val ruLocale = Locale("ru", "RU")

private fun fmtNum(v: Double): String =
    if (v == v.roundToLong().toDouble()) String.format(ruLocale, "%,.0f", v)
    else String.format(ruLocale, "%,.1f", v)

private fun fmtDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("dd.MM"))

/** Экран «Графики»: окно 7/31 день или всё, перетаскивание, оси, тап по точке. */
@Composable
fun ChartsScreen(m: Modifier) {
    val context = LocalContext.current
    val db = remember { SliceDb(context) }
    val slices = remember { db.all() }
    val byDate = remember(slices) { slices.associateBy { it.date } }
    val today = LocalDate.now()
    var windowDays by remember { mutableIntStateOf(WEEK_DAYS) }
    var windowEnd by remember { mutableStateOf(today) }

    val firstDataDate = slices.firstOrNull()?.date ?: today

    fun clampEnd(d: LocalDate): LocalDate =
        when {
            d > today -> today
            d < firstDataDate -> firstDataDate
            else -> d
        }

    val days: List<LocalDate> = if (windowDays == ALL_DAYS) {
        slices.map { it.date }
    } else {
        ((windowDays - 1) downTo 0).map { windowEnd.minusDays(it) }
    }
    fun shift(deltaDays: Int) {
        if (windowDays != ALL_DAYS && deltaDays != 0) {
            windowEnd = clampEnd(windowEnd.plusDays(deltaDays.toLong()))
        }
    }

    val daySlices: List<DaySlice?> = days.map { byDate[it] }
    fun values(f: (DaySlice) -> Double?): List<Double?> = daySlices.map { it?.let(f) }

    val steps = values { it.steps?.toDouble() }
    val sleepH = values { it.sleepMinutes?.div(60.0) }
    val burn = values { it.burnedKcal }
    val eaten = values { it.eatenKcal }
    val weight = values { it.weightKg }
    val protein = values { it.proteinG }
    val fat = values { it.fatG }
    val carbs = values { it.carbsG }
    val bodyFat = values { it.bodyFatPct }
    val bone = values { it.boneMassKg }
    val bmr = values { it.bmrKcal }

    Column(
        m.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Графики", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                WEEK_DAYS to "Неделя",
                MONTH_DAYS to "31 день",
                ALL_DAYS to "Всё",
            ).forEach { (p, label) ->
                OutlinedButton(onClick = {
                    windowDays = p
                    windowEnd = today
                }) {
                    Text(if (windowDays == p) "• $label" else label)
                }
            }
        }
        if (slices.isEmpty()) {
            Text("Данных пока нет — загляни на вкладку «Сегодня» и нажми «Обновить».")
        } else {
            if (windowDays != ALL_DAYS) {
                Text(
                    "Окно: ${fmtDate(days.first())} – ${fmtDate(days.last())}. Тяни графики влево/вправо, " +
                        "коснись точки — покажу значение.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "Весь период: ${fmtDate(days.first())} – ${fmtDate(days.last())}. Коснись точки — покажу значение.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            LineChart("Шаги", days, listOf(series("Шаги", steps, MaterialTheme.colorScheme.primary)), "шаг.", ::shift)
            LineChart("Сон", days, listOf(series("Сон", sleepH, MaterialTheme.colorScheme.primary)), "ч", ::shift)
            LineChart(
                "Вход против расхода", days,
                listOf(
                    series("Съедено", eaten, MaterialTheme.colorScheme.primary),
                    series("Сожжено", burn, MaterialTheme.colorScheme.secondary, dash = true),
                ),
                "ккал", ::shift,
            )
            LineChart(
                "Вес и тренд", days,
                listOf(
                    series("Вес", weight, MaterialTheme.colorScheme.primary),
                    series("Тренд", weightTrend(weight), MaterialTheme.colorScheme.secondary, dash = true),
                ),
                "кг", ::shift,
            )
            LineChart(
                "БЖУ", days,
                listOf(
                    series("Белки", protein, MaterialTheme.colorScheme.primary),
                    series("Жиры", fat, MaterialTheme.colorScheme.secondary, dash = true),
                    series("Углеводы", carbs, MaterialTheme.colorScheme.tertiary),
                ),
                "г", ::shift,
            )
            LineChart("Жир", days, listOf(series("Жир", bodyFat, MaterialTheme.colorScheme.primary)), "%", ::shift)
            LineChart("Кости", days, listOf(series("Кости", bone, MaterialTheme.colorScheme.primary)), "кг", ::shift)
            LineChart("Обмен", days, listOf(series("Обмен", bmr, MaterialTheme.colorScheme.primary)), "ккал/дн", ::shift)
        }
    }
}

private fun series(label: String, values: List<Double?>, color: Color, dash: Boolean = false) =
    ChartSeries(label, values, color, dash)

data class ChartSeries(val label: String, val values: List<Double?>, val color: Color, val dash: Boolean)

/**
 * Линейный график с осями (числа слева, даты снизу), перетаскиванием и тапом по точке.
 * При отсутствии данных точка отсутствует, но линия не рвётся — соединяется с ближайшей имеющейся.
 */
@Composable
fun LineChart(
    title: String,
    dates: List<LocalDate>,
    seriesList: List<ChartSeries>,
    unit: String,
    onPan: (Int) -> Unit = {},
) {
    val n = dates.size
    val allValues = seriesList.flatMap { it.values }.filterNotNull()
    val lo = allValues.minOrNull() ?: 0.0
    val hi = allValues.maxOrNull() ?: 1.0
    val span = (hi - lo).takeIf { it > 0 } ?: 1.0
    val mid = (lo + hi) / 2.0
    val hasData = allValues.isNotEmpty()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    var selected by remember(dates, seriesList) { mutableStateOf<Int?>(null) }

    fun x(i: Int, width: Float): Float {
        val pad = 10f
        val w = width - 2 * pad
        return pad + if (n == 1) w / 2 else w * i / (n - 1)
    }

    fun y(v: Double, height: Float): Float {
        val pad = 10f
        val h = height - 2 * pad
        return pad + (h * (1.0 - (v - lo) / span)).toFloat()
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        val sel = selected
        if (sel != null) {
            val parts = seriesList.mapNotNull { s ->
                s.values.getOrNull(sel)?.let { "${s.label} ${fmtNum(it)} $unit" }
            }
            Text(
                if (parts.isEmpty()) "${fmtDate(dates[sel])} — данных нет"
                else "${fmtDate(dates[sel])}: ${parts.joinToString(" · ")}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Ось Y: hi / mid / lo
            Column(
                Modifier.width(44.dp).height(120.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(fmtNum(hi), style = MaterialTheme.typography.labelSmall)
                Text(fmtNum(mid), style = MaterialTheme.typography.labelSmall)
                Text(fmtNum(lo), style = MaterialTheme.typography.labelSmall)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(120.dp)
                    .pointerInput(dates, seriesList) {
                        detectTapGestures { pos ->
                            selected = if (n == 1) 0
                            else ((pos.x / size.width) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                        }
                    }
                    .pointerInput(dates, seriesList) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (n > 1) {
                                val pxPerDay = size.width.toFloat() / n
                                val delta = (-dragAmount.x / pxPerDay).roundToInt()
                                if (delta != 0) onPan(delta)
                            }
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // сетка: три горизонтальные линии
                    listOf(y(hi, size.height), y(mid, size.height), y(lo, size.height)).forEach { yy ->
                        drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, yy),
                            androidx.compose.ui.geometry.Offset(size.width, yy), strokeWidth = 1f)
                    }
                    if (!hasData) return@Canvas

                    seriesList.forEach { s ->
                        val effect = if (s.dash) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null
                        val path = Path()
                        var started = false
                        // Пропуски не рвут линию: к каждой непустой точке ведём линию от предыдущей непустой.
                        s.values.forEachIndexed { i, v ->
                            if (v != null) {
                                if (!started) {
                                    path.moveTo(x(i, size.width), y(v, size.height))
                                    started = true
                                } else {
                                    path.lineTo(x(i, size.width), y(v, size.height))
                                }
                            }
                        }
                        drawPath(path, color = s.color, style = Stroke(width = 4f, pathEffect = effect))
                    }
                    // выделенная точка
                    sel?.let { idx ->
                        seriesList.forEach { s ->
                            s.values.getOrNull(idx)?.let { v ->
                                drawCircle(
                                    s.color, radius = 7f,
                                    center = androidx.compose.ui.geometry.Offset(x(idx, size.width), y(v, size.height)),
                                )
                            }
                        }
                    }
                }
                if (!hasData) {
                    Text("данных нет", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // Ось X: первая / средняя / последняя дата окна
        if (n >= 2) {
            Row(Modifier.padding(start = 44.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtDate(dates.first()), style = MaterialTheme.typography.labelSmall)
                Text(fmtDate(dates[n / 2]), style = MaterialTheme.typography.labelSmall)
                Text(fmtDate(dates.last()), style = MaterialTheme.typography.labelSmall)
            }
        } else if (n == 1) {
            Text(fmtDate(dates[0]), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 44.dp))
        }
        Legend(seriesList, unit)
    }
}

@Composable
private fun Legend(seriesList: List<ChartSeries>, unit: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        seriesList.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 18.dp, height = 4.dp)) {
                    drawLine(
                        s.color,
                        androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                        androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                        strokeWidth = 4f,
                        pathEffect = if (s.dash) PathEffect.dashPathEffect(floatArrayOf(8f, 5f)) else null,
                    )
                }
                Text(" ${s.label} ($unit)", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
