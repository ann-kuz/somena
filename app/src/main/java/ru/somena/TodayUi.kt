package ru.somena

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import ru.somena.core.CardLayout
import ru.somena.core.DaySlice
import ru.somena.core.ImportPreview
import ru.somena.core.LatestValues
import ru.somena.core.LayoutCard
import ru.somena.core.ManualMetric
import ru.somena.core.MetricLatest
import ru.somena.core.TodayPlate
import ru.somena.core.fmt
import ru.somena.core.manualEatenPreview
import ru.somena.core.manualMacroProblem
import ru.somena.core.manualMetric
import ru.somena.core.manualOldValue
import ru.somena.core.manualProblem
import ru.somena.core.manualSlicePreview
import ru.somena.core.moved
import ru.somena.core.parseManualNumber
import ru.somena.core.todayBurned
import ru.somena.core.todayNutrition
import ru.somena.core.toSlice
import ru.somena.data.SliceDb
import ru.somena.ui.BgBase
import ru.somena.ui.CardBorder
import ru.somena.ui.EyeIcon
import ru.somena.ui.EyeOffIcon
import ru.somena.ui.GhostButton
import ru.somena.ui.GlassCard
import ru.somena.ui.GlowButton
import ru.somena.ui.Gold
import ru.somena.ui.ImportPreviewCard
import ru.somena.ui.MetricCard
import ru.somena.ui.ScreenHeader
import ru.somena.ui.TextMuted
import ru.somena.ui.Violet

/**
 * Диалог меню карточки (плашки «Сегодня», графика «Графиков»): внести данные,
 * переместить, скрыть; у скрытой - показать. «Внести данные» есть только у
 * карточек со своим показателем.
 */
