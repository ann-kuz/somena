package ru.somena

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.somena.core.CycleDay
import ru.somena.core.CyclePhase
import ru.somena.core.CyclePrediction
import ru.somena.core.PeriodBlock
import ru.somena.core.buildPeriods
import ru.somena.core.cycleForecastLine
import ru.somena.core.cycleHeadline
import ru.somena.core.cyclePhase
import ru.somena.core.cycleStats
import ru.somena.core.predictCycle
import ru.somena.data.SliceDb
import ru.somena.ui.CardBorder
import ru.somena.ui.CardLabel
import ru.somena.ui.GhostButton
import ru.somena.ui.GlassCard
import ru.somena.ui.GlowButton
import ru.somena.ui.Indigo
import ru.somena.ui.PeriodChip
import ru.somena.ui.Rose
import ru.somena.ui.TextMuted
import ru.somena.ui.Violet

/**
 * Календарь цикла (спека 0003): карточка на «Сегодня», экран с календарём,
 * редактор дня и статистика. Записи только ручные, хранение локальное (ADR-0005).
 * Экран скрыт для пола «м» (Профиль); данные при этом не удаляются.
 */

/** Записать или убрать день: без менструации и отметок день не хранится вовсе. */
private fun writeDay(db: SliceDb, date: LocalDate, menstruation: Boolean, flow: Int, pain: Int) {
    if (!menstruation && pain == CycleDay.LEVEL_UNMARKED) {
        db.deleteCycleDay(date)
    } else {
        db.upsertCycleDay(CycleDay(date, menstruation = menstruation, flow = flow, pain = pain))
    }
}

/** Карточка Календаря цикла на «Сегодня»: статус, прогноз и быстрый переключатель менструации. */
@Composable
fun CycleCard(db: SliceDb, revision: Int, onOpen: () -> Unit, onChanged: () -> Unit) {
    val today = LocalDate.now()
    val entries = remember(revision) { db.allCycleDays() }
    val periods = remember(entries) { buildPeriods(entries) }
    val prediction = remember(periods) { predictCycle(periods, today) }
    val todayEntry = remember(entries) { entries.associateBy { it.date }[today] }

    GlassCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CardLabel("Календарь цикла", Rose)
                Text(
                    cycleHeadline(periods, prediction, today),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                )
                prediction?.let {
                    Text(
                        cycleForecastLine(it),
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Switch(
                checked = todayEntry?.menstruation == true,
                onCheckedChange = { on ->
                    writeDay(
                        db, today, menstruation = on,
                        flow = if (on) todayEntry?.flow?.takeIf { it > 0 } ?: 0 else 0,
                        pain = todayEntry?.pain ?: CycleDay.LEVEL_UNMARKED,
                    )
                    onChanged()
                },
                modifier = Modifier.semantics { contentDescription = "Менструация идёт сегодня" },
            )
        }
    }
}

