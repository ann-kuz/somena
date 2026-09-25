package ru.somena

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.somena.core.AiChartSpec
import ru.somena.core.AiMetric
import ru.somena.core.CHAT_SYSTEM_PROMPT
import ru.somena.core.DayData
import ru.somena.core.ImportPreview
import ru.somena.core.ImportValues
import ru.somena.core.ImportWellbeing
import ru.somena.core.MAX_ATTACHMENT_BYTES
import ru.somena.core.MAX_ATTACHMENT_CHARS
import ru.somena.core.WELLBEING_METRICS
import ru.somena.core.Wellbeing
import ru.somena.core.buildChatContext
import ru.somena.core.decodeTableBytes
import ru.somena.core.describe
import ru.somena.core.lastDays
import ru.somena.core.metricSeries
import ru.somena.core.parseAiCharts
import ru.somena.core.parseImportReply
import ru.somena.core.toSlice
import ru.somena.core.toWellbeing
import ru.somena.data.ChatClient
import ru.somena.data.ChatLog
import ru.somena.data.ChatMessage
import ru.somena.data.ChatSettings
import ru.somena.data.ProfileStore
import ru.somena.data.SliceDb
import ru.somena.data.STEP_FAST
import ru.somena.data.STEP_MAX
import ru.somena.ui.AttachFileIcon
import ru.somena.ui.GhostButton
import ru.somena.ui.GlassCard
import ru.somena.ui.GlowButton
import ru.somena.ui.Gold
import ru.somena.ui.Indigo
import ru.somena.ui.PeriodChip
import ru.somena.ui.ScreenHeader
import ru.somena.ui.TextMuted
import ru.somena.ui.Violet
import ru.somena.ui.neonSurface

private val SUGGESTIONS = listOf(
    "Почему вес встал?",
    "Что изменить на этой неделе?",
    "Как сон влияет на самочувствие?",
)

/** Ступень (спека 0004): Пользователь видит имена; названия моделей приходят из /health
 *  Бэкенда (единая точка правды), этот запасной список - только пока /health не ответит. */
private val STEP_LABELS = listOf(STEP_FAST to "Быстрая", STEP_MAX to "Максимальная")
private val STEP_UI_MODELS_FALLBACK = mapOf(STEP_FAST to "gpt-4.1-mini", STEP_MAX to "gpt-5.1")

/** Цвет метрики на графике ИИ: правило цветов метрик спеки 0002, единое для всех экранов. */
fun aiMetricColor(m: AiMetric): Color = when (m) {
    AiMetric.STEPS, AiMetric.EATEN, AiMetric.FAT -> Gold
    AiMetric.CARBS, AiMetric.MOOD -> Indigo
    AiMetric.SLEEP_QUALITY -> TextMuted
    else -> Violet
}

