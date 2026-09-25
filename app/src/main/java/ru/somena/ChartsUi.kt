package ru.somena

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import ru.somena.core.ChartPeriod
import ru.somena.core.DaySlice
import ru.somena.core.chartRange
import ru.somena.core.weightTrend
import ru.somena.data.SliceDb

/** Экран «Графики» (тикет 06): периоды неделя/месяц/всё, пробелы данных ≠ нули. */
@Composable
fun ChartsScreen(m: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = remember { SliceDb(context) }
    var period by remember { mutableStateOf(ChartPeriod.WEEK) }
    val slices = remember { db.all() }
    val byDate = remember(slices) { slices.associateBy { it.date } }
    val today = LocalDate.now()

    val days: List<Pair<LocalDate, DaySlice?>> = when (period) {
        ChartPeriod.ALL -> slices.map { Pair<LocalDate, DaySlice?>(it.date, it) }
        else -> chartRange(today, period).map { it to byDate[it] }
    }
    fun values(f: (DaySlice) -> Double?): List<Double?> = days.map { it.second?.let(f) }

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
                ChartPeriod.WEEK to "Неделя",
                ChartPeriod.MONTH to "Месяц",
                ChartPeriod.ALL to "Всё",
            ).forEach { (p, label) ->
                OutlinedButton(onClick = { period = p }) {
                    Text(if (period == p) "• $label" else label)
                }
            }
        }
        if (days.isEmpty()) {
            Text("Данных пока нет — загляни на вкладку «Сегодня» и нажми «Обновить».")
        } else {
            LineChart("Шаги", listOf(series("Шаги", steps, MaterialTheme.colorScheme.primary)), "шаг.")
            LineChart("Сон", listOf(series("Сон", sleepH, MaterialTheme.colorScheme.primary)), "ч")
            CombinedChart("Вход против расхода",
                series("Съедено", eaten, MaterialTheme.colorScheme.primary),
                series("Сожжено", burn, MaterialTheme.colorScheme.secondary, dash = true), "ккал")
            LineChart(
                "Вес и тренд",
                listOf(
                    series("Вес", weight, MaterialTheme.colorScheme.primary),
                    series("Тренд", weightTrend(weight), MaterialTheme.colorScheme.secondary, dash = true),
                ),
                "кг",
            )
            LineChart(
                "БЖУ",
                listOf(
                    series("Белки", protein, MaterialTheme.colorScheme.primary),
                    series("Жиры", fat, MaterialTheme.colorScheme.secondary, dash = true),
                    series("Углеводы", carbs, MaterialTheme.colorScheme.tertiary),
                ),
                "г",
            )
            LineChart("Жир", listOf(series("Жир", bodyFat, MaterialTheme.colorScheme.primary)), "%")
            LineChart("Кости", listOf(series("Кости", bone, MaterialTheme.colorScheme.primary)), "кг")
            LineChart("Обмен", listOf(series("Обмен", bmr, MaterialTheme.colorScheme.primary)), "ккал/дн")
            val first = days.first().first.format(DateTimeFormatter.ofPattern("dd.MM"))
            val last = days.last().first.format(DateTimeFormatter.ofPattern("dd.MM"))
            Text("Период: $first – $last. Разрыв линии = «данных нет».", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun series(label: String, values: List<Double?>, color: Color, dash: Boolean = false) =
    ChartSeries(label, values, color, dash)

data class ChartSeries(val label: String, val values: List<Double?>, val color: Color, val dash: Boolean)

@Composable
fun LineChart(title: String, seriesList: List<ChartSeries>, unit: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        val hasData = seriesList.any { s -> s.values.any { it != null } }
        Box(Modifier.fillMaxWidth().height(130.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                if (!hasData) return@Canvas
                val all = seriesList.flatMap { it.values }.filterNotNull()
                val lo = all.min()
                val hi = all.max()
                val span = (hi - lo).takeIf { it > 0 } ?: 1.0
                val pad = 8f
                val w = size.width - 2 * pad
                val h = size.height - 2 * pad
                fun x(i: Int) = pad + if (seriesList[0].values.size == 1) w / 2 else w * i / (seriesList[0].values.size - 1)
                fun y(v: Double) = pad + (h * (1.0 - (v - lo) / span)).toFloat()

                seriesList.forEach { s ->
                    val effect = if (s.dash) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null
                    var runStart: Int? = null
                    val n = s.values.size
                    val path = Path()
                    for (i in 0 until n) {
                        val v = s.values[i]
                        if (v != null) {
                            if (runStart == null) {
                                runStart = i
                                path.moveTo(x(i), y(v))
                            } else {
                                path.lineTo(x(i), y(v))
                            }
                        } else {
                            runStart = null
                        }
                    }
                    drawPath(
                        path,
                        color = s.color,
                        style = Stroke(width = 4f, pathEffect = effect),
                    )
                }
            }
            if (!hasData) {
                Text("данных нет", style = MaterialTheme.typography.bodySmall)
            }
        }
        Legend(seriesList, unit)
    }
}

@Composable
fun CombinedChart(title: String, primary: ChartSeries, secondary: ChartSeries, unit: String) =
    LineChart(title, listOf(primary, secondary), unit)

@Composable
private fun Legend(seriesList: List<ChartSeries>, unit: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        seriesList.forEach { s ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
