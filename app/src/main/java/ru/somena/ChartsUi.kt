package ru.somena

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import ru.somena.core.AiMetric
import ru.somena.core.ChartCard
import ru.somena.core.ChartLayout
import ru.somena.core.DayData
import ru.somena.core.DaySlice
import ru.somena.core.Wellbeing
import ru.somena.core.lastDays
import ru.somena.core.clampWindowEnd
import ru.somena.core.hiddenAs
import ru.somena.core.manualMetric
import ru.somena.core.metricSeries
import ru.somena.core.PanAccumulator
import ru.somena.core.weightTrend
import ru.somena.core.withStepsBurn
import ru.somena.data.CardLayoutStore
import ru.somena.data.ProfileStore
import ru.somena.data.SliceDb
import ru.somena.data.StepsBurnStore
import ru.somena.ui.GlassCard
import ru.somena.ui.NebulaBackground
import ru.somena.ui.PeriodChip
import ru.somena.ui.TextMuted

private const val ALL_DAYS = 0 // «Всё»: без окна, все сохранённые срезы подряд
private const val MONTH_DAYS = 31
private const val WEEK_DAYS = 7

private val ruLocale = Locale("ru", "RU")

private fun fmtNum(v: Double): String =
    if (v == v.roundToLong().toDouble()) String.format(ruLocale, "%,.0f", v)
    else String.format(ruLocale, "%,.1f", v)

private fun fmtDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("dd.MM"))

/**
 * Экран «Графики»: окно 7/31 день или всё, перетаскивание, оси, тап по точке.
 * Долгое нажатие на графике открывает меню карточки (спека 0011): внести данные,
 * скрыть, переместить; порядок и скрытость - настройка Пользователя.
 */
