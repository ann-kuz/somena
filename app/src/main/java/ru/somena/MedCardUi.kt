package ru.somena

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import ru.somena.core.AnalyteRow
import ru.somena.core.MedKind
import ru.somena.core.MedRecord
import ru.somena.core.MedValidator
import ru.somena.core.fmt
import ru.somena.core.numericFieldError
import ru.somena.core.parseLooseDate
import ru.somena.core.parseOptionalDouble
import ru.somena.data.SliceDb
import ru.somena.ui.CardLabel
import ru.somena.ui.GhostButton
import ru.somena.ui.GlassCard
import ru.somena.ui.GlowButton
import ru.somena.ui.NebulaBackground
import ru.somena.ui.PeriodChip
import ru.somena.ui.ScreenHeader
import ru.somena.ui.TextMuted
import ru.somena.ui.BgBase

private val MED_LIST_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/**
 * Экран «Медкарта» (спека 0010, тикет 01): список записей трёх видов, ручной ввод,
 * правка и удаление. Ничего не разбирается и не пишется без явного действия Пользователя.
 */
@Composable
fun MedCardScreen(m: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = remember { SliceDb(context) }
    var records by remember { mutableStateOf(db.allMed()) }
    var editing by remember { mutableStateOf<MedRecord?>(null) }

    fun reload() {
        records = db.allMed()
    }

    Column(m.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Медкарта", "Анализы, обследования и протоколы")
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
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(records) { record ->
                    MedRecordCard(record, onClick = { editing = record })
                }
            }
        }
        GlowButton(
            "Добавить вручную",
            onClick = { editing = MedRecord(date = LocalDate.now()) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    editing?.let { initial ->
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

/** Карточка записи в списке: вид, дата и сводка. Оригинал не трогаем - только показываем статус. */
@Composable
fun MedRecordCard(record: MedRecord, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CardLabel(record.kind.label)
                Text(record.describe(), style = MaterialTheme.typography.bodyMedium)
                record.fileName?.let {
                    Text(
                        "Оригинал: $it",
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
    var kind by remember { mutableStateOf(initial.kind) }
    var dateText by remember {
        mutableStateOf(initial.date.format(MED_LIST_DATE))
    }
    var items by remember {
        mutableStateOf(
            initial.items.map {
                AnalyteInput(it.name, fmt(it.value), it.unit ?: "", it.refLow?.let { v -> fmt(v) } ?: "", it.refHigh?.let { v -> fmt(v) } ?: "")
            }.ifEmpty { listOf(AnalyteInput()) }
        )
    }
    var examType by remember { mutableStateOf(initial.examType ?: "") }
    var conclusion by remember { mutableStateOf(initial.conclusion ?: "") }
    var specialty by remember { mutableStateOf(initial.specialty ?: "") }
    var diagnosesText by remember { mutableStateOf(initial.diagnoses.joinToString("\n")) }
    var recommendations by remember { mutableStateOf(initial.recommendations ?: "") }

    val today = LocalDate.now()

    fun buildDraft(): MedRecord? {
        val date = parseLooseDate(dateText) ?: return null
        val rows = items.mapIndexedNotNull { i, row ->
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
                ) to i
            }
        }
        return MedRecord(
            id = initial.id,
            kind = kind,
            date = date,
            createdAt = initial.createdAt,
            fileUri = initial.fileUri,
            fileName = initial.fileName,
            items = rows.map { it.first },
            examType = examType.trim().ifBlank { null },
            conclusion = conclusion.trim().ifBlank { null },
            specialty = specialty.trim().ifBlank { null },
            diagnoses = diagnosesText.lines().map { it.trim() }.filter { it.isNotEmpty() },
            recommendations = recommendations.trim().ifBlank { null },
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
            numericFieldError(row.value, integer = false)?.let { add("Строка ${i + 1}: значение - $it") }
            numericFieldError(row.refLow, integer = false)?.let { add("Строка ${i + 1}: референс от - $it") }
            numericFieldError(row.refHigh, integer = false)?.let { add("Строка ${i + 1}: референс до - $it") }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(BgBase)) {
            NebulaBackground()
            Column(
                Modifier
                    .fillMaxSize()
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
                when (kind) {
                    MedKind.ANALYSIS -> {
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
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
}

private fun <T> List<T>.updated(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }
