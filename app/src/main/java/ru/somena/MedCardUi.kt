package ru.somena

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.net.Uri
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.somena.core.AnalyteRow
import ru.somena.core.DirChildren
import ru.somena.core.MedFileIndex
import ru.somena.core.MedImportResult
import ru.somena.core.MedKind
import ru.somena.core.MedRecord
import ru.somena.core.MedValidator
import ru.somena.core.StorageEntry
import ru.somena.core.TreeRow
import ru.somena.core.fmt
import ru.somena.core.medRecordsView
import ru.somena.core.numericFieldError
import ru.somena.core.parseLooseDate
import ru.somena.core.parseMedReply
import ru.somena.core.parseOptionalDouble
import ru.somena.core.treeRows
import ru.somena.data.ChatClient
import ru.somena.data.ChatLog
import ru.somena.data.ChatMessage
import ru.somena.data.ChatSettings
import ru.somena.data.MedDocReader
import ru.somena.data.MedStorage
import ru.somena.data.PdfPages
import ru.somena.data.SliceDb
import ru.somena.data.STEP_FAST
import ru.somena.data.StorageFile
import ru.somena.ui.BgBase
import ru.somena.ui.CardLabel
import ru.somena.ui.FolderIcon
import ru.somena.ui.GhostButton
import ru.somena.ui.GlassCard
import ru.somena.ui.GlowButton
import ru.somena.ui.NebulaBackground
import ru.somena.ui.PeriodChip
import ru.somena.ui.ScreenHeader
import ru.somena.ui.TextMuted
import ru.somena.ui.neonSurface

private val MED_LIST_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/**
 * Экран «Медкарта» (спека 0010): список записей трёх видов с поиском и фильтром по виду,
 * всегда по дате новые сверху; ручной ввод, правка и удаление, файловый список Хранилища.
 * Ничего не разбирается и не пишется без явного действия Пользователя; файлы приложение
 * не удаляет и не переименовывает.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedCardScreen(m: Modifier) {
    val context = LocalContext.current
    val db = remember { SliceDb(context) }
    val storage = remember { MedStorage(context) }
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(db.allMed()) }
    var existingUris by remember { mutableStateOf<Set<String>?>(null) }
    var editing by remember { mutableStateOf<MedRecord?>(null) }
    var showFiles by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var kindFilter by remember { mutableStateOf<MedKind?>(null) }

    // Оригиналы проверяются точечно - по одному запросу на запись, без обхода дерева.
    fun reload() {
        records = db.allMed()
        scope.launch {
            existingUris = withContext(Dispatchers.IO) {
                records.mapNotNull { it.fileUri }.filter { storage.documentExists(it) }.toSet()
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    // Пока идёт проверка, пропажи не объявляются: считаем все оригиналы на месте.
    val index = remember(records, existingUris) {
        MedFileIndex(records, existingUris ?: records.mapNotNull { it.fileUri }.toSet())
    }

    Column(m.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                ScreenHeader("Медкарта", "Анализы, обследования и протоколы")
            }
            Box(
                Modifier
                    .size(48.dp)
                    .neonSurface(true, cornerRadius = 100.dp)
                    .clickable { searchOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "Поиск по записям",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(
                Modifier
                    .size(48.dp)
                    .neonSurface(true, cornerRadius = 100.dp)
                    .clickable { showFiles = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    FolderIcon,
                    contentDescription = "Файлы Хранилища",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (records.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "Пока пусто. Заведи запись вручную или приложи медицинский документ " +
                        "скрепкой в Чате - Разбор предложит записать его сюда.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            // Поиск свёрнут до лупы в шапке и разворачивается тапом; крестик
            // очищает запрос и сворачивает обратно.
            if (searchOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Поиск: название, показатель, пометка, дата") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            query = ""
                            searchOpen = false
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Свернуть поиск")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PeriodChip("Все", selected = kindFilter == null, onClick = { kindFilter = null })
                listOf(MedKind.ANALYSIS, MedKind.EXAM).forEach { k ->
                    PeriodChip(k.label, selected = kindFilter == k, onClick = { kindFilter = if (kindFilter == k) null else k })
                }
            }
            val visible = medRecordsView(records, query, kindFilter)
            if (visible.isEmpty()) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Ничего не нашлось: поменяй запрос или сбрось фильтр вида.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visible) { record ->
                        MedRecordCard(
                            record,
                            missingOriginal = index.missingRecords.any { it.id == record.id },
                            onClick = { editing = record },
                        )
                    }
                }
            }
        }
        GlowButton(
            "Добавить вручную",
            onClick = { editing = MedRecord(date = LocalDate.now()) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showFiles) {
        Box(m.fillMaxSize().background(BgBase)) {
            NebulaBackground()
            MedFilesScreen(
                Modifier.fillMaxSize(),
                records = records,
                index = index,
                onChanged = { reload() },
                onBack = { showFiles = false },
            )
        }
    }

    // Редактор - полноэкранный слой в окне приложения: поверх Хранилища, если оба открыты.
    editing?.let { initial ->
        Box(m.fillMaxSize()) {
            MedRecordEditor(
                initial = initial,
                title = if (initial.id == 0L) "Новая запись Медкарты" else "Запись от ${initial.date.format(MED_LIST_DATE)}",
                saveLabel = "Сохранить",
                onSave = { record ->
                    if (record.id == 0L) db.insertMed(record) else db.updateMed(record)
                    editing = null
                    reload()
                },
                onDelete = if (initial.id != 0L) {
                    {
                        db.deleteMed(initial.id)
                        editing = null
                        reload()
                    }
                } else null,
                onDismiss = { editing = null },
            )
        }
    }
}

/** Карточка записи в списке: вид, пометка, дата, сводка и честный статус оригинала. */
@Composable
fun MedRecordCard(record: MedRecord, missingOriginal: Boolean, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CardLabel(record.kind.label)
                    record.mark?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(record.describe(), style = MaterialTheme.typography.bodyMedium)
                when {
                    record.fileUri == null -> Text(
                        "Без оригинала",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    missingOriginal -> Text(
                        "Оригинал не найден: ${record.fileName ?: "?"}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    else -> Text(
                        "Оригинал: ${record.fileName ?: "?"}",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                record.date.format(MED_LIST_DATE),
                style = MaterialTheme.typography.labelLarge,
                color = TextMuted,
            )
        }
    }
}

