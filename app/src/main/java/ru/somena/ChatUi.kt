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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import android.net.Uri
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
import ru.somena.core.MED_IMPORT_SYSTEM_PROMPT
import ru.somena.core.MedImportResult
import ru.somena.core.MedRecord
import ru.somena.core.WELLBEING_METRICS
import ru.somena.core.Wellbeing
import ru.somena.core.buildChatContext
import ru.somena.core.decodeTableBytes
import ru.somena.core.describe
import ru.somena.core.lastDays
import ru.somena.core.metricSeries
import ru.somena.core.parseAiCharts
import ru.somena.core.parseAiDataEntries
import ru.somena.core.parseImportReply
import ru.somena.core.parseMedReply
import ru.somena.core.toSlice
import ru.somena.core.toWellbeing
import ru.somena.data.ChatClient
import ru.somena.data.ChatLog
import ru.somena.data.ChatMessage
import ru.somena.data.ChatSettings
import ru.somena.data.MedDocReader
import ru.somena.data.MedStorage
import ru.somena.data.PdfPages
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
    "Запиши сожжённые 2100 ккал за 26.09",
)

/** Ступень (спека 0004): Пользователь видит имена; названия моделей приходят из /health
 *  Бэкенда (единая точка правды), этот запасной список - только пока /health не ответит.
 *  Общий для Чата и экрана файлов Медкарты. */
internal val STEP_LABELS = listOf(STEP_FAST to "Быстрая", STEP_MAX to "Максимальная")
private val STEP_UI_MODELS_FALLBACK = mapOf(STEP_FAST to "gpt-4.1-mini", STEP_MAX to "gpt-5.1")

/** Цвет метрики на графике ИИ: правило цветов метрик спеки 0002, единое для всех экранов. */
fun aiMetricColor(m: AiMetric): Color = when (m) {
    AiMetric.STEPS, AiMetric.EATEN, AiMetric.FAT -> Gold
    AiMetric.CARBS, AiMetric.MOOD -> Indigo
    AiMetric.SLEEP_QUALITY -> TextMuted
    else -> Violet
}

/** Вложение до отправки: таблица или медицинский документ (спека 0010). */
private sealed interface PendingAttachment {
    val name: String

    data class Table(override val name: String, val text: String) : PendingAttachment

    /** Медицинский документ (ADR-0009): плотный текст - текстом, скан или фото - картинками. */
    data class MedDoc(
        override val name: String,
        val text: String?,
        val images: List<String>,
        val uri: String,
        val mime: String?,
        val pagesTotal: Int = 0,
    ) : PendingAttachment {
        val truncated: Boolean get() = pagesTotal > 0 && pagesTotal > PdfPages.MAX_PAGES
    }
}

/** Предпросмотр Разбора документа: черновик записи и исходные данные файла. */
private data class MedImportState(
    val name: String,
    val uri: String,
    val mime: String?,
    val userText: String,
    val result: MedImportResult,
    val pagesTotal: Int = 0,
)

/** Экран «Чат по данным» (тикет 07): свободный вопрос, ответ ИИ, графики от модели, история. */
@Composable
fun ChatScreen(m: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { SliceDb(context) }
    // Ручной обмен из Профиля: запас для Дефицита, пока весы не передают свой.
    val profileBmr = remember { ProfileStore(context).load().bmrKcal }
    val settings = remember { ChatSettings(context) }
    val client = remember {
        ChatClient(settings.endpoint(), log = { line -> ChatLog.append(context, line) })
    }
    // Данные для контекста вопроса и графиков ИИ: перечитываются после Разбора таблицы.
    // Самочувствие группируется по дате: отметок в день бывает две.
    var data by remember {
        mutableStateOf(DayData(db.all().associateBy { it.date }, db.allWellbeing().groupBy { it.date }))
    }
    // Разбор таблицы пишет первую отметку дня, поэтому сравнивает её с прежней первой.
    fun firstWellbeing(): Map<LocalDate, Wellbeing> =
        db.allWellbeing().filter { it.slot == Wellbeing.SLOT_FIRST }.associateBy { it.date }
    fun reloadData() {
        data = DayData(db.all().associateBy { it.date }, db.allWellbeing().groupBy { it.date })
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
    // Вложение (спека 0004): таблица или медицинский документ; Предпросмотр до записи - обязателен.
    var attachment by remember { mutableStateOf<PendingAttachment?>(null) }
    var importPreview by remember { mutableStateOf<ImportPreview?>(null) }
    var importFileName by remember { mutableStateOf<String?>(null) }
    var importUserText by remember { mutableStateOf<String?>(null) }
    // Источник Предпросмотра: таблица вложения или блок «данные» в ответе ИИ - итог записи разный.
    var importFromChat by remember { mutableStateOf(false) }
    // Разбор документа (спека 0010): Ступень выбирается при запуске, по умолчанию Быстрая.
    var medStep by remember { mutableStateOf(STEP_FAST) }
    var medImport by remember { mutableStateOf<MedImportState?>(null) }
    val listState = rememberLazyListState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val name = fileNameOf(context, uri)
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val isPdf = mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)
            val isImage = mime?.startsWith("image/") == true ||
                listOf("png", "jpg", "jpeg", "webp", "heic").any { name.endsWith(it, ignoreCase = true) }
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            }.getOrNull()
            when {
                bytes == null -> error = "Не удалось прочитать файл: выбери его заново."
                isPdf || isImage -> {
                    // Один конвейер обоих входов (ADR-0009): текст прежде зрения.
                    MedDocReader.read(context, uri, name, mime).fold(
                        onSuccess = { parts ->
                            error = null
                            attachment = PendingAttachment.MedDoc(
                                name, parts.text, parts.images, uri.toString(), mime, parts.totalPages,
                            )
                        },
                        onFailure = { e -> error = e.message ?: "Не получилось прочитать документ." },
                    )
                }
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
                        attachment = PendingAttachment.Table(name, text)
                    }
                }
            }
        }
    }

    fun startImport(question: String) {
        val (name, tableText) = attachment as? PendingAttachment.Table ?: return
        busy = true
        input = ""
        scope.launch {
            client.askImport(tableText, question).fold(
                onSuccess = { raw ->
                    val preview = parseImportReply(raw, data.slicesByDate, firstWellbeing())
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

    fun startMedImport(question: String) {
        val doc = attachment as? PendingAttachment.MedDoc ?: return
        busy = true
        input = ""
        scope.launch {
            client.askDocumentImport(
                attachment = doc.text,
                images = doc.images,
                question = question,
                step = medStep,
            ).fold(
                onSuccess = { raw ->
                    val result = parseMedReply(raw, LocalDate.now(), db.allMed())
                    if (result == null) {
                        error = "ИИ не смог разобрать документ. Попробуй Максимальную ступень " +
                            "или пришли документ картинками."
                    } else {
                        medImport = MedImportState(doc.name, doc.uri, doc.mime, question, result, doc.pagesTotal)
                        attachment = null
                    }
                },
                onFailure = { e -> error = e.message ?: "Разбор не удался." },
            )
            busy = false
        }
    }

    /** «Записать» Предпросмотра документа: копия оригинала в Хранилище, запись и итог в истории чата. */
    fun confirmMedImport(record: MedRecord) {
        val state = medImport ?: return
        val stored = runCatching {
            MedStorage(context).copyIntoStorage(Uri.parse(state.uri), state.name, state.mime)
        }.getOrNull()
        val saved = record.copy(
            fileUri = stored?.uri,
            fileName = stored?.name,
            createdAt = System.currentTimeMillis(),
        )
        db.insertMed(saved)
        val note = buildString {
            append("Записала ${saved.chatSummary()}.")
            if (state.result.rejected.isNotEmpty()) {
                append(" Не разобрано фрагментов: ${state.result.rejected.size}.")
            }
            if (state.pagesTotal > PdfPages.MAX_PAGES) {
                append(" Разобраны первые ${PdfPages.MAX_PAGES} из ${state.pagesTotal} страниц.")
            }
            if (stored == null) {
                append(" Оригинал не сохранён: Хранилище не выбрано - файл можно привязать позже.")
            }
        }
        val withText = state.userText.takeIf { it.isNotBlank() }?.let { ":\n$it" } ?: ""
        db.addChatMessage(ChatMessage.USER, "Приложила документ «${state.name}»$withText")
        db.addChatMessage(ChatMessage.ASSISTANT, note)
        medImport = null
        messages = db.chatHistory()
    }

    fun confirmImport() {
        val preview = importPreview ?: return
        if (!importFromChat && importFileName == null) return
        for (entry in preview.entries) {
            db.upsert(entry.toSlice(db.get(entry.date)))
        }
        for (entry in preview.wellbeing) {
            db.upsert(entry.toWellbeing(db.getWellbeing(entry.date, Wellbeing.SLOT_FIRST)))
        }
        val note = if (importFromChat) {
            buildString {
                val parts = buildList {
                    if (preview.entries.isNotEmpty()) {
                        add("показатели на ${preview.entries.size} дн. " +
                            "(новых ${preview.entries.size - preview.replacedCount}, замен ${preview.replacedCount})")
                    }
                    if (preview.wellbeing.isNotEmpty()) {
                        add("самочувствие на ${preview.wellbeing.size} дн. (замен ${preview.wellbeingReplacedCount})")
                    }
                }
                append("Записала из ответа: ${parts.joinToString(", ")}.")
                if (preview.rejected.isNotEmpty()) append(" Строк не разобрано: ${preview.rejected.size}.")
            }
        } else {
            buildString {
                val parts = buildList {
                    if (preview.entries.isNotEmpty()) {
                        add("записала ${preview.entries.size} дн. " +
                            "(новых ${preview.entries.size - preview.replacedCount}, замен ${preview.replacedCount})")
                    }
                    if (preview.wellbeing.isNotEmpty()) {
                        add("самочувствия ${preview.wellbeing.size} дн. (замен ${preview.wellbeingReplacedCount})")
                    }
                }
                append("Разобрала таблицу «${importFileName ?: ""}»: ${parts.joinToString(", ")}.")
                if (preview.rejected.isNotEmpty()) append(" Строк не разобрано: ${preview.rejected.size}.")
            }
        }
        if (!importFromChat) {
            val withText = importUserText?.takeIf { it.isNotBlank() }?.let { ":\n$it" } ?: ""
            db.addChatMessage(ChatMessage.USER, "Приложила таблицу «${importFileName ?: ""}»$withText")
        }
        db.addChatMessage(ChatMessage.ASSISTANT, note)
        attachment = null
        importPreview = null
        importFileName = null
        importUserText = null
        importFromChat = false
        reloadData()
        messages = db.chatHistory()
    }

    fun clearImport() {
        attachment = null
        importPreview = null
        importFileName = null
        importUserText = null
        importFromChat = false
    }

    fun send(question: String) {
        val text = question.trim()
        if (busy || !settings.isConfigured || importPreview != null || medImport != null) return
        if (text.isEmpty() && attachment == null) return
        error = null
        when (attachment) {
            is PendingAttachment.Table -> startImport(text.ifBlank { "Разбери таблицу." })
            is PendingAttachment.MedDoc -> startMedImport(text.ifBlank { "Разбери документ." })
            null -> {
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
                            medcard = db.allMed(),
                        ),
                        step = step,
                    ).fold(
                        onSuccess = { raw ->
                            // Блоки «данные» в обычном ответе: тот же Предпросмотр, что у
                            // Разбора таблицы, запись - только по явному «Записать».
                            val dataReply = parseAiDataEntries(raw, data.slicesByDate, firstWellbeing(), LocalDate.now())
                            db.addChatMessage(ChatMessage.ASSISTANT, dataReply.text)
                            if (dataReply.brokenBlocks > 0) {
                                error = "ИИ попробовала занести данные, но блок не разобрался: попроси повторить."
                            }
                            val p = dataReply.preview
                            if (p != null && (p.entries.isNotEmpty() || p.wellbeing.isNotEmpty() || p.rejected.isNotEmpty())) {
                                importFromChat = true
                                importPreview = p
                            }
                        },
                        onFailure = { e -> error = e.message ?: "Чат не удался." },
                    )
                    busy = false
                    messages = db.chatHistory()
                }
            }
        }
    }

    // Новое сообщение, «Думаю…» или Предпросмотр - держим конец диалога на виду.
    LaunchedEffect(messages.size, busy, importPreview) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(m.fillMaxSize().padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenHeader("Чат по данным", "ИИ видит твои срезы, Самочувствие, профиль и Медкарту.")
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
                Text(
                    "Чат не настроен: введи токен приложения на вкладке «Ещё». " +
                        "Остальное приложение работает и без него.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                        Text(
                            "Спроси что угодно о своих данных: ИИ видит дневные срезы за 30 дней, " +
                                "Самочувствие и профиль. Попроси показать график («покажи вес за месяц») " +
                                "или занеси данные словами: «запиши сожжённые 2100 ккал за 26.09».",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(SUGGESTIONS) { q ->
                    PeriodChip(q, selected = false, onClick = { send(q) })
                }
            }
            items(messages) { msg ->
                MessageBubble(msg, data, profileBmr)
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
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        attachment?.let { att ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        AttachFileIcon,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        att.name,
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
                if (att is PendingAttachment.MedDoc) {
                    // Ступень Разбора документа: по умолчанию Быстрая, сложный бланк - Максимальная.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Разбор:",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        STEP_LABELS.forEach { (key, label) ->
                            PeriodChip(label, selected = medStep == key, onClick = { medStep = key })
                        }
                    }
                }
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
                            "application/pdf",
                            "image/*",
                        )
                    )
                },
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = {
                    Text(
                        when (attachment) {
                            is PendingAttachment.MedDoc -> "Что учесть при разборе документа?"
                            is PendingAttachment.Table -> "Что внести из таблицы?"
                            null -> "Спроси о своих данных…"
                        }
                    )
                },
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

    // Предпросмотр Разбора документа (спека 0010): редактор записи с баннером предупреждений;
    // запись - только по явному «Записать».
    medImport?.let { state ->
        MedRecordEditor(
            initial = state.result.draft,
            title = "Разбор документа «${state.name}»",
            saveLabel = "Записать",
            onSave = ::confirmMedImport,
            onDismiss = { medImport = null },
            banner = { MedImportWarnings(state.result, pagesTotal = state.pagesTotal) },
        )
    }
}