@Composable
fun ChartsScreen(m: Modifier) {
    val context = LocalContext.current
    val db = remember { SliceDb(context) }
    // Ручной базовый расход из Профиля: запас для Дефицита, пока весы не передают свой.
    // Профиль целиком нужен и Расходу от шагов (спека 0017).
    val profile = remember { ProfileStore(context).load() }
    val profileBmr = profile.bmrKcal
    val stepsBurn = remember { StepsBurnStore(context).isEnabled() }
    // Запись из меню графика растит ревизию: срезы и Самочувствие перечитываются.
    var revision by remember { mutableIntStateOf(0) }
    val slices = remember(revision) {
        db.all().let { if (stepsBurn) it.withStepsBurn(profile) else it }
    }
    val wellbeing = remember(revision) { db.allWellbeing() }
    val byDate = remember(slices) { slices.associateBy { it.date } }
    val data = remember(slices, wellbeing) {
        DayData(byDate, wellbeing.groupBy { it.date })
    }
    val today = LocalDate.now()
    var windowDays by remember { mutableIntStateOf(WEEK_DAYS) }
    var windowEnd by remember { mutableStateOf(today) }

    // Порядок и скрытость графиков - настройка Пользователя, хранится локально.
    val layoutStore = remember { CardLayoutStore(context, "charts_order", "charts_hidden", ChartCard::byId) }
    var layout by remember { mutableStateOf(layoutStore.load(ChartCard.entries.toList())) }
    fun saveLayout(l: ChartLayout) {
        layout = l
        layoutStore.save(l)
    }

    var menuChart by remember { mutableStateOf<ChartCard?>(null) }
    var entryChart by remember { mutableStateOf<ChartCard?>(null) }
    var reorder by remember { mutableStateOf(false) }
    var showWellbeingEditor by remember { mutableStateOf(false) }

    val firstDataDate = listOfNotNull(slices.firstOrNull()?.date, wellbeing.firstOrNull()?.date)
        .minOrNull() ?: today

    val days: List<LocalDate> = if (windowDays == ALL_DAYS) {
        // «Всё»: даты срезов и Самочувствия вместе, день без обеих записей не существует.
        (slices.map { it.date } + wellbeing.map { it.date }).distinct().sorted()
    } else {
        lastDays(windowEnd, windowDays)
    }
    fun shift(deltaDays: Int) {
        if (windowDays != ALL_DAYS && deltaDays != 0) {
            windowEnd = clampWindowEnd(windowEnd.plusDays(deltaDays.toLong()), today, firstDataDate)
        }
    }

    Column(
        m.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Графики",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                WEEK_DAYS to "Неделя",
                MONTH_DAYS to "31 день",
                ALL_DAYS to "Всё",
            ).forEach { (p, label) ->
                PeriodChip(label, selected = windowDays == p, onClick = {
                    windowDays = p
                    windowEnd = today
                })
            }
        }
        if (slices.isEmpty() && wellbeing.isEmpty()) {
            Text(
                "Данных пока нет: загляни на вкладку «Сегодня» и нажми «Обновить».",
                color = TextMuted,
            )
        } else {
            if (windowDays != ALL_DAYS) {
                Text(
                    "Окно: ${fmtDate(days.first())} - ${fmtDate(days.last())}. Тяни графики влево/вправо, " +
                        "коснись точки: покажу значение. Удержание - меню графика.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "Весь период: ${fmtDate(days.first())} - ${fmtDate(days.last())}. Коснись точки: покажу " +
                        "значение. Удержание - меню графика.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            layout.visible.forEach { chart ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(chart) {
                            // Долгое нажатие - меню карточки; тап по точке и перетаскивание
                            // окна живут в самом графике и не мешают удержанию.
                            detectTapGestures(onLongPress = { menuChart = chart }) { }
                        }
                ) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        ChartByCard(chart, days, data, profileBmr, ::shift) { menuChart = chart }
                    }
                }
            }
        }
        HiddenCardsSection(
            hiddenCards = layout.order.filter { it in layout.hidden },
            onOpenMenu = { menuChart = it },
        )
    }

    menuChart?.let { chart ->
        CardMenuDialog(
            card = chart,
            isHidden = chart in layout.hidden,
            canEnter = chart == ChartCard.WELLBEING || chart.manualMetric() != null,
            onEnterData = {
                menuChart = null
                if (chart == ChartCard.WELLBEING) {
                    showWellbeingEditor = true
                } else if (chart.manualMetric() != null) {
                    entryChart = chart
                }
            },
            onMove = {
                menuChart = null
                reorder = true
            },
            onHide = {
                saveLayout(layout.hiddenAs(chart, hide = true))
                menuChart = null
            },
            onShow = {
                saveLayout(layout.hiddenAs(chart, hide = false))
                menuChart = null
            },
            onDismiss = { menuChart = null },
        )
    }

    entryChart?.let { chart ->
        chart.manualMetric()?.let { metric ->
            PlateEntryDialog(
                metric = metric,
                db = db,
                onSaved = {
                    entryChart = null
                    revision++
                },
                onDismiss = { entryChart = null },
            )
        }
    }

    if (showWellbeingEditor) {
        val markedFirst = wellbeing.any { it.date == today && it.slot == Wellbeing.SLOT_FIRST }
        WellbeingEditorDialog(
            db = db,
            initialDate = today,
            initialSlot = if (markedFirst) Wellbeing.SLOT_SECOND else Wellbeing.SLOT_FIRST,
            onDismiss = {
                showWellbeingEditor = false
                revision++
            },
        )
    }

    if (reorder) {
        Box(Modifier.fillMaxSize().background(ru.somena.ui.BgBase)) {
            NebulaBackground()
            ReorderCardsScreen(
                initial = layout,
                onDone = {
                    saveLayout(it)
                    reorder = false
                },
            )
        }
    }
}

/** Значения показателя по датам окна: день без среза даёт пустоту, не ноль (спека 0001). */
private fun seriesOf(days: List<LocalDate>, data: DayData, selector: (DaySlice) -> Double?): List<Double?> =
    days.map { d -> data.slicesByDate[d]?.let(selector) }

