package ru.somena

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import android.net.Uri
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.somena.core.AiChartSpec
import ru.somena.core.AiMetric
import ru.somena.core.CHAT_SYSTEM_PROMPT
import ru.somena.core.DayData
import ru.somena.core.DaySlice
import ru.somena.core.ImportPreview
import ru.somena.core.ImportValues
import ru.somena.core.ImportWellbeing
import ru.somena.core.ManualMetric
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
import ru.somena.core.fmt
import ru.somena.core.isDataEntryRequest
import ru.somena.core.lastDays
import ru.somena.core.manualProblem
import ru.somena.core.manualSlicePreview
import ru.somena.core.manualWellbeingPreview
import ru.somena.core.manualWellbeingProblem
import ru.somena.core.metricSeries
import ru.somena.core.parseAiCharts
import ru.somena.core.parseAiDataEntries
import ru.somena.core.parseImportReply
import ru.somena.core.parseManualNumber
import ru.somena.core.parseMedReply
import ru.somena.core.sentenceCaseTyped
import ru.somena.core.toSlice
import ru.somena.core.toWellbeing
import ru.somena.data.ChatClient
import ru.somena.data.ChatMessage
import ru.somena.data.ChatSettings
import ru.somena.data.AppLog
import ru.somena.data.CatalogModel
import ru.somena.data.MODE_CUSTOM
import ru.somena.data.MODE_POPULAR
import ru.somena.data.MedDocReader
import ru.somena.data.MedStorage
import ru.somena.data.PdfPages
import ru.somena.data.PROTOCOL_ANTHROPIC
import ru.somena.data.PROTOCOL_GEMINI
import ru.somena.data.PROTOCOL_OPENAI
import ru.somena.data.ProfileStore
import ru.somena.data.ProxyModels
import ru.somena.data.SliceDb
import ru.somena.data.STEP_FAST
import ru.somena.data.STEP_MAX
import ru.somena.ui.AttachFileIcon
import ru.somena.ui.BgBase
import ru.somena.ui.CardBorder
import ru.somena.ui.GhostButton
import ru.somena.ui.GlassCard
import ru.somena.ui.GlowButton
import ru.somena.ui.Gold
import ru.somena.ui.Indigo
import ru.somena.ui.PeriodChip
import ru.somena.ui.ScreenHeader
import ru.somena.ui.TextMuted
import ru.somena.ui.Violet
import ru.somena.ui.neonHalo
import ru.somena.ui.neonSurface

private val SUGGESTIONS = listOf(
    "Почему вес встал?",
    "Что изменить на этой неделе?",
    "Запиши сожжённые 2100 ккал за 26.09",
)

/** Ступень (спека 0004): Пользователь видит имена; названия моделей у Бэкенда берутся
 *  из /health, у «Своего proxyapi» - из настроек на телефоне; этот запасной список -
 *  только пока /health не ответит. Общий для Чата и экрана файлов Медкарты. */
internal val STEP_LABELS = listOf(STEP_FAST to "Быстрая", STEP_MAX to "Максимальная")
private val STEP_UI_MODELS_FALLBACK = mapOf(STEP_FAST to "gpt-4o-mini", STEP_MAX to "gpt-5.1")

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

/** Источник Предпросмотра Дневных срезов: у каждого - свой итог в истории чата. */
private enum class ImportOrigin { TABLE, CHAT, MANUAL }