/** Полный экран Календаря цикла: статус, календарь, статистика. Открывается поверх вкладок. */
@Composable
fun CycleScreen(m: Modifier, onBack: () -> Unit, onChanged: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = remember { SliceDb(context) }
    var entries by remember { mutableStateOf(db.allCycleDays()) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var editing by remember { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now()

    fun reload() {
        entries = db.allCycleDays()
        onChanged()
    }

    val periods = remember(entries) { buildPeriods(entries) }
    val prediction = remember(periods) { predictCycle(periods, today) }
    val entriesByDate = remember(entries) { entries.associateBy { it.date } }

    Column(
        m.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.KeyboardArrowLeft, "Назад", tint = TextMuted)
            }
            Column {
                Text("Календарь цикла", fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                Text(
                    "Календарь и прогноз; данные только на этом телефоне",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CardLabel("Сегодня", Rose)
                    Text(
                        cycleHeadline(periods, prediction, today),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                    )
                    prediction?.let {
                        Text(
                            cycleForecastLine(it),
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Switch(
                    checked = entriesByDate[today]?.menstruation == true,
                    onCheckedChange = { on ->
                        writeDay(
                            db, today, menstruation = on,
                            flow = if (on) entriesByDate[today]?.flow?.takeIf { it > 0 } ?: 0 else 0,
                            pain = entriesByDate[today]?.pain ?: CycleDay.LEVEL_UNMARKED,
                        )
                        reload()
                    },
                    modifier = Modifier.semantics { contentDescription = "Менструация идёт сегодня" },
                )
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MonthHeader(month, onMove = { month = it })
                WeekdayRow()
                CalendarGrid(month, periods, prediction, entriesByDate, today, onDayClick = { editing = it })
                Legend()
            }
        }
        CycleStatsCard(periods)
        Text(
            "Тапни по любому дню, чтобы отметить или исправить: так можно вписать и прошлые циклы.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    editing?.let { date ->
        CycleDayEditorDialog(
            db = db,
            initialDate = date,
            onDismiss = { editing = null },
            onSaved = {
                editing = null
                reload()
            },
        )
    }
}

@Composable
private fun MonthHeader(month: YearMonth, onMove: (YearMonth) -> Unit) {
    val title = month.format(DateTimeFormatter.ofPattern("LLLL uuuu", Locale("ru", "RU")))
        .replaceFirstChar { it.uppercase(Locale("ru")) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onMove(month.minusMonths(1)) }) {
            Icon(Icons.Filled.KeyboardArrowLeft, "Месяц назад", tint = TextMuted)
        }
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = { onMove(month.plusMonths(1)) }) {
            Icon(Icons.Filled.KeyboardArrowRight, "Месяц вперёд", tint = TextMuted)
        }
    }
}

private val WEEKDAYS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

@Composable
private fun WeekdayRow() {
    Row(Modifier.fillMaxWidth()) {
        WEEKDAYS.forEach {
            Text(
                it,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Сетка месяца: понедельник первый, дни фаз окрашены токенами цикла. */
@Composable
private fun CalendarGrid(
    month: YearMonth,
    periods: List<PeriodBlock>,
    prediction: CyclePrediction?,
    entriesByDate: Map<LocalDate, CycleDay>,
    today: LocalDate,
    onDayClick: (LocalDate) -> Unit,
) {
    val leadingBlanks = month.atDay(1).dayOfWeek.value - 1 // понедельник = 1
    val cells: List<LocalDate?> = List(leadingBlanks) { null } +
        (1..month.lengthOfMonth()).map { month.atDay(it) }
    cells.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            week.forEach { date ->
                if (date == null) {
                    Spacer(Modifier.weight(1f))
                } else {
                    DayCell(
                        date = date,
                        phase = cyclePhase(periods, prediction, date),
                        isToday = date == today,
                        hasEntry = entriesByDate[date]?.let { !it.menstruation } == true,
                        onClick = { onDayClick(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    phase: CyclePhase,
    isToday: Boolean,
    hasEntry: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (fill, border, text) = when (phase) {
        CyclePhase.MENSTRUATION -> Triple(Rose.copy(alpha = 0.30f), Rose, Rose)
        CyclePhase.PREDICTED -> Triple(Color.Transparent, Rose.copy(alpha = 0.55f), Rose)
        CyclePhase.FERTILE -> Triple(Indigo.copy(alpha = 0.16f), Color.Transparent, Color.Unspecified)
        CyclePhase.OVULATION -> Triple(Indigo.copy(alpha = 0.16f), Indigo, Color.Unspecified)
        else -> Triple(Color.Transparent, Color.Transparent, Color.Unspecified)
    }
    Box(
        modifier
            .padding(2.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (isToday) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape) else Modifier
            )
            .padding(2.dp)
            .clip(CircleShape)
            .background(fill)
            .then(
                if (border != Color.Transparent) Modifier.border(1.dp, border, CircleShape) else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${date.dayOfMonth}",
            fontSize = 14.sp,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
            color = text,
        )
        val marker = when {
            phase == CyclePhase.OVULATION -> Indigo
            hasEntry -> Violet
            else -> null
        }
        marker?.let {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(it)
            )
        }
    }
}

@Composable
private fun Legend() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendItem(Modifier.weight(1f), Rose.copy(alpha = 0.30f), Rose, "Период")
            LegendItem(Modifier.weight(1f), Color.Transparent, Rose.copy(alpha = 0.55f), "Прогноз периода")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendItem(Modifier.weight(1f), Indigo.copy(alpha = 0.16f), Color.Transparent, "Фертильное окно")
            LegendItem(Modifier.weight(1f), Indigo.copy(alpha = 0.16f), Indigo, "Овуляция", dot = Indigo)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendItem(Modifier.weight(1f), Color.Transparent, Color.Transparent, "Запись дня", dot = Violet)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun LegendItem(
    modifier: Modifier = Modifier,
    fill: Color,
    border: Color,
    label: String,
    dot: Color? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(14.dp)
                .background(fill, CircleShape)
                .then(if (border != Color.Transparent) Modifier.border(1.dp, border, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            dot?.let {
                Box(Modifier.size(4.dp).background(it, CircleShape))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

/** Блок статистики: последние циклы, средние и отклонение последнего. */
@Composable
private fun CycleStatsCard(periods: List<PeriodBlock>) {
    val stats = remember(periods) { cycleStats(periods) }
    val fmt = DateTimeFormatter.ofPattern("dd.MM")
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Последние циклы", style = MaterialTheme.typography.titleMedium)
            if (stats.recent.isEmpty()) {
                Text("Отметь первый день менструации в календаре", color = TextMuted)
            } else {
                stats.recent.reversed().forEach { stat ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stat.start.format(fmt), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text(
                            buildString {
                                append("период ${stat.periodLengthDays} дн")
                                stat.cycleLengthDays?.let { append(" · цикл $it дн") }
                            },
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                val averages = buildList {
                    stats.averageCycleDays?.let { add("средний цикл $it дн") }
                    stats.averagePeriodDays?.let { add("средний период $it дн") }
                }
                if (averages.isNotEmpty()) {
                    Text(averages.joinToString(", "), color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                stats.lastDeviationDays?.let { dev ->
                    Text(
                        when {
                            dev < 0 -> "Последний цикл на ${-dev} дн короче среднего: период начался раньше"
                            dev > 0 -> "Последний цикл на $dev дн длиннее среднего: период начался позже"
                            else -> "Последний цикл ровно в среднюю длину"
                        },
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** Редактор дня: менструация, интенсивность выделений и боль, задним числом тоже. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CycleDayEditorDialog(
    db: SliceDb,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var date by remember { mutableStateOf(initialDate) }
    val existing = remember(date) { db.getCycleDay(date) }
    var menstruation by remember(date) { mutableStateOf(existing?.menstruation ?: false) }
    var flow by remember(date) { mutableStateOf(existing?.flow ?: 0) }
    var pain by remember(date) { mutableStateOf(existing?.pain ?: CycleDay.LEVEL_UNMARKED) }
    val today = LocalDate.now()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF171226),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
        ) {
            Column(
                Modifier.padding(20.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Запись цикла", style = MaterialTheme.typography.titleLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = { date = date.minusDays(1) },
                        enabled = date > today.minusYears(2),
                    ) { Icon(Icons.Filled.KeyboardArrowLeft, "День назад", tint = TextMuted) }
                    Text(
                        date.toString(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextMuted,
                    )
                    IconButton(
                        onClick = { date = date.plusDays(1) },
                        enabled = date < today,
                    ) { Icon(Icons.Filled.KeyboardArrowRight, "День вперёд", tint = TextMuted) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Менструация", Modifier.weight(1f), color = TextMuted)
                    Switch(
                        checked = menstruation,
                        onCheckedChange = { menstruation = it },
                        modifier = Modifier.semantics { contentDescription = "Менструация в этот день" },
                    )
                }
                if (menstruation) {
                    Text("Выделения", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LevelChip("Скудные", 1, flow) { flow = it }
                        LevelChip("Умеренные", 2, flow) { flow = it }
                        LevelChip("Обильные", 3, flow) { flow = it }
                    }
                }
                Text("Менструальная боль", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LevelChip("Нет боли", CycleDay.PAIN_NONE, pain) { pain = it }
                    LevelChip("Слабая", 1, pain) { pain = it }
                    LevelChip("Средняя", 2, pain) { pain = it }
                    LevelChip("Сильная", 3, pain) { pain = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (existing != null) {
                        GhostButton(
                            "Убрать",
                            onClick = {
                                db.deleteCycleDay(date)
                                onSaved()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    GhostButton("Отмена", onDismiss, Modifier.weight(1f))
                    GlowButton(
                        "Сохранить",
                        onClick = {
                            writeDay(
                                db, date, menstruation = menstruation,
                                flow = if (menstruation) flow else 0,
                                pain = pain,
                            )
                            onSaved()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Чип уровня: повторный тап снимает выбор («не отмечено»). */
@Composable
private fun LevelChip(label: String, level: Int, current: Int, onPick: (Int) -> Unit) {
    PeriodChip(
        label = label,
        selected = current == level,
        onClick = { onPick(if (current == level) CycleDay.LEVEL_UNMARKED else level) },
    )
}