/** Экран «Чат по данным» (тикет 07): свободный вопрос, ответ ИИ, графики от модели, история. */
@Composable
fun ChatScreen(m: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { SliceDb(context) }
    val settings = remember { ChatSettings(context) }
    val client = remember {
        ChatClient(settings.endpoint()) { line -> ChatLog.append(context, line) }
    }
    // Данные для контекста вопроса и графиков ИИ: перечитываются после Разбора таблицы.
    var data by remember {
        mutableStateOf(DayData(db.all().associateBy { it.date }, db.allWellbeing().associateBy { it.date }))
    }
    fun reloadData() {
        data = DayData(db.all().associateBy { it.date }, db.allWellbeing().associateBy { it.date })
    }
    val cycleEntries = remember { db.allCycleDays() }
    var messages by remember { mutableStateOf(db.chatHistory()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf(settings.modelStep) }
    // Подпись моделей у селектора: живёт на Бэкенде, сюда попадает через /health.
    var stepModels by remember { mutableStateOf(STEP_UI_MODELS_FALLBACK) }
    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) {
            client.steps()?.let { stepModels = it }
        }
    }
    // Вложение (спека 0004): имя файла и его текст; Предпросмотр до записи - обязателен.
    var attachment by remember { mutableStateOf<Pair<String, String>?>(null) }
    var importPreview by remember { mutableStateOf<ImportPreview?>(null) }
    var importFileName by remember { mutableStateOf<String?>(null) }
    var importUserText by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val name = fileNameOf(context, uri)
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            }.getOrNull()
            when {
                bytes == null -> error = "Не удалось прочитать таблицу: выбери её заново."
                bytes.size > MAX_ATTACHMENT_BYTES -> error =
                    "Таблица больше 2 МБ: убери лишние листы или разбей на части."
                else -> {
                    val text = decodeTableBytes(bytes)
                    if (text == null) {
                        error = "Не получилось прочитать таблицу: поддерживаются csv, tsv и xlsx."
                    } else if (text.length > MAX_ATTACHMENT_CHARS) {
                        error = "Таблица слишком длинная (${text.length} симв.): разбей её на части, например по полгода."
                    } else {
                        error = null
                        attachment = name to text
                    }
                }
            }
        }
    }

    fun startImport(question: String) {
        val (name, tableText) = attachment ?: return
        busy = true
        input = ""
        scope.launch {
            client.askImport(tableText).fold(
                onSuccess = { raw ->
                    val preview = parseImportReply(raw, data.slicesByDate, data.wellbeingByDate)
                    if (preview == null) {
                        error = "ИИ не смог разобрать таблицу. Нужны колонки с датами и показателями. " +
                            "Если таблица длинная, разбей её на части."
                    } else {
                        importFileName = name
                        importUserText = question
                        importPreview = preview
                    }
                },
                onFailure = { e -> error = e.message ?: "Разбор не удался." },
            )
            busy = false
        }
    }

    fun confirmImport() {
        val preview = importPreview ?: return
        val name = importFileName ?: return
        for (entry in preview.entries) {
            db.upsert(entry.toSlice(db.get(entry.date)))
        }
        for (entry in preview.wellbeing) {
            db.upsert(entry.toWellbeing(db.getWellbeing(entry.date)))
        }
        val note = buildString {
            val parts = buildList {
                if (preview.entries.isNotEmpty()) {
                    add("записала ${preview.entries.size} дн. " +
                        "(новых ${preview.entries.size - preview.replacedCount}, замен ${preview.replacedCount})")
                }
                if (preview.wellbeing.isNotEmpty()) {
                    add("самочувствия ${preview.wellbeing.size} дн. (замен ${preview.wellbeingReplacedCount})")
                }
            }
            append("Разобрала таблицу «$name»: ${parts.joinToString(", ")}.")
            if (preview.rejected.isNotEmpty()) append(" Строк не разобрано: ${preview.rejected.size}.")
        }
        val withText = importUserText?.takeIf { it.isNotBlank() }?.let { ":\n$it" } ?: ""
        db.addChatMessage(ChatMessage.USER, "Приложила таблицу «$name»$withText")
        db.addChatMessage(ChatMessage.ASSISTANT, note)
        attachment = null
        importPreview = null
        importFileName = null
        importUserText = null
        reloadData()
        messages = db.chatHistory()
    }

    fun clearImport() {
        attachment = null
        importPreview = null
        importFileName = null
        importUserText = null
    }

    fun send(question: String) {
        val text = question.trim()
        if (busy || !settings.isConfigured || importPreview != null) return
        if (text.isEmpty() && attachment == null) return
        error = null
        if (attachment != null) {
            startImport(text.ifBlank { "Разбери таблицу." })
            return
        }
        input = ""
        db.addChatMessage(ChatMessage.USER, text)
        messages = db.chatHistory()
        busy = true
        scope.launch {
            client.ask(
                history = db.chatHistory(),
                system = CHAT_SYSTEM_PROMPT,
                context = buildChatContext(
                    today = LocalDate.now(),
                    data = data,
                    profile = ProfileStore(context).load(),
                    cycle = cycleEntries,
                ),
                step = step,
            ).fold(
                onSuccess = { reply -> db.addChatMessage(ChatMessage.ASSISTANT, reply) },
                onFailure = { e -> error = e.message ?: "Чат не удался." },
            )
            busy = false
            messages = db.chatHistory()
        }
    }

    // Новое сообщение, «Думаю…» или Предпросмотр - держим конец диалога на виду.
    LaunchedEffect(messages.size, busy, importPreview) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(m.fillMaxSize().padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenHeader("Чат по данным", "ИИ видит твои срезы, Самочувствие и профиль. Не врач: диагнозов не ставит.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            STEP_LABELS.forEach { (key, label) ->
                PeriodChip(label, selected = step == key, onClick = {
                    step = key
                    settings.modelStep = key
                })
            }
            Spacer(Modifier.weight(1f))
            Text(
                stepModels[step] ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
        if (!settings.isConfigured) {
            GlassCard(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        "Чат не настроен: введи токен приложения на вкладке «Ещё». " +
                            "Остальное приложение работает и без него.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && !busy) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Text(
                                "Спроси что угодно о своих данных: ИИ видит дневные срезы за 30 дней, " +
                                    "Самочувствие и профиль. Попроси показать график, например: «покажи вес за месяц».",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                items(SUGGESTIONS) { q ->
                    PeriodChip(q, selected = false, onClick = { send(q) })
                }
            }
            items(messages) { msg ->
                MessageBubble(msg, data)
            }
            if (busy) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        GlassCard(Modifier.fillMaxWidth(0.5f)) {
                            Text("Думаю…", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            importPreview?.let { preview ->
                item { ImportPreviewCard(preview, onConfirm = ::confirmImport, onCancel = ::clearImport) }
            }
        }
        error?.let {
            SelectionContainer {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        attachment?.let { (name, _) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    AttachFileIcon,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Убрать",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = !busy) { attachment = null },
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AttachButton(
                enabled = settings.isConfigured && !busy && importPreview == null,
                onClick = {
                    picker.launch(
                        arrayOf(
                            "text/csv",
                            "text/comma-separated-values",
                            "text/tab-separated-values",
                            "text/plain",
                            "application/csv",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        )
                    )
                },
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(if (attachment == null) "Спроси о своих данных…" else "Что внести из таблицы?") },
                enabled = settings.isConfigured && !busy && importPreview == null,
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            SendButton(
                enabled = settings.isConfigured && !busy && importPreview == null &&
                    (input.isNotBlank() || attachment != null),
                onClick = { send(input) },
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, data: DayData) {
    if (msg.role == ChatMessage.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                Modifier
                    .fillMaxWidth(0.85f)
                    .neonSurface(active = true, shape = RoundedCornerShape(20.dp))
                    .padding(12.dp),
            ) {
                SelectionContainer { Text(msg.content, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    } else {
        val (text, specs) = remember(msg.content) { parseAiCharts(msg.content) }
        // График привязан к дню ответа: история показывает данные «на момент вопроса».
        val anchor = remember(msg.sentAt) {
            Instant.ofEpochMilli(msg.sentAt).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            GlassCard(Modifier.fillMaxWidth(0.94f)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (text.isNotBlank()) {
                        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
                    }
                    specs.forEach { spec -> AiChartCard(spec, anchor, data) }
                }
            }
        }
    }
}

/** График, построенный моделью: метрики и окно из ответа, линии — из локальных данных. */
@Composable
fun AiChartCard(spec: AiChartSpec, anchor: LocalDate, data: DayData) {
    val dates = lastDays(anchor, spec.windowDays)
    val metrics = spec.metrics.mapNotNull { AiMetric.byKey(it) }
    if (metrics.isEmpty()) return
    val seriesList = metrics.map { m ->
        ChartSeries(m.label, metricSeries(m, dates, data), aiMetricColor(m), m.unit)
    }
    val wbOnly = metrics.all { it in WELLBEING_METRICS }
    LineChart(
        title = spec.title.ifBlank { "График" },
        dates = dates,
        seriesList = seriesList,
        yMin = if (wbOnly) Wellbeing.MIN.toDouble() else null,
        yMax = if (wbOnly) Wellbeing.MAX.toDouble() else null,
    )
}

/** Карточка Предпросмотра (спеки 0004 и 0006): ничего не записано, пока не нажато «Записать». */
@Composable
private fun ImportPreviewCard(preview: ImportPreview, onConfirm: () -> Unit, onCancel: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                buildString {
                    append("Предпросмотр: разобрано дней ${preview.entries.size}")
                    if (preview.wellbeing.isNotEmpty()) append(", самочувствия ${preview.wellbeing.size}")
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                buildString {
                    append("Новых: ${preview.entries.size - preview.replacedCount}, замен: ${preview.replacedCount}")
                    if (preview.wellbeing.isNotEmpty()) {
                        append(", самочувствия замен: ${preview.wellbeingReplacedCount}")
                    }
                    if (preview.rejected.isNotEmpty()) append(", не разобрано: ${preview.rejected.size}")
                },
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Column(
                Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                preview.entries.forEach { entry ->
                    val was = if (entry.old == ImportValues()) "" else " (было: ${entry.old.describe()})"
                    Text("${entry.date.format(fmt)}: ${entry.values.describe()}$was", style = MaterialTheme.typography.bodyMedium)
                }
                preview.wellbeing.forEach { entry ->
                    val was = if (entry.old == ImportWellbeing()) "" else " (было: ${entry.old.describe()})"
                    Text("${entry.date.format(fmt)}: ${entry.values.describe()}$was", style = MaterialTheme.typography.bodyMedium)
                }
                preview.rejected.forEach { row ->
                    Text(
                        "Не разобрано: ${row.raw.take(60)} - ${row.reason}",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlowButton(
                    "Записать",
                    onClick = onConfirm,
                    enabled = preview.entries.isNotEmpty() || preview.wellbeing.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
                GhostButton("Отмена", onClick = onCancel, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Круглая кнопка-скрепка: то же неоновое стекло, что у отправки. */
@Composable
private fun AttachButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .neonSurface(active = enabled, shape = CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AttachFileIcon,
            contentDescription = "Приложить таблицу",
            tint = if (enabled) MaterialTheme.colorScheme.onBackground else TextMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Имя выбранного файла из системного пикера; без него - нейтральное имя. */
private fun fileNameOf(context: android.content.Context, uri: android.net.Uri): String =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ?.takeIf { it.isNotBlank() }
        ?: "таблица.csv"

/** Круглая неоновая кнопка отправки: то же неоновое стекло, в форме круга под иконку. */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .neonSurface(active = enabled, shape = CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Send,
            contentDescription = "Отправить вопрос",
            tint = if (enabled) MaterialTheme.colorScheme.onBackground else TextMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Настройки Чата по данным на вкладке «Ещё»: адрес Бэкенда, токен, очистка истории. */
@Composable
fun ChatSettingsSection() {
    val context = LocalContext.current
    val settings = remember { ChatSettings(context) }
    var url by remember { mutableStateOf(settings.backendUrl) }
    var token by remember { mutableStateOf(settings.appToken) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Чат по данным", style = MaterialTheme.typography.titleMedium)
        Text(
            "Адрес Бэкенда-прокси и токен приложения. Без токена чат не работает, " +
                "остальные вкладки работают всегда.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Адрес Бэкенда") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Токен приложения") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        GlowButton(
            "Сохранить",
            onClick = {
                settings.backendUrl = url
                settings.appToken = token
                status = "Сохранено ✓"
            },
            enabled = url.isBlank() || (url.startsWith("http") && token.isNotBlank()),
            modifier = Modifier.fillMaxWidth(),
        )
        GhostButton(
            "Очистить историю чата",
            onClick = {
                SliceDb(context).clearChat()
                status = "История очищена"
            },
            modifier = Modifier.fillMaxWidth(),
        )
        status?.let {
            SelectionContainer {
                Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