/** Длинное нажатие на ответ копирует его текст: без выделения текста, которое на
 *  части прошивок рисует тёмные прямоугольники поверх пузырей. */
@Composable
private fun MessageBubble(msg: ChatMessage, data: DayData, profileBmr: Double?) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    if (msg.role == ChatMessage.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                Modifier
                    .fillMaxWidth(0.85f)
                    .neonSurface(active = true, cornerRadius = 20.dp)
                    .padding(12.dp),
            ) {
                Text(msg.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else {
        val (text, specs) = remember(msg.content) { parseAiCharts(msg.content) }
        // График привязан к дню ответа: история показывает данные «на момент вопроса».
        val anchor = remember(msg.sentAt) {
            Instant.ofEpochMilli(msg.sentAt).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        fun copyAnswer() {
            if (text.isBlank()) return
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            GlassCard(
                Modifier
                    .fillMaxWidth(0.94f)
                    .pointerInput(msg.content) {
                        detectTapGestures(onLongPress = { copyAnswer() })
                    }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (text.isNotBlank()) {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                    specs.forEach { spec -> AiChartCard(spec, anchor, data, profileBmr) }
                }
            }
        }
    }
}

/** График, построенный моделью: метрики и окно из ответа, линии — из локальных данных. */
@Composable
fun AiChartCard(spec: AiChartSpec, anchor: LocalDate, data: DayData, profileBmr: Double? = null) {
    val dates = lastDays(anchor, spec.windowDays)
    val metrics = spec.metrics.mapNotNull { AiMetric.byKey(it) }
    if (metrics.isEmpty()) return
    val seriesList = metrics.map { m ->
        ChartSeries(m.label, metricSeries(m, dates, data, profileBmr), aiMetricColor(m), m.unit)
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
            .neonSurface(active = enabled, cornerRadius = 100.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AttachFileIcon,
            contentDescription = "Приложить таблицу или документ",
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
            .neonSurface(active = enabled, cornerRadius = 100.dp)
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
            Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