/** Экран «Чат по данным» (тикет 07): свободный вопрос, ответ ИИ, графики от модели, история. */
@Composable
fun ChatScreen(m: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { SliceDb(context) }
    // Ручной базовый расход из Профиля: запас для Дефицита, пока весы не передают свой.
    val profileBmr = remember { ProfileStore(context).load().bmrKcal }
    val settings = remember { ChatSettings(context) }
    val client = remember {
        ChatClient(settings.transport(), log = { line -> AppLog.append(context, AppLog.CHAT, line) })
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
    var input by remember { mutableStateOf(TextFieldValue("")) }
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
    // Источник Предпросмотра: таблица, ответ ИИ или ручное внесение - итог записи разный.
    var importOrigin by remember { mutableStateOf(ImportOrigin.TABLE) }
    // Диалог ручного внесения (кнопка «Внести данные»): категория - дата - значение.
    var manualEntry by remember { mutableStateOf(false) }
    // Разбор документа (спека 0010): Ступень выбирается при запуске, по умолчанию Быстрая.
    var medStep by remember { mutableStateOf(STEP_FAST) }
    var medImport by remember { mutableStateOf<MedImportState?>(null) }
    val listState = rememberLazyListState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val name = fileNameOf(context, uri)
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            // Документы (pdf, docx, txt, картинки) - в Разбор документа, таблицы - в Разбор таблицы.
            val isDoc = MedDocReader.kindOf(name, mime) != MedDocReader.Kind.OTHER
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            }.getOrNull()
            when {
                bytes == null -> error = "Не удалось прочитать файл: выбери его заново."
                isDoc -> {
                    // Один конвейер всех входов (ADR-0009): текст прежде зрения.
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
        input = TextFieldValue("")
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
                                importOrigin = ImportOrigin.TABLE
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
        input = TextFieldValue("")
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
        if (importOrigin == ImportOrigin.TABLE && importFileName == null) return
        for (entry in preview.entries) {
            db.upsert(entry.toSlice(db.get(entry.date)))
        }
        for (entry in preview.wellbeing) {
            db.upsert(entry.toWellbeing(db.getWellbeing(entry.date, Wellbeing.SLOT_FIRST)))
        }
        val fmtShort = DateTimeFormatter.ofPattern("dd.MM")
        when (importOrigin) {
            // Ручное внесение: итог перечисляет, что и за какие дни легло в базу.
            ImportOrigin.MANUAL -> {
                val what = buildList {
                    preview.entries.forEach { add("${it.date.format(fmtShort)}: ${it.values.describe()}") }
                    preview.wellbeing.forEach { add("${it.date.format(fmtShort)}: ${it.values.describe()}") }
                }.joinToString("; ")
                db.addChatMessage(ChatMessage.USER, "Внести данные: $what")
                db.addChatMessage(ChatMessage.ASSISTANT, "Записала вручную: $what.")
            }
            ImportOrigin.CHAT -> {
                val note = buildString {
                    append("Записала из ответа")
                    if (preview.entries.isNotEmpty()) {
                        // Даты в итоге записи: сразу видно, на какие дни легли значения.
                        val dates = preview.entries.sortedBy { it.date }.map { it.date.format(fmtShort) }
                        val shown = if (dates.size <= 5) dates.joinToString(", ") else "${dates.size} дн."
                        append(": показатели на $shown")
                        append(" (новых ${preview.entries.size - preview.replacedCount}, замен ${preview.replacedCount})")
                    }
                    if (preview.wellbeing.isNotEmpty()) {
                        append(if (preview.entries.isEmpty()) ": " else ", ")
                        append("самочувствие на ${preview.wellbeing.size} дн. (замен ${preview.wellbeingReplacedCount})")
                    }
                    append(".")
                    if (preview.rejected.isNotEmpty()) append(" Строк не разобрано: ${preview.rejected.size}.")
                }
                db.addChatMessage(ChatMessage.ASSISTANT, note)
            }
            ImportOrigin.TABLE -> {
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
                    append("Разобрала таблицу «${importFileName ?: ""}»: ${parts.joinToString(", ")}.")
                    if (preview.rejected.isNotEmpty()) append(" Строк не разобрано: ${preview.rejected.size}.")
                }
                val withText = importUserText?.takeIf { it.isNotBlank() }?.let { ":\n$it" } ?: ""
                db.addChatMessage(ChatMessage.USER, "Приложила таблицу «${importFileName ?: ""}»$withText")
                db.addChatMessage(ChatMessage.ASSISTANT, note)
            }
        }
        attachment = null
        importPreview = null
        importFileName = null
        importUserText = null
        importOrigin = ImportOrigin.TABLE
        reloadData()
        messages = db.chatHistory()
    }

    fun clearImport() {
        attachment = null
        importPreview = null
        importFileName = null
        importUserText = null
        importOrigin = ImportOrigin.TABLE
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
                input = TextFieldValue("")
                db.addChatMessage(ChatMessage.USER, text)
                messages = db.chatHistory()
                busy = true
                if (isDataEntryRequest(text)) {
                    // Пометка «Внести данные»: маршрут мимо чатовой модели - выделенный разбор
                    // фразы в строгий JSON, как у Разбора таблицы. Чатовая модель на пометку
                    // отвечала «Записываю...» без блока (инцидент 29.09).
                    scope.launch {
                        client.askDataEntry(text).fold(
                            onSuccess = { raw ->
                                val preview = parseImportReply(raw, data.slicesByDate, firstWellbeing(), LocalDate.now())
                                val hasValues = preview != null &&
                                    (preview.entries.isNotEmpty() || preview.wellbeing.isNotEmpty())
                                if (!hasValues) {
                                    error = "Не поняла, что занести. Напиши показатель, значение и дату, например: сожжено 400 за 26.09."
                                } else {
                                    importOrigin = ImportOrigin.CHAT
                                    importPreview = preview
                                }
                            },
                            onFailure = { e -> error = e.message ?: "Разбор не удался." },
                        )
                        busy = false
                    }
                } else {
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
                                    importOrigin = ImportOrigin.CHAT
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
                // Каталог знает человекочитаемое имя (Claude Haiku 4.5), иначе - как есть.
                stepModels[step]?.let { ProxyModels.byId(it)?.title ?: it } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
        if (!settings.isConfigured) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "Чат не настроен: ${settings.notConfiguredHint}. " +
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
        if (attachment == null && importPreview == null) {
            // «Внести данные»: меню категории - дата - значение, вовсе без ИИ
            // (Быстрая ступень обещала «записала», но Предпросмотра не было, инцидент 29.09).
            NeonChip(
                "Внести данные",
                onClick = { manualEntry = true },
                modifier = Modifier.fillMaxWidth(),
                active = manualEntry,
            )
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
                            "text/markdown",
                            "application/csv",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/pdf",
                            "image/*",
                        )
                    )
                },
            )
            OutlinedTextField(
                value = input,
                // Автозаглавие предложений: правим только чистую вставку (core-функция
                // с тестами), курсор из события остаётся валиден - длина не меняется.
                onValueChange = { v -> input = v.copy(text = sentenceCaseTyped(input.text, v.text)) },
                placeholder = when {
                    isDataEntryRequest(input.text) -> ({ Text("Что занести? Например: сожжено 400 за 26.09") })
                    attachment is PendingAttachment.MedDoc -> ({ Text("Что учесть при разборе документа?") })
                    attachment is PendingAttachment.Table -> ({ Text("Что внести из таблицы?") })
                    else -> null
                },
                enabled = settings.isConfigured && !busy && importPreview == null,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f),
            )
            SendButton(
                enabled = settings.isConfigured && !busy && importPreview == null &&
                    (input.text.isNotBlank() || attachment != null),
                onClick = { send(input.text) },
            )
        }
    }

    // Предпросмотр Разбора документа (спека 0010): редактор записи с баннером предупреждений;
    // запись - только по явному «Записать». Полноэкранный слой области вкладки.
    medImport?.let { state ->
        Box(m.fillMaxSize()) {
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

    // Ручное внесение (кнопка «Внести данные»): итог уходит тем же Предпросмотром.
    if (manualEntry) {
        ManualEntryDialog(
            onDismiss = { manualEntry = false },
            onConfirm = { preview ->
                manualEntry = false
                importOrigin = ImportOrigin.MANUAL
                importPreview = preview
            },
            sliceAt = { db.get(it) },
            firstWellbeingAt = { db.getWellbeing(it, Wellbeing.SLOT_FIRST) },
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

/**
 * Настройки Чата по данным (Настройки → Чат): пилюля режима и поля под него.
 * «Популярные API» - каталог proxyapi: адрес и формат запроса подставляются по модели
 * (Claude живёт на /anthropic/v1), остаётся ввести ключ. «Свой API» - формат запроса,
 * адрес, ключ и название модели целиком вручную: OpenAI (включая Qwen и DeepSeek на
 * /openrouter/v1), Claude или Gemini.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatSettingsSection() {
    val context = LocalContext.current
    val settings = remember { ChatSettings(context) }
    var mode by remember { mutableStateOf(settings.mode) }
    var apiKey by remember { mutableStateOf(settings.proxyApiKey) }
    var fastId by remember { mutableStateOf(settings.popularFastId) }
    var maxId by remember { mutableStateOf(settings.popularMaxId) }
    var customProtocol by remember { mutableStateOf(settings.customProtocol) }
    var customModel by remember { mutableStateOf(settings.customModel) }
    var customUrl by remember { mutableStateOf(settings.customBaseUrl) }
    var showInstruction by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (mode == MODE_SERVER_LEGACY) {
            Text(
                "Сейчас Чат работает через сервер Somena (настройка прежней версии). " +
                    "Любой режим ниже переключит его на твой ключ.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PeriodChip("Популярные API", selected = mode == MODE_POPULAR, onClick = { mode = MODE_POPULAR })
            PeriodChip("Свой API", selected = mode == MODE_CUSTOM, onClick = { mode = MODE_CUSTOM })
        }
        if (mode == MODE_CUSTOM) {
            Text("Формат запроса", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PROTOCOL_CHOICES.forEach { (key, label) ->
                    PeriodChip(
                        label,
                        selected = customProtocol == key,
                        onClick = {
                            // Пустой или умолчальный адрес прошлого формата меняется сам.
                            if (customUrl.isBlank() || customUrl == ProxyModels.defaultUrlFor(customProtocol)) {
                                customUrl = ProxyModels.defaultUrlFor(key)
                            }
                            customProtocol = key
                        },
                    )
                }
            }
            Text(
                "${protocolHint(customProtocol)} Обе Ступени Чата поедут на выбранной " +
                    "модели, ключ хранится только на этом телефоне.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                label = { Text("Адрес API") },
                placeholder = { Text(ProxyModels.defaultUrlFor(customProtocol)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Ключ API") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = customModel,
                onValueChange = { customModel = it },
                label = { Text("Название модели") },
                placeholder = { Text(modelPlaceholder(customProtocol)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                "Модели proxyapi: адрес и формат запроса подставляются сами, нужен только " +
                    "ключ. Claude ходит на свой адрес /anthropic/v1, GPT - на OpenAI-совместимый.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Ключ proxyapi") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            GhostButton(
                "Инструкция: как получить ключ",
                onClick = { showInstruction = true },
                modifier = Modifier.fillMaxWidth(),
            )
            CatalogPicker("Быстрая ступень", ProxyModels.FAST, fastId) { fastId = it }
            CatalogPicker("Максимальная ступень", ProxyModels.MAX, maxId) { maxId = it }
            Text(
                "Цена в пилюле - за миллион токенов ввода, дешёвые сверху; тарифы proxyapi " +
                    "на 30.09.2026. Обычный вопрос - несколько тысяч токенов, а разбор pdf на " +
                    "десять страниц - примерно 15 тысяч: скан без текстового слоя уходит " +
                    "картинками и стоит дороже.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        GlowButton(
            "Сохранить",
            onClick = {
                settings.mode = mode
                settings.proxyApiKey = apiKey
                settings.popularFastId = fastId
                settings.popularMaxId = maxId
                settings.customProtocol = customProtocol
                settings.customModel = customModel
                settings.customBaseUrl = customUrl
                status = "Сохранено ✓"
            },
            enabled = if (mode == MODE_CUSTOM) {
                apiKey.isNotBlank() && customModel.isNotBlank() &&
                    (customUrl.isBlank() || customUrl.startsWith("http"))
            } else {
                apiKey.isNotBlank()
            },
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
    if (showInstruction) {
        ProxyApiKeyInstruction(onDismiss = { showInstruction = false })
    }
}

/** Серверный режим владелицы живёт в ChatSettings, интерфейсом больше не выбирается. */
private const val MODE_SERVER_LEGACY = "server"

/** Пилюли формата «Своего API»: формат решает, как клиент собирает запрос. */
private val PROTOCOL_CHOICES = listOf(
    PROTOCOL_OPENAI to "OpenAI",
    PROTOCOL_ANTHROPIC to "Claude",
    PROTOCOL_GEMINI to "Gemini",
)

/** Подсказка формата «Своего API»: где какие модели живут у proxyapi и у самих вендоров. */
private fun protocolHint(protocol: String): String = when (protocol) {
    PROTOCOL_ANTHROPIC -> "Родной формат Anthropic: Claude на /anthropic/v1 у proxyapi " +
        "или api.anthropic.com/v1 у Anthropic."
    PROTOCOL_GEMINI -> "Родной формат Google: Gemini на /google/v1beta у proxyapi " +
        "или generativelanguage.googleapis.com/v1beta у Google."
    else -> "GPT живёт на /openai/v1, Qwen, DeepSeek и Grok - на /openrouter/v1; " +
        "годится и другой OpenAI-совместимый сервис (OpenRouter, Ollama)."
}

/** Пример названия модели по формату: из тех, что реально отвечают. */
private fun modelPlaceholder(protocol: String): String = when (protocol) {
    PROTOCOL_ANTHROPIC -> "например, claude-sonnet-5-5"
    PROTOCOL_GEMINI -> "например, gemini-2.5-flash"
    else -> "gpt-4o-mini или qwen/qwen3.8-27b"
}

/** Пилюли каталога «Популярных API»: название модели и цена ввода, дешёвые сверху. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogPicker(
    title: String,
    catalog: List<CatalogModel>,
    selectedId: String,
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            catalog.forEach { m ->
                PeriodChip(
                    "${m.title} · ${ProxyModels.shortPriceLabel(m)}",
                    selected = selectedId == m.id,
                    onClick = { onPick(m.id) },
                )
            }
        }
    }
}

/** Пошаговая инструкция ключа proxyapi для новичка: плотная подложка, настройки под ней не просвечивают. */
@Composable
private fun ProxyApiKeyInstruction(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Скрим на весь экран и плотная карточка Темы: стеклянный диалог пропускал
        // текст настроек сквозь себя, а системного затемнения не хватало.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(BgBase)
                    .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(24.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Как получить ключ proxyapi", style = MaterialTheme.typography.titleMedium)
                Text(
                    "1. Открой сайт https://proxyapi.ru и зарегистрируйся: почта с паролем " +
                        "или вход через Google.\n\n" +
                        "2. Пополните баланс: раздел «Оплата». Для Чата по данным хватит " +
                        "200-500 ₽: при лёгкой модели их хватает на месяцы.\n\n" +
                        "3. Создай ключ: раздел «API-ключи» → «Создать ключ», придумай имя " +
                        "(например, Somena) и скопируй показанный ключ целиком.\n\n" +
                        "4. Вернись сюда, вставь ключ в поле «Ключ proxyapi» и нажми «Сохранить».\n\n" +
                        "5. Выбери лёгкую и тяжёлую модели - и спрашивай в Чате по данным.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Ключ - это как пароль от кошелька: не пересылай его никому и не " +
                        "публикуй в чатах. Если ключ попал не в те руки - создай новый в том " +
                        "же разделе, а старый удали. При ошибке «Ключ API неверный» проверь, " +
                        "что скопирован целиком, без пробелов.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                GlowButton("Понятно", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Диалог ручного внесения (кнопка «Внести данные»): категория - дата - значение,
 * без ИИ. Дата - пилюлями (сегодня/вчера/позавчера) или календарём, как у цикла;
 * итог уходит тем же Предпросмотром, запись - по явному «Записать».
 */
@Composable
private fun NeonChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    // Стекло Темы с постоянным неоновым ореолом: активная ступень ярче (правило
    // «выбранная пилюля - неоновое стекло», спека 0002, применено к меню внесения).
    val fill = if (active) {
        Brush.linearGradient(listOf(Violet.copy(alpha = 0.34f), Indigo.copy(alpha = 0.20f)))
    } else {
        Brush.linearGradient(listOf(Violet.copy(alpha = 0.16f), Indigo.copy(alpha = 0.10f)))
    }
    val border = if (active) {
        BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.50f), Violet.copy(alpha = 0.40f))))
    } else {
        BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Violet.copy(alpha = 0.22f))))
    }
    Box(
        modifier
            .heightIn(min = 44.dp)
            .neonHalo(Violet, cornerRadius = 24.dp, glow = if (active) 12.dp else 6.dp, alpha = if (active) 0.24f else 0.15f)
            .clip(CircleShape)
            .background(fill)
            .border(border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (active) Color(0xFFF3F0FF) else Color(0xFFDCD6F2),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ManualEntryDialog(
    onDismiss: () -> Unit,
    onConfirm: (ImportPreview) -> Unit,
    sliceAt: (LocalDate) -> DaySlice?,
    firstWellbeingAt: (LocalDate) -> Wellbeing?,
) {
    val today = LocalDate.now()
    var metric by remember { mutableStateOf<ManualMetric?>(null) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var showCalendar by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var value by remember { mutableStateOf("") }
    var energy by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf("") }
    var sleepQ by remember { mutableStateOf("") }

    fun resetValueInputs(m: ManualMetric?, d: LocalDate?) {
        value = ""
        if (m == ManualMetric.WELLBEING) {
            val old = d?.let { firstWellbeingAt(it) }
            energy = old?.energy?.toString() ?: ""
            mood = old?.mood?.toString() ?: ""
            sleepQ = old?.sleepQuality?.toString() ?: ""
        }
    }

    val problem = when (val m = metric) {
        ManualMetric.WELLBEING -> manualWellbeingProblem(energy, mood, sleepQ)
        null -> null
        else -> if (value.isBlank()) null else manualProblem(m, value)
    }
    val ready = when (metric) {
        null -> false
        ManualMetric.WELLBEING -> date != null && problem == null &&
            energy.isNotBlank() && mood.isNotBlank() && sleepQ.isNotBlank()
        else -> date != null && value.isNotBlank() && problem == null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Скрим на весь экран и плотная карточка Темы: полупрозрачное стекло пропускало
        // текст чата сквозь выбор, а системного затемнения не хватало.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(BgBase)
                    .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(24.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    when {
                        metric == null -> "Что занести?"
                        date == null -> "${metric!!.label}: за какой день?"
                        else -> "${metric!!.label} за ${date!!.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (metric == null) {
                    // Категории - сеткой по две: светящееся стекло пилюль одного ритма.
                    ManualMetric.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { m ->
                                NeonChip(
                                    m.label,
                                    onClick = { metric = m },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else if (date == null) {
                    // Даты - пилюлями по две; «Другая дата» - отдельной строкой на всю
                    // ширину: длинная подпись не жмётся в половину экрана.
                    listOf(
                        "Сегодня" to today,
                        "Вчера" to today.minusDays(1),
                        "Позавчера" to today.minusDays(2),
                    ).chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { (label, d) ->
                                NeonChip(
                                    label,
                                    onClick = {
                                        date = d
                                        resetValueInputs(metric, d)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    NeonChip(
                        if (showCalendar) "Свернуть календарь" else "Другая дата",
                        onClick = { showCalendar = !showCalendar },
                        modifier = Modifier.fillMaxWidth(),
                        active = showCalendar,
                    )
                    if (showCalendar) {
                        ManualMonthPicker(month, today, picked = {
                            month = YearMonth.from(it)
                            date = it
                            resetValueInputs(metric, it)
                            showCalendar = false
                        }, onMove = { month = it })
                    }
                } else {
                    val m = metric!!
                    val d = date!!
                    // Что сейчас в базе: замена видна до ввода, а не после «Записать».
                    if (m != ManualMetric.WELLBEING) {
                        val oldField = sliceAt(d)
                        val oldLine = when (m) {
                            ManualMetric.BURNED -> oldField?.burnedKcal
                            ManualMetric.EATEN -> oldField?.eatenKcal
                            ManualMetric.WEIGHT -> oldField?.weightKg
                            ManualMetric.STEPS -> oldField?.steps?.toDouble()
                            else -> oldField?.sleepMinutes?.let { it / 60.0 }
                        }
                        if (oldLine != null) {
                            Text(
                                "Сейчас в базе: ${fmt(oldLine)} ${m.unitHint}",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (m == ManualMetric.WELLBEING) {
                        OutlinedTextField(energy, onValueChange = { energy = it }, label = { Text("Энергия, 0-10") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(mood, onValueChange = { mood = it }, label = { Text("Настроение, 0-10") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(sleepQ, onValueChange = { sleepQ = it }, label = { Text("Качество сна, 0-10") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    } else {
                        OutlinedTextField(
                            value,
                            onValueChange = { value = it },
                            label = { Text("${m.label}, ${m.unitHint}") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                                val preview = if (m == ManualMetric.WELLBEING) {
                                    manualWellbeingPreview(
                                        d, energy.toInt(), mood.toInt(), sleepQ.toInt(), firstWellbeingAt(d),
                                    )
                                } else {
                                    manualSlicePreview(m, parseManualNumber(value)!!, d, sliceAt(d))
                                }
                                onConfirm(preview)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        GhostButton("Отмена", onClick = onDismiss, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Мини-календарь выбора даты: та же сетка-месяц, что у Цикла; будущее недоступно. */
@Composable
private fun ManualMonthPicker(
    month: YearMonth,
    today: LocalDate,
    picked: (LocalDate) -> Unit,
    onMove: (YearMonth) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMove(month.minusMonths(1)) }) {
                Icon(Icons.Filled.KeyboardArrowLeft, "Месяц назад", tint = TextMuted)
            }
            Text(
                month.format(DateTimeFormatter.ofPattern("LLLL uuuu", java.util.Locale("ru", "RU")))
                    .replaceFirstChar { it.uppercase(java.util.Locale("ru", "RU")) },
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = { onMove(month.plusMonths(1)) }) {
                Icon(Icons.Filled.KeyboardArrowRight, "Месяц вперёд", tint = TextMuted)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс").forEach {
                Text(
                    it,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        val leading = month.atDay(1).dayOfWeek.value - 1
        val cells: List<LocalDate?> = List(leading) { null } +
            (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { d ->
                    if (d == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val future = d.isAfter(today)
                        Box(
                            Modifier
                                .weight(1f)
                                .padding(2.dp)
                                .aspectRatio(1f)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    if (d == today) Violet.copy(alpha = 0.25f) else Color.Transparent
                                )
                                .then(
                                    if (future) Modifier else Modifier.clickable { picked(d) }
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${d.dayOfMonth}",
                                fontSize = 14.sp,
                                fontWeight = if (d == today) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium,
                                color = if (future) TextMuted.copy(alpha = 0.4f) else androidx.compose.ui.graphics.Color.Unspecified,
                            )
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