@Composable
fun <T : LayoutCard> CardMenuDialog(
    card: T,
    isHidden: Boolean,
    canEnter: Boolean,
    onEnterData: () -> Unit,
    onMove: () -> Unit,
    onHide: () -> Unit,
    onShow: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(BgBase)
                    .border(androidx.compose.foundation.BorderStroke(1.dp, CardBorder), RoundedCornerShape(24.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(card.title, style = MaterialTheme.typography.titleSmall)
                if (isHidden) {
                    GlowButton("Показать", onClick = onShow, modifier = Modifier.fillMaxWidth())
                } else {
                    if (canEnter) {
                        GlowButton("Внести данные", onClick = onEnterData, modifier = Modifier.fillMaxWidth())
                    }
                    GhostButton("Переместить", onClick = onMove, modifier = Modifier.fillMaxWidth())
                    GhostButton("Скрыть", onClick = onHide, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * Окошко ручного ввода с карточки: категория уже выбрана, сверху переключается
 * дата, итог идёт тем же Предпросмотром, запись - по явному «Записать».
 */
@Composable
fun PlateEntryDialog(
    metric: ManualMetric,
    db: SliceDb,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    val earliest = today.minusYears(2)
    var date by remember { mutableStateOf(today) }
    var value by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    val existing = remember(date) { db.get(date) }

    val problem = when {
        value.isBlank() -> null
        metric == ManualMetric.EATEN ->
            manualProblem(metric, value) ?: manualMacroProblem(protein) ?: manualMacroProblem(fat) ?: manualMacroProblem(carbs)
        else -> manualProblem(metric, value)
    }
    val ready = value.isNotBlank() && problem == null

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(BgBase)
                    .border(androidx.compose.foundation.BorderStroke(1.dp, CardBorder), RoundedCornerShape(24.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val p = preview
                if (p == null) {
                    Text(metric.label, style = MaterialTheme.typography.titleSmall)
                    // Дата - сверху стрелками: сегодня по умолчанию, будущее недоступно.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { date = date.minusDays(1) },
                            enabled = date > earliest,
                        ) { Icon(Icons.Filled.KeyboardArrowLeft, "День назад", tint = TextMuted) }
                        Text(
                            date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        IconButton(
                            onClick = { date = date.plusDays(1) },
                            enabled = date < today,
                        ) { Icon(Icons.Filled.KeyboardArrowRight, "День вперёд", tint = TextMuted) }
                    }
                    existing.manualOldValue(metric)?.let {
                        Text(
                            "Сейчас в базе: ${fmt(it)} ${metric.unitHint}",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (metric == ManualMetric.EATEN) {
                        OutlinedTextField(
                            value,
                            onValueChange = { value = it },
                            label = { Text("Съедено, ккал") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // БЖУ по желанию: с упаковки часто известны все четыре числа.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(protein, onValueChange = { protein = it }, label = { Text("Б, г") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), modifier = Modifier.weight(1f))
                            OutlinedTextField(fat, onValueChange = { fat = it }, label = { Text("Ж, г") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), modifier = Modifier.weight(1f))
                            OutlinedTextField(carbs, onValueChange = { carbs = it }, label = { Text("У, г") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        }
                    } else {
                        OutlinedTextField(
                            value,
                            onValueChange = { value = it },
                            label = { Text("${metric.label}, ${metric.unitHint}") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    problem?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlowButton(
                            "В Предпросмотр",
                            enabled = ready,
                            onClick = {
                                preview = if (metric == ManualMetric.EATEN) {
                                    manualEatenPreview(
                                        parseManualNumber(value)!!,
                                        protein.takeIf { it.isNotBlank() }?.let { parseManualNumber(it) },
                                        fat.takeIf { it.isNotBlank() }?.let { parseManualNumber(it) },
                                        carbs.takeIf { it.isNotBlank() }?.let { parseManualNumber(it) },
                                        date,
                                        existing,
                                    )
                                } else {
                                    manualSlicePreview(metric, parseManualNumber(value)!!, date, existing)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        GhostButton("Отмена", onClick = onDismiss, modifier = Modifier.weight(1f))
                    }
                } else {
                    ImportPreviewCard(
                        p,
                        onConfirm = {
                            for (entry in p.entries) db.upsert(entry.toSlice(db.get(entry.date)))
                            onSaved()
                        },
                        onCancel = { preview = null },
                        cancelLabel = "Изменить",
                    )
                }
            }
        }
    }
}

/**
 * Режим переноса карточек: полный экран, карточки одной колонкой, перетаскивание
 * удержанием за любую точку строки; остальные сдвигаются, освобождая место.
 * Перетаскиваемая строка не анимирует своё место (иначе палец и карточка
 * расходятся), а шаг переноса считает отступ между строками. Порядок
 * применяется по «Подтвердить» (системное «назад» делает то же).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun <T : LayoutCard> ReorderCardsScreen(initial: CardLayout<T>, onDone: (CardLayout<T>) -> Unit) {
    var order by remember { mutableStateOf(initial.order) }
    val hidden = initial.hidden
    val listState = rememberLazyListState()
    var dragging by remember { mutableStateOf<T?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableStateOf(0) }
    val spacingPx = with(LocalDensity.current) { RowSpacing.toPx() }

    fun finish() = onDone(CardLayout(order, hidden))
    BackHandler { finish() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Перенос карточек", "удерживай карточку и тащи на новое место")
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(RowSpacing),
        ) {
            items(order, key = { it.id }) { card ->
                val isDragging = dragging == card
                Row(
                    Modifier
                        .fillMaxWidth()
                        // Анимация места - только у остальных: перетаскиваемая
                        // должна слушаться пальца, а не прыгать сама.
                        .then(if (isDragging) Modifier else Modifier.animateItemPlacement())
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                        .onSizeChanged { rowHeight = it.height }
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isDragging) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.045f))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragging = card
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    // Шаг = высота строки + отступ между строками.
                                    val stride = rowHeight + spacingPx
                                    if (stride > 0) {
                                        val shift = (dragOffset / stride).roundToInt()
                                        if (shift != 0) {
                                            val to = (order.indexOf(card) + shift).coerceIn(0, order.lastIndex)
                                            if (to != order.indexOf(card)) {
                                                order = order.moved(card, to)
                                                dragOffset -= shift * stride
                                            } else {
                                                dragOffset = 0f
                                            }
                                        }
                                    }
                                },
                                onDragEnd = {
                                    dragging = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    dragging = null
                                    dragOffset = 0f
                                },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.Menu, "Перетащить карточку", tint = TextMuted, modifier = Modifier.size(20.dp))
                    Text(
                        card.title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (card in hidden) TextMuted else Color.Unspecified,
                    )
                    if (card in hidden) {
                        Icon(EyeOffIcon, "Скрыта", tint = TextMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        GlowButton("Подтвердить", onClick = { finish() }, modifier = Modifier.fillMaxWidth())
    }
}

/** Отступ между строками режима переноса; шаг переноса считается вместе с ним. */
private val RowSpacing = 8.dp

/** Скрытые карточки внизу экрана: только название и глаз; нажатие открывает меню с «Показать». */
@Composable
fun <T : LayoutCard> HiddenCardsSection(hiddenCards: List<T>, onOpenMenu: (T) -> Unit) {
    if (hiddenCards.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(EyeIcon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
            Text(
                "СКРЫТЫЕ",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.4.sp,
                color = TextMuted,
            )
        }
        hiddenCards.forEach { card ->
            GlassCard(
                Modifier.fillMaxWidth(),
                padding = 12.dp,
                onClick = { onOpenMenu(card) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card.title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Icon(EyeIcon, "Показать", tint = TextMuted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * Метрическая плашка «Сегодня» по идентификатору: все одного размера и высоты,
 * свежие светятся, суточные (Съедено, Сожжено) живут только сегодняшним срезом -
 * нулевые калории плашку не подсвечивают. Самочувствие и Цикл - не метрические,
 * экран рисует их сам.
 */
@Composable
fun MetricPlate(
    plate: TodayPlate,
    latest: LatestValues,
    todaySlice: DaySlice?,
    today: LocalDate,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun staleOn(m: MetricLatest<*>?): LocalDate? = m?.on?.takeIf { it != today }
    when (plate) {
        TodayPlate.STEPS -> MetricCard(
            "Шаги", latest.steps?.value?.let { "%,d".format(it) }, modifier,
            accent = Gold,
            staleOn = staleOn(latest.steps),
            onClick = onOpenMenu,
        )
        TodayPlate.SLEEP -> MetricCard(
            "Сон", latest.sleepMinutes?.value?.let { "${it / 60}" }, modifier,
            accent = Violet,
            unit = latest.sleepMinutes?.value?.let { "ч %02d мин".format(it % 60) } ?: "",
            staleOn = staleOn(latest.sleepMinutes),
            onClick = onOpenMenu,
        )
        TodayPlate.BURNED -> {
            val burned = todaySlice.todayBurned()
            MetricCard(
                "Сожжено", "%,.0f".format(burned), modifier,
                accent = Violet, unit = "ккал",
                muted = burned <= 0.0,
                onClick = onOpenMenu,
            )
        }
        TodayPlate.EATEN -> {
            val nutrition = todaySlice.todayNutrition()
            MetricCard(
                "Съедено", "%,.0f".format(nutrition.kcal), modifier,
                accent = Gold, unit = "ккал",
                sub = nutrition.macros?.let {
                    "Б %,.0f · Ж %,.0f · У %,.0f".format(it.proteinG, it.fatG, it.carbsG)
                },
                muted = !nutrition.hasEaten,
                onClick = onOpenMenu,
            )
        }
        TodayPlate.WEIGHT -> MetricCard(
            "Вес", latest.weightKg?.value?.let { "%,.1f".format(it) }, modifier,
            accent = Violet, unit = "кг",
            staleOn = staleOn(latest.weightKg),
            onClick = onOpenMenu,
        )
        TodayPlate.BODY_FAT -> MetricCard(
            "Процент жира", latest.bodyFatPct?.value?.let { "%,.1f".format(it) }, modifier,
            accent = Violet, unit = "%",
            staleOn = staleOn(latest.bodyFatPct),
            onClick = onOpenMenu,
        )
        TodayPlate.BONE -> MetricCard(
            "Костная масса", latest.boneMassKg?.value?.let { "%,.1f".format(it) }, modifier,
            accent = Violet, unit = "кг",
            staleOn = staleOn(latest.boneMassKg),
            onClick = onOpenMenu,
        )
        TodayPlate.BMR -> MetricCard(
            "Базовый расход", latest.bmrKcal?.value?.let { "%,.0f".format(it) }, modifier,
            accent = Violet, unit = "ккал/дн",
            staleOn = staleOn(latest.bmrKcal),
            onClick = onOpenMenu,
        )
        TodayPlate.WELLBEING, TodayPlate.CYCLE -> Unit
    }
}