/** Предпросмотр Разбора файла из Хранилища: черновик, заменяемая запись и исходный файл. */
private data class FileImportState(
    val file: StorageFile,
    val replaceOf: MedRecord?,
    val result: MedImportResult,
    val pagesTotal: Int = 0,
)

/**
 * Баннер предупреждений Предпросмотра Разбора (спека 0010): общий для обоих входов -
 * скрепки в Чате и файла из Хранилища. Дата, замена, дубли, усечённые страницы
 * и отброшенные фрагменты с причинами.
 */
@Composable
fun MedImportWarnings(
    result: MedImportResult,
    pagesTotal: Int = 0,
    replaceOf: MedRecord? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (pagesTotal > PdfPages.MAX_PAGES) {
            Text(
                "В документе $pagesTotal страниц: разобраны первые ${PdfPages.MAX_PAGES}, " +
                    "остальные остались за кадром - пришли их отдельным Разбором.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        replaceOf?.let { old ->
            Text(
                "Заменит ${old.kind.label} от ${old.date.format(MED_LIST_DATE)} " +
                    "(${old.describe()}): первый Разбор вышел кривым - второй поправит.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        result.dateProblem?.let {
            Text("Дата: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        result.duplicateOf?.let { dup ->
            Text(
                "Уже есть ${dup.kind.label} от ${dup.date.format(MED_LIST_DATE)}: " +
                    "если это тот же документ, после записи будет дубль - лишний удали на вкладке Медкарты.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (result.rejected.isNotEmpty()) {
            Text(
                "Не разобрано фрагментов: ${result.rejected.size}",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            result.rejected.take(5).forEach { row ->
                Text(
                    "«${row.raw.take(50)}» - ${row.reason}",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * Файловое дерево Хранилища (спека 0010, тикеты 02 и 05): структура с папками как
 * на диске, уровень читается одним запросом при раскрытии - не всё дерево сразу.
 * Бейджи «разобран», открытие внешним просмотрщиком, привязка файла к записи и
 * Разбор выбранного файла - тем путём, который положен его типу: плотный текст pdf -
 * текстом, скан и картинка - зрением (ADR-0009). Переразбор заменяет запись файла
 * через тот же Предпросмотр; ничего не разбирается без явного выбора Пользователя.
 * Приложение файлы только читает - удаление и переименование остаются за Пользователем.
 */
@Composable
fun MedFilesScreen(
    m: Modifier,
    records: List<MedRecord>,
    index: MedFileIndex,
    onChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { SliceDb(context) }
    val storage = remember { MedStorage(context) }
    val settings = remember { ChatSettings(context) }
    val client = remember {
        ChatClient(settings.endpoint(), log = { line -> ChatLog.append(context, line) })
    }
    var root by remember { mutableStateOf(storage.rootId()) }
    var children by remember { mutableStateOf<Map<String, DirChildren>>(emptyMap()) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selected by remember { mutableStateOf<StorageFile?>(null) }
    var bindingFile by remember { mutableStateOf<StorageFile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var medStep by remember { mutableStateOf(STEP_FAST) }
    var fileImport by remember { mutableStateOf<FileImportState?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            storage.setFolder(uri)
            root = storage.rootId()
            children = emptyMap()
            expanded = emptySet()
            onChanged()
        }
    }

    /** Чтение одного уровня - единственная тяжёлая операция экрана, всегда в фоне. */
    fun loadDir(dirId: String) {
        if (dirId in children) return
        scope.launch {
            val level = withContext(Dispatchers.IO) {
                storage.listChildren(dirId) ?: DirChildren(emptyList(), emptyList())
            }
            children = children + (dirId to level)
        }
    }

    LaunchedEffect(root) { root?.let { loadDir(it) } }

    fun toggleFolder(entry: StorageEntry) {
        if (entry.documentId in expanded) {
            expanded -= entry.documentId
        } else {
            loadDir(entry.documentId)
            expanded += entry.documentId
        }
    }

    val rows = remember(children, expanded, root) {
        treeRows(root?.let { children[it] }, children, expanded)
    }

    fun startFileImport(file: StorageFile) {
        if (busy || fileImport != null) return
        if (!settings.isConfigured) {
            error = "Разбор требует настроенного Чата: адрес Бэкенда и токен - на вкладке «Ещё»."
            return
        }
        busy = true
        error = null
        selected = null
        scope.launch(Dispatchers.IO) {
            // Один конвейер обоих входов (ADR-0009): текст прежде зрения.
            MedDocReader.read(context, Uri.parse(file.uri), file.name, file.mime).fold(
                onSuccess = { parts ->
                    client.askDocumentImport(
                        attachment = parts.text,
                        images = parts.images,
                        question = "",
                        step = medStep,
                    ).fold(
                        onSuccess = { raw ->
                            // Заменяемая запись не считается дублём: переразбор - законный путь.
                            val existing = db.allMed().filterNot { it.fileUri == file.uri }
                            val result = parseMedReply(raw, LocalDate.now(), existing)
                            if (result == null) {
                                error = "ИИ не смог разобрать документ. Попробуй Максимальную ступень."
                            } else {
                                fileImport = FileImportState(file, index.recordOf(file.uri), result, parts.totalPages)
                            }
                        },
                        onFailure = { e -> error = e.message ?: "Разбор не удался." },
                    )
                },
                onFailure = { e -> error = e.message ?: "Разбор не удался." },
            )
            busy = false
        }
    }

    /** «Записать» Разбора файла: файл уже в Хранилище, копия не нужна; замена честная. */
    fun confirmFileImport(record: MedRecord) {
        val state = fileImport ?: return
        val saved = record.copy(
            id = state.replaceOf?.id ?: 0L,
            createdAt = state.replaceOf?.createdAt ?: System.currentTimeMillis(),
            fileUri = state.file.uri,
            fileName = state.file.name,
        )
        if (saved.id == 0L) db.insertMed(saved) else db.updateMed(saved)
        val note = buildString {
            append(
                if (saved.id == 0L) "Записала ${saved.chatSummary()}."
                else "Обновила ${saved.chatSummary()}."
            )
            if (state.result.rejected.isNotEmpty()) {
                append(" Не разобрано фрагментов: ${state.result.rejected.size}.")
            }
            if (state.pagesTotal > PdfPages.MAX_PAGES) {
                append(" Разобраны первые ${PdfPages.MAX_PAGES} из ${state.pagesTotal} страниц.")
            }
        }
        db.addChatMessage(ChatMessage.USER, "Разобрала файл «${state.file.name}» из Хранилища")
        db.addChatMessage(ChatMessage.ASSISTANT, note)
        fileImport = null
        onChanged()
    }

    Column(
        m.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Файлы Хранилища", "Папка с оригиналами документов Медкарты")
        GhostButton("Назад", onBack, Modifier.fillMaxWidth())
        if (storage.folderUri() == null) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "Хранилище - папка на телефоне: выбери её один раз, и приложение увидит " +
                        "всё, что в неё лежит. Удобно предложить папку Документы/Somena и класть " +
                        "в неё файлы с компьютера или проводника. Приложение файлы не удаляет " +
                        "и не переименовывает.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            GlowButton(
                "Выбрать папку",
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Ступень Разбора файла из списка: по умолчанию Быстрая (спека 0010).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Разбор:",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                STEP_LABELS.forEach { (key, label) ->
                    PeriodChip(label, selected = medStep == key, onClick = { medStep = key })
                }
                if (busy) {
                    Text("Разбираю…", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (root != null && root !in children) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Читаю папку…",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (root in children && rows.isEmpty()) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(
                        "В папке пока пусто. Положи туда pdf и картинки - можно в подпапки - " +
                            "они появятся здесь и будут готовы к Разбору.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            rows.forEach { row ->
                when (row) {
                    is TreeRow.Folder -> {
                        GlassCard(
                            Modifier.fillMaxWidth().clickable { toggleFolder(row.entry) },
                        ) {
                            Row(
                                Modifier.padding(start = (row.depth * 20).dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    FolderIcon,
                                    contentDescription = "Папка",
                                    tint = TextMuted,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    row.entry.name.ifBlank { "папка" } +
                                        if (row.expanded && !row.loaded) " …" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    if (row.expanded) "▾" else "▸",
                                    color = TextMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    is TreeRow.File -> {
                        val file = storage.fileOf(row.entry) ?: return@forEach
                        GlassCard(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.padding(start = (row.depth * 20).dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        formatBytes(file.sizeBytes),
                                        color = TextMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                if (index.isParsed(file.uri)) {
                                    PeriodChip("Разобран", selected = true, onClick = {})
                                }
                            }
                            if (selected?.uri == file.uri) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        if (bindingFile?.uri == file.uri) {
                                            "К какой записи привязать этот файл?"
                                        } else {
                                            "Что сделать с файлом?"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (bindingFile?.uri == file.uri) {
                                        if (records.isEmpty()) {
                                            Text(
                                                "Записей пока нет: нажми «Разобрать» - запись появится сама.",
                                                color = TextMuted,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        } else {
                                            // Весь список записей со скроллом: привязка доступна всегда.
                                            Column(
                                                Modifier
                                                    .heightIn(max = 320.dp)
                                                    .verticalScroll(rememberScrollState()),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                records.forEach { record ->
                                                    Text(
                                                        "${record.date.format(MED_LIST_DATE)}, ${record.name()}: ${record.contents()}",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                db.updateMed(record.copy(fileUri = file.uri, fileName = file.name))
                                                                bindingFile = null
                                                                selected = null
                                                                onChanged()
                                                            },
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            GlowButton(
                                                "Разобрать",
                                                onClick = { startFileImport(file) },
                                                enabled = settings.isConfigured && !busy,
                                                modifier = Modifier.weight(1f),
                                            )
                                            GhostButton(
                                                "Открыть",
                                                onClick = { storage.openFile(file) },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            GhostButton(
                                                "Привязать к записи",
                                                onClick = { bindingFile = file },
                                                modifier = Modifier.weight(1f),
                                            )
                                            GhostButton(
                                                "Закрыть",
                                                onClick = { selected = null },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }
                            } else {
                                Box(Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable {
                                    selected = if (selected?.uri == file.uri) null else file
                                })
                            }
                        }
                    }
                }
            }
            GhostButton(
                "Сменить папку",
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // Предпросмотр Разбора файла (тикет 05): замена честно названа, запись - по «Записать».
    fileImport?.let { state ->
        Box(m.fillMaxSize()) {
            MedRecordEditor(
                initial = state.result.draft,
                title = "Разбор файла «${state.file.name}»",
                saveLabel = if (state.replaceOf != null) "Заменить запись" else "Записать",
                onSave = ::confirmFileImport,
                onDismiss = { fileImport = null },
                banner = { MedImportWarnings(state.result, pagesTotal = state.pagesTotal, replaceOf = state.replaceOf) },
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f МБ".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} КБ"
    else -> "$bytes Б"
}

/** Черновик строки показателя в редакторе: всё строками, пока не нажато «Сохранить». */
private data class AnalyteInput(
    val name: String = "",
    val value: String = "",
    val unit: String = "",
    val refLow: String = "",
    val refHigh: String = "",
)

/**
 * Редактор записи Медкарты: ручной ввод, правка существующей и Предпросмотр Разбора
 * документа (баннер с предупреждениями - слот [banner]). Дата обязательна, будущее
 * запрещено; правка значения или референса пересчитывает флаг «вне референса».
 * Пометка - произвольный ярлык вроде «до операции»: задаётся здесь сразу после
 * Разбора или позже правкой записи из списка.
 */
@Composable
fun MedRecordEditor(
    initial: MedRecord,
    title: String,
    saveLabel: String,
    onSave: (MedRecord) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    banner: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(initial.kind) }
    var titleText by remember { mutableStateOf(initial.title ?: "") }
    var dateText by remember {
        mutableStateOf(initial.date.format(MED_LIST_DATE))
    }
    var items by remember {
        mutableStateOf(
            initial.items.map {
                AnalyteInput(
                    it.name, fmt(it.value), it.unit ?: "",
                    it.refLow?.let { v -> fmt(v) } ?: "", it.refHigh?.let { v -> fmt(v) } ?: "",
                )
            }.ifEmpty { listOf(AnalyteInput()) }
        )
    }
    var examType by remember { mutableStateOf(initial.examType ?: "") }
    var conclusion by remember { mutableStateOf(initial.conclusion ?: "") }
    var specialty by remember { mutableStateOf(initial.specialty ?: "") }
    var diagnosesText by remember { mutableStateOf(initial.diagnoses.joinToString("\n")) }
    var recommendations by remember { mutableStateOf(initial.recommendations ?: "") }
    var mark by remember { mutableStateOf(initial.mark ?: "") }

    val today = LocalDate.now()

    fun buildDraft(): MedRecord? {
        val date = parseLooseDate(dateText) ?: return null
        val rows = items.mapNotNull { row ->
            if (row.name.isBlank() && row.value.isBlank() && row.unit.isBlank() &&
                row.refLow.isBlank() && row.refHigh.isBlank()
            ) {
                null
            } else {
                AnalyteRow(
                    name = row.name.trim(),
                    value = parseOptionalDouble(row.value) ?: 0.0,
                    unit = row.unit.trim().ifBlank { null },
                    refLow = parseOptionalDouble(row.refLow),
                    refHigh = parseOptionalDouble(row.refHigh),
                )
            }
        }
        return MedRecord(
            id = initial.id,
            kind = kind,
            date = date,
            createdAt = initial.createdAt,
            fileUri = initial.fileUri,
            fileName = initial.fileName,
            title = titleText.trim().ifBlank { null },
            items = rows,
            examType = examType.trim().ifBlank { null },
            conclusion = conclusion.trim().ifBlank { null },
            specialty = specialty.trim().ifBlank { null },
            diagnoses = diagnosesText.lines().map { it.trim() }.filter { it.isNotEmpty() },
            recommendations = recommendations.trim().ifBlank { null },
            mark = mark.trim().ifBlank { null },
        )
    }

    val draft = buildDraft()
    val errors = buildList {
        if (draft == null) add("Дата не разобралась: формат ДД.ММ.ГГГГ")
        draft?.let { addAll(MedValidator.validate(it, today)) }
        items.forEachIndexed { i, row ->
            if (row.name.isBlank() && (row.value.isNotBlank() || row.refLow.isNotBlank() || row.refHigh.isNotBlank() || row.unit.isNotBlank())) {
                add("Строка ${i + 1}: нет названия показателя")
            }
            // Пустое значение не превращается молча в ноль (правило Profile: мусор - не «пусто»).
            if (row.name.isNotBlank() && row.value.isBlank()) {
                add("Строка ${i + 1}: нет значения")
            }
            numericFieldError(row.value, integer = false)?.let { add("Строка ${i + 1}: значение - $it") }
            numericFieldError(row.refLow, integer = false)?.let { add("Строка ${i + 1}: референс от - $it") }
            numericFieldError(row.refHigh, integer = false)?.let { add("Строка ${i + 1}: референс до - $it") }
        }
    }

    // Полноэкранный слой вместо системного диалога: окно диалога на части прошивок
    // не доносит отступы до контента, и нижние кнопки («Отмена», «Удалить запись»)
    // оставались под панелью навигации. Слой живёт в окне приложения и занимает
    // область вкладки: от статус-бара и панели навигации его отводит Scaffold.
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(BgBase)
            // Слой глух к касаниям мимо полей: список под ним не нажимается.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        NebulaBackground()
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
                ScreenHeader(title, "Запись Медкарты")
                banner()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MedKind.entries.forEach { k ->
                        PeriodChip(k.label, selected = kind == k, onClick = { kind = k })
                    }
                }
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("Дата, ДД.ММ.ГГГГ") },
                    isError = draft == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mark,
                    onValueChange = { mark = it },
                    label = { Text("Пометка") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (kind) {
                    MedKind.ANALYSIS -> {
                        OutlinedTextField(
                            value = titleText,
                            onValueChange = { titleText = it },
                            label = { Text("Название") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        items.forEachIndexed { i, row ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = row.name,
                                        onValueChange = { items = items.updated(i, row.copy(name = it)) },
                                        label = { Text("Показатель") },
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = row.value,
                                        onValueChange = { items = items.updated(i, row.copy(value = it)) },
                                        label = { Text("Значение") },
                                        modifier = Modifier.width(110.dp),
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = row.unit,
                                        onValueChange = { items = items.updated(i, row.copy(unit = it)) },
                                        label = { Text("Единицы") },
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = row.refLow,
                                        onValueChange = { items = items.updated(i, row.copy(refLow = it)) },
                                        label = { Text("Реф. от") },
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = row.refHigh,
                                        onValueChange = { items = items.updated(i, row.copy(refHigh = it)) },
                                        label = { Text("до") },
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "Убрать",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .heightIn(min = 40.dp)
                                            .clickable { items = items.filterIndexed { idx, _ -> idx != i } },
                                    )
                                }
                            }
                        }
                        GhostButton(
                            "+ Показатель",
                            onClick = { items = items + AnalyteInput() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    MedKind.EXAM -> {
                        OutlinedTextField(
                            value = examType,
                            onValueChange = { examType = it },
                            label = { Text("Вид обследования (УЗИ, МРТ…)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = conclusion,
                            onValueChange = { conclusion = it },
                            label = { Text("Заключение") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    MedKind.PROTOCOL -> {
                        OutlinedTextField(
                            value = specialty,
                            onValueChange = { specialty = it },
                            label = { Text("Специальность врача") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = diagnosesText,
                            onValueChange = { diagnosesText = it },
                            label = { Text("Диагнозы, по одному в строке") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = recommendations,
                            onValueChange = { recommendations = it },
                            label = { Text("Рекомендации") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (errors.isNotEmpty()) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            errors.forEach {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (initial.fileName != null) {
                    Text(
                        "Оригинал: ${initial.fileName}",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (initial.fileUri != null) {
                    // Тикет 02: оригинал открывается внешним просмотрщиком и из записи.
                    GhostButton(
                        "Открыть оригинал",
                        onClick = {
                            MedStorage(context).openFile(
                                StorageFile(initial.fileUri, initial.fileName ?: "документ", 0, null)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                GlowButton(
                    saveLabel,
                    onClick = { draft?.let(onSave) },
                    enabled = errors.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GhostButton("Отмена", onDismiss, Modifier.weight(1f))
                    if (onDelete != null) {
                        GhostButton("Удалить запись", onDelete, Modifier.weight(1f))
                    }
                }
            }
    }
}

private fun <T> List<T>.updated(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }
