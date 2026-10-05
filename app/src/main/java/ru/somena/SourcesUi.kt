package ru.somena

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import ru.somena.core.HcSource
import ru.somena.core.SourceGroup
import ru.somena.core.SourceKind
import ru.somena.core.lastWriteLabel
import ru.somena.core.mergeSources
import ru.somena.data.EatenFloorStore
import ru.somena.data.HcSourceScan
import ru.somena.data.ProfileStore
import ru.somena.data.SliceDb
import ru.somena.data.SourceStore
import ru.somena.data.StepsBurnStore
import ru.somena.ui.BodyIcon
import ru.somena.ui.BurnIcon
import ru.somena.ui.CardBorder
import ru.somena.ui.FoodIcon
import ru.somena.ui.GlassCard
import ru.somena.ui.PeriodChip
import ru.somena.ui.PulseIcon
import ru.somena.ui.SleepIcon
import ru.somena.ui.StepsIcon
import ru.somena.ui.TextMuted
import ru.somena.ui.Violet
import ru.somena.ui.detectDragAfterHold

/**
 * Расход от шагов (спека 0017), Настройки → Данные: переключатель, по умолчанию
 * выключен. Включённый считает «Сожжено» от количества шагов как обычную ходьбу
 * по ровной местности: формула от веса (последнее взвешивание), роста, возраста
 * и пола. Применяется к «Сегодня», «Графикам» и Чату по данным при следующем
 * входе на экран.
 */
@Composable
fun StepsBurnSection() {
    val context = LocalContext.current
    val store = remember { StepsBurnStore(context) }
    var enabled by remember { mutableStateOf(store.isEnabled()) }
    // Подсказка о недостающем для формулы: хоть одно взвешивание и дата рождения.
    val hasWeight = remember { SliceDb(context).all().any { it.weightKg != null } }
    val hasBirthDate = remember { ProfileStore(context).load().birthDate != null }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Расход от шагов", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Считать «Сожжено» от количества шагов: как обычная ходьба по ровной " +
                        "местности, по формуле от веса, роста, возраста и пола.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    store.setEnabled(on)
                },
                modifier = Modifier.semantics { contentDescription = "Считать расход от шагов" },
            )
        }
        if (enabled) {
            if (!hasWeight || !hasBirthDate) {
                Text(
                    "Для расчёта нужно хоть одно взвешивание (умные весы, Разбор таблицы или " +
                        "ввод веса вручную) и дата рождения в Профиле. Пока их нет, «Сожжено» " +
                        "показывается как раньше.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Записи расхода из Health Connect не учитываются: тренировки без шагов " +
                    "(велосипед, бассейн) в расчёт не попадут. День без шагов или без веса " +
                    "показывает записанное значение. Выключение возвращает записанные значения.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Порог съеденного (спека 0019), Настройки → Данные: поле с числом ккал, пусто -
 * выключено. День с «Съедено» ниже порога считается днём без еды: его нет на
 * графиках еды и Дефицита, на «Сегодня» и в Чате по данным. Поле принимает
 * только цифры; записи при этом не стираются - очистка порога возвращает всё.
 */
@Composable
fun EatenFloorSection() {
    val context = LocalContext.current
    val store = remember { EatenFloorStore(context) }
    var text by remember {
        mutableStateOf(store.threshold().takeIf { it > 0 }?.toInt()?.toString().orEmpty())
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Порог съеденного", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Дни, где «Съедено» меньше порога, считаются днями без еды: не попадают " +
                    "на графики еды и Дефицита, на «Сегодня» и в Чат по данным. Годится " +
                    "для дней с недописанным дневником еды.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = raw.filter(Char::isDigit).take(4)
                    text = digits
                    store.setThreshold(digits.toDoubleOrNull() ?: 0.0)
                },
                label = { Text("Порог, ккал (пусто - выключено)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Записи не стираются: сотри порог - и скрытые дни вернутся.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Ключ подэкрана Настроек для экрана показателя: «data/<группа>». */
fun sourceGroupPage(group: SourceGroup): String = "${SettingsPages.DATA}/${group.key}"

/** Группа по ключу подэкрана; чужой ключ - null. */
fun sourceGroupFromPage(page: String?): SourceGroup? =
    SourceGroup.entries.firstOrNull { "${SettingsPages.DATA}/${it.key}" == page }

/** Иконка показателя в списке Источников: рисована вручную в ui/Icons. */
private fun SourceGroup.icon(): ImageVector = when (this) {
    SourceGroup.ACTIVITY -> StepsIcon
    SourceGroup.SLEEP -> SleepIcon
    SourceGroup.BURN -> BurnIcon
    SourceGroup.PULSE -> PulseIcon
    SourceGroup.FOOD -> FoodIcon
    SourceGroup.BODY -> BodyIcon
}

/**
 * Источники данных (ADR-0010, спека 0018), Настройки → Данные: список показателей
 * как в Health Connect - иконка, сводка «кто пишет» и вход на экран показателя.
 * Пустые показатели остаются в списке приглушённой секцией: видно, что приложение
 * умеет и что можно подключить. Скан показывает писавших за последние 30 дней.
 */
@Composable
fun SourcesSection(onOpenGroup: (SourceGroup) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SourceStore(context) }
    var choices by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var writers by remember { mutableStateOf<Map<SourceKind, List<HcSource>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf<Int?>(null) }

    fun rescan() {
        busy = true
        scope.launch {
            try {
                writers = HcSourceScan.scan(context)
                choices = store.load()
                error = null
            } catch (e: Exception) {
                error = "Скан не удался: ${e.message}"
            }
            granted = HealthProbe.grantedPermissions(context).size
            busy = false
        }
    }

    // Недостающие разрешения выдаются отсюда же: после обновления приложения
    // новые типы данных требуют отдельного разрешения Health Connect.
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { rescan() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Источники данных", style = MaterialTheme.typography.titleMedium)
        Text(
            "Приложения, писавшие в Health Connect за последние 30 дней. Тапни показатель, " +
                "чтобы увидеть приложения и задать их порядок.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (granted != null && granted!! < HC_PERMISSIONS.size) {
            Text(
                "Выдано разрешений: ${granted} из ${HC_PERMISSIONS.size} - без недостающих " +
                    "часть показателей не читается.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            ru.somena.ui.GlowButton(
                "Выдать разрешения",
                onClick = { permissionLauncher.launch(HC_PERMISSIONS) },
                enabled = HealthProbe.isAvailable(context),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val scan = writers
        when {
            busy -> Text("Читаю Health Connect…", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            error != null -> Text(error!!, color = TextMuted, style = MaterialTheme.typography.bodySmall)
            scan == null -> Unit
            scan.values.all { it.isEmpty() } -> Text(
                "Ни одно приложение ещё не писало данные. Включи синхронизацию в приложениях " +
                    "браслета, весов или еды: они появятся здесь сами.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> {
                val groups = mergeSources(scan, choices)
                val active = groups.filter { it.sources.isNotEmpty() }
                val empty = groups.filter { it.sources.isEmpty() }
                GlassCard(Modifier.fillMaxWidth()) {
                    Column {
                        active.forEachIndexed { i, g ->
                            if (i > 0) {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(CardBorder))
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp)
                                    .clickable(onClickLabel = "открыть показатель") { onOpenGroup(g.group) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Icon(
                                    g.group.icon(),
                                    contentDescription = null,
                                    tint = Violet,
                                    modifier = Modifier.size(22.dp),
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(g.group.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(g.summary(), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                                Icon(
                                    Icons.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
                if (empty.isNotEmpty()) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column {
                            Text(
                                "Пока никто не пишет",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                            empty.forEach { g ->
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Icon(
                                        g.group.icon(),
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Text(g.group.title, style = MaterialTheme.typography.bodyLarge, color = TextMuted)
                                }
                            }
                            Text(
                                "Включи синхронизацию Health Connect в приложениях браслета, весов " +
                                    "или еды - они появятся здесь сами.",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { rescan() }
}

/**
 * Экран показателя (спека 0018): приложения с иконками и свежестью «Последняя
 * запись», порядок задаётся перетаскиванием удержанием (жест общий с переносом
 * плашек «Сегодня») или стрелками - запасной способ для тех, кому тащить
 * неудобно. Порядок применяется при следующем «Обновить» на «Сегодня».
 */
@Composable
fun SourceGroupContent(group: SourceGroup) {
    val context = LocalContext.current
    val store = remember { SourceStore(context) }
    var sources by remember { mutableStateOf<List<HcSource>>(emptyList()) }
    var priority by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun applyOrder(order: List<String>) {
        priority = order
        store.choose(group, order)
    }

    LaunchedEffect(Unit) {
        busy = true
        try {
            val merged = mergeSources(HcSourceScan.scan(context), store.load())
                .first { it.group == group }
            sources = merged.sources
            priority = merged.priority
            error = null
        } catch (e: Exception) {
            error = "Скан не удался: ${e.message}"
        }
        busy = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Если показатель пишут несколько приложений, данные за день берутся у первого " +
                "в списке, у которого в этот день есть записи. Остальные - запасные на дни " +
                "его молчания.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        when {
            busy -> Text("Читаю Health Connect…", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            error != null -> Text(error!!, color = TextMuted, style = MaterialTheme.typography.bodySmall)
            sources.isEmpty() -> Text(
                "Никто не пишет этот показатель за последние 30 дней. Включи синхронизацию " +
                    "Health Connect в приложении браслета, весов или еды - оно появится здесь само.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> {
                if (sources.size > 1) {
                    PeriodChip(
                        label = "Все источники",
                        selected = priority.isEmpty(),
                        onClick = { applyOrder(emptyList()) },
                    )
                    Text(
                        "Удерживай строку и тащи на новое место, или пользуйся стрелками.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SourceOrderRows(sources, priority, ::applyOrder)
                    Text(
                        "Непоставленные в порядок - запас с конца: они читаются в дни, когда " +
                            "у стоящих выше записей нет.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    SourceAppRow(source = sources.single(), number = null, inPriority = false)
                }
                Text(
                    "Порядок применится при следующем «Обновить» на «Сегодня».",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Действующий порядок: сохранённый приоритет, новые Источники - с конца по имени. */
private fun orderOf(sources: List<HcSource>, priority: List<String>): List<HcSource> =
    sources.sortedBy { src ->
        val i = priority.indexOf(src.packageName)
        if (i >= 0) i else priority.size
    }

/**
 * Строки приложений с перетаскиванием: порядок меняется удержанием строки (жест
 * общий с переносом плашек), шаг переноса - высота строки плюс отступ. Порядок
 * живёт в состоянии и читается жестом живьём: pointerInput не перезапускается
 * при смене порядка, замыкание со снимком списка тащило бы по старым местам.
 * Изменения сохраняются сразу, как и у стрелок.
 */
@Composable
private fun SourceOrderRows(
    sources: List<HcSource>,
    priority: List<String>,
    onOrder: (List<String>) -> Unit,
) {
    var ordered by remember { mutableStateOf(orderOf(sources, priority)) }
    LaunchedEffect(sources, priority) { ordered = orderOf(sources, priority) }
    var dragging by remember { mutableStateOf<HcSource?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableStateOf(0) }
    val spacingPx = with(LocalDensity.current) { 4.dp.toPx() }
    val haptic = LocalHapticFeedback.current

    fun reorder(from: Int, to: Int) {
        if (from < 0 || to == from) return
        ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
        onOrder(ordered.map { it.packageName })
    }

    fun move(pkg: String, delta: Int) {
        reorder(ordered.indexOfFirst { it.packageName == pkg }, (ordered.indexOfFirst { it.packageName == pkg } + delta).coerceIn(0, ordered.lastIndex))
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ordered.forEachIndexed { i, src ->
            val isDragging = dragging == src
            Row(
                Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .onSizeChanged { rowHeight = it.height }
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isDragging) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.045f))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .pointerInput(Unit) {
                        detectDragAfterHold(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragging = src
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
                                        val from = ordered.indexOfFirst { it.packageName == src.packageName }
                                        val to = (from + shift).coerceIn(0, ordered.lastIndex)
                                        if (to != from) {
                                            reorder(from, to)
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
            ) {
                SourceAppRow(
                    source = src,
                    number = i + 1,
                    inPriority = src.packageName in priority,
                    onUp = if (i > 0) ({ move(src.packageName, -1) }) else null,
                    onDown = if (i < ordered.lastIndex) ({ move(src.packageName, +1) }) else null,
                )
            }
        }
    }
}

/**
 * Строка приложения: номер места, настоящая иконка из системы (для удалённого -
 * буква), имя и свежесть последней записи, стрелки «выше/ниже». null-номер -
 * единственный пишущий, без стрелок.
 */
@Composable
private fun SourceAppRow(
    source: HcSource,
    number: Int?,
    inPriority: Boolean,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // Иконка читается один раз на пакет: удалённое приложение иконки не отдаёт.
    val icon = remember(source.packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(source.packageName)
            val bmp = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                ?: android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                    .also {
                        drawable.setBounds(0, 0, 96, 96)
                        drawable.draw(android.graphics.Canvas(it))
                    }
            bmp.asImageBitmap()
        }.getOrNull()
    }
    val fresh = lastWriteLabel(source.lastWrite, Instant.now(), ZoneId.systemDefault())

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (number != null) {
            Text(
                "$number.",
                style = MaterialTheme.typography.titleSmall,
                color = if (inPriority) Violet else TextMuted,
                modifier = Modifier.size(width = 28.dp, height = 24.dp),
            )
        }
        Box(
            Modifier
                .padding(end = if (number == null) 0.dp else 4.dp)
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Text(
                    source.label.take(1).uppercase(),
                    color = TextMuted,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                source.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (inPriority) Color.Unspecified else TextMuted,
            )
            if (fresh != null) {
                Text(fresh, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
        if (onUp != null) {
            IconButton(onClick = onUp) {
                Icon(Icons.Filled.KeyboardArrowUp, "Выше в приоритете", tint = TextMuted, modifier = Modifier.size(22.dp))
            }
        }
        if (onDown != null) {
            IconButton(onClick = onDown) {
                Icon(Icons.Filled.KeyboardArrowDown, "Ниже в приоритете", tint = TextMuted, modifier = Modifier.size(22.dp))
            }
        }
    }
}