/** График по карточке: составные (Дефицит, Калории, БЖУ) рисуют свои серии. */
@Composable
private fun ChartByCard(
    chart: ChartCard,
    days: List<LocalDate>,
    data: DayData,
    profileBmr: Double?,
    onPan: (Int) -> Unit,
    onLongPress: () -> Unit,
) {
    when (chart) {
        ChartCard.DEFICIT -> LineChart(
            chart.title, days,
            listOf(
                ChartSeries(
                    "Дефицит", metricSeries(AiMetric.DEFICIT, days, data, profileBmr),
                    MaterialTheme.colorScheme.primary, "ккал"
                ),
            ),
            onPan = onPan,
            onLongPress = onLongPress,
            caption = "Сожжено + базовый расход - съедено",
        )
        ChartCard.CALORIES -> LineChart(
            chart.title, days,
            listOf(
                ChartSeries("Съедено", seriesOf(days, data) { it.eatenKcal }, MaterialTheme.colorScheme.tertiary, "ккал"),
                ChartSeries("Сожжено", seriesOf(days, data) { it.burnedKcal }, MaterialTheme.colorScheme.primary, "ккал"),
            ),
            onPan = onPan,
            onLongPress = onLongPress,
        )
        ChartCard.WEIGHT -> LineChart(
            chart.title, days,
            listOf(
                ChartSeries("Вес", seriesOf(days, data) { it.weightKg }, MaterialTheme.colorScheme.primary, "кг"),
                ChartSeries("Тренд", weightTrend(seriesOf(days, data) { it.weightKg }), MaterialTheme.colorScheme.secondary, "кг"),
            ),
            onPan = onPan,
            onLongPress = onLongPress,
        )
        ChartCard.MACROS -> LineChart(
            chart.title, days,
            listOf(
                ChartSeries("Белки", seriesOf(days, data) { it.proteinG }, MaterialTheme.colorScheme.primary, "г"),
                ChartSeries("Жиры", seriesOf(days, data) { it.fatG }, MaterialTheme.colorScheme.tertiary, "г"),
                ChartSeries("Углеводы", seriesOf(days, data) { it.carbsG }, MaterialTheme.colorScheme.secondary, "г"),
            ),
            onPan = onPan,
            onLongPress = onLongPress,
        )
        ChartCard.STEPS -> LineChart(
            chart.title, days,
            listOf(ChartSeries("Шаги", seriesOf(days, data) { it.steps?.toDouble() }, MaterialTheme.colorScheme.tertiary, "шаг.")),
            onPan = onPan,
            onLongPress = onLongPress,
        )
        ChartCard.SLEEP -> LineChart(
            chart.title, days,
            listOf(ChartSeries("Сон", seriesOf(days, data) { it.sleepMinutes?.div(60.0) }, MaterialTheme.colorScheme.primary, "ч")),
            onPan = onPan,
            onLongPress = onLongPress,
        )
        ChartCard.PULSE -> LineChart(
            chart.title, days,
            listOf(
                ChartSeries("Средний", seriesOf(days, data) { it.pulseAvg?.toDouble() }, MaterialTheme.colorScheme.primary, "уд/мин"),
            ),
            onPan = onPan,
            onLongPress = onLongPress,
            caption = "среднее за день; минимум и максимум - на плашке Пульса",
        )
        ChartCard.WELLBEING -> LineChart(
            chart.title, days,
            listOf(
                ChartSeries("Энергия", metricSeries(AiMetric.ENERGY, days, data), MaterialTheme.colorScheme.primary, "из 10"),
                ChartSeries("Настроение", metricSeries(AiMetric.MOOD, days, data), MaterialTheme.colorScheme.secondary, "из 10"),
                ChartSeries("Качество сна", metricSeries(AiMetric.SLEEP_QUALITY, days, data), TextMuted, "из 10"),
            ),
            onPan = onPan,
            onLongPress = onLongPress,
            yMin = Wellbeing.MIN.toDouble(),
            yMax = Wellbeing.MAX.toDouble(),
        )
        ChartCard.BODY_FAT -> LineChart(
            chart.title, days,
            listOf(ChartSeries("Процент жира", seriesOf(days, data) { it.bodyFatPct }, MaterialTheme.colorScheme.primary, "%")),
            onPan = onPan,
            onLongPress = onLongPress,
        )
        ChartCard.BONE -> LineChart(
            chart.title, days,
            listOf(ChartSeries("Костная масса", seriesOf(days, data) { it.boneMassKg }, MaterialTheme.colorScheme.primary, "кг")),
            onPan = onPan,
            onLongPress = onLongPress,
        )
        ChartCard.BMR -> LineChart(
            chart.title, days,
            listOf(ChartSeries("Базовый расход", seriesOf(days, data) { it.bmrKcal }, MaterialTheme.colorScheme.primary, "ккал/дн")),
            onPan = onPan,
            onLongPress = onLongPress,
        )
    }
}

data class ChartSeries(
    val label: String,
    val values: List<Double?>,
    val color: Color,
    val unit: String,
)

/**
 * Линейный график с осями (числа слева, даты снизу), перетаскиванием, тапом по точке
 * и долгим нажатием (меню карточки). При отсутствии данных точка отсутствует,
 * но линия не рвётся — соединяется с ближайшей имеющейся.
 * [yMin]/[yMax] фиксируют ось: шкалы Самочувствия всегда рисуются 0–10.
 */
@Composable
fun LineChart(
    title: String,
    dates: List<LocalDate>,
    seriesList: List<ChartSeries>,
    onPan: (Int) -> Unit = {},
    onLongPress: () -> Unit = {},
    yMin: Double? = null,
    yMax: Double? = null,
    caption: String? = null,
) {
    val n = dates.size
    val allValues = seriesList.flatMap { it.values }.filterNotNull()
    val lo = yMin ?: allValues.minOrNull() ?: 0.0
    val hi = yMax ?: allValues.maxOrNull() ?: 1.0
    val span = (hi - lo).takeIf { it > 0 } ?: 1.0
    val mid = (lo + hi) / 2.0
    val hasData = allValues.isNotEmpty()
    val gridColor = Color.White.copy(alpha = 0.07f)
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
        caption?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        val sel = selected
        if (sel != null) {
            val parts = seriesList.mapNotNull { s ->
                s.values.getOrNull(sel)?.let { "${s.label} ${fmtNum(it)} ${s.unit}" }
            }
            Text(
                if (parts.isEmpty()) "${fmtDate(dates[sel])}: данных нет"
                else "${fmtDate(dates[sel])}: ${parts.joinToString(" · ")}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Ось Y: hi / mid / lo
            Column(
                Modifier.width(44.dp).height(120.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(fmtNum(hi), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text(fmtNum(mid), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text(fmtNum(lo), style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(120.dp)
                    .pointerInput(dates, seriesList) {
                        detectTapGestures(
                            onLongPress = { onLongPress() },
                        ) { pos ->
                            selected = if (n == 1) 0
                            else ((pos.x / size.width) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                        }
                    }
                    .pointerInput(n) {
                        // Ключ n, а не dates: сдвиг окна не рвёт жест, копилка живёт до смены периода.
                        val pan = PanAccumulator()
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (n > 1) {
                                val pxPerDay = size.width.toFloat() / n
                                val delta = pan.add(-dragAmount.x, pxPerDay)
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
                        val path = Path()
                        var started = false
                        var firstIdx = -1
                        var lastIdx = -1
                        // Пропуски не рвут линию: к каждой непустой точке ведём линию от предыдущей непустой.
                        s.values.forEachIndexed { i, v ->
                            if (v != null) {
                                if (!started) {
                                    path.moveTo(x(i, size.width), y(v, size.height))
                                    started = true
                                    firstIdx = i
                                } else {
                                    path.lineTo(x(i, size.width), y(v, size.height))
                                }
                                lastIdx = i
                            }
                        }
                        // градиентная заливка под линией — «неоновый» след
                        if (firstIdx >= 0) {
                            val area = Path().apply {
                                addPath(path)
                                lineTo(x(lastIdx, size.width), size.height)
                                lineTo(x(firstIdx, size.width), size.height)
                                close()
                            }
                            drawPath(
                                area,
                                brush = Brush.verticalGradient(
                                    listOf(s.color.copy(alpha = 0.28f), Color.Transparent),
                                    endY = size.height,
                                ),
                            )
                        }
                        // неоновое свечение линии: мягкий широкий штрих под основным
                        drawPath(
                            path,
                            color = s.color.copy(alpha = 0.25f),
                            style = Stroke(width = 10f, cap = StrokeCap.Round),
                        )
                        drawPath(path, color = s.color, style = Stroke(width = 4f, cap = StrokeCap.Round))
                    }
                    // выделенная точка со свечением
                    sel?.let { idx ->
                        seriesList.forEach { s ->
                            s.values.getOrNull(idx)?.let { v ->
                                val c = androidx.compose.ui.geometry.Offset(x(idx, size.width), y(v, size.height))
                                drawCircle(s.color.copy(alpha = 0.30f), radius = 15f, center = c)
                                drawCircle(s.color, radius = 7f, center = c)
                                drawCircle(Color.White, radius = 3f, center = c)
                            }
                        }
                    }
                }
                if (!hasData) {
                    Text("данных нет", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        }
        // Ось X: первая / средняя / последняя дата окна
        if (n >= 2) {
            Row(Modifier.padding(start = 44.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtDate(dates.first()), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text(fmtDate(dates[n / 2]), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text(fmtDate(dates.last()), style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        } else if (n == 1) {
            Text(fmtDate(dates[0]), style = MaterialTheme.typography.labelSmall, color = TextMuted, modifier = Modifier.padding(start = 44.dp))
        }
        Legend(seriesList)
    }
}

@Composable
private fun Legend(seriesList: List<ChartSeries>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        seriesList.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 18.dp, height = 4.dp)) {
                    drawLine(
                        s.color,
                        androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                        androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                        strokeWidth = 4f,
                        cap = StrokeCap.Round,
                    )
                }
                Text(" ${s.label} (${s.unit})", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
    }
}
