package ru.somena

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import ru.somena.core.Wellbeing
import ru.somena.data.SliceDb
import ru.somena.data.WellbeingReminder

/** Блок Самочувствия на экране «Сегодня». */
@Composable
fun WellbeingSection(w: Wellbeing?, onEdit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Самочувствие", style = MaterialTheme.typography.titleMedium)
        if (w == null) {
            Text("Сегодня ещё не отмечено")
        } else {
            MetricRow("Энергия", "${w.energy} из 5")
            MetricRow("Настроение", "${w.mood} из 5")
            MetricRow("Сон", "${w.sleepQuality} из 5")
            w.note?.takeIf { it.isNotBlank() }?.let { Text("Заметка: $it", style = MaterialTheme.typography.bodySmall) }
        }
        OutlinedButton(onClick = onEdit) {
            Text(if (w == null) "Отметить" else "Изменить")
        }
    }
}

/** Редактор: три шкалы + заметка + переключение дней задним числом + напоминание. */
@Composable
fun WellbeingEditorDialog(db: SliceDb, initialDate: LocalDate, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var date by remember { mutableStateOf(initialDate) }
    val existing = remember(date) { db.getWellbeing(date) }
    var energy by remember(date) { mutableStateOf((existing?.energy ?: 3).toFloat()) }
    var mood by remember(date) { mutableStateOf((existing?.mood ?: 3).toFloat()) }
    var sleep by remember(date) { mutableStateOf((existing?.sleepQuality ?: 3).toFloat()) }
    var note by remember(date) { mutableStateOf(existing?.note ?: "") }
    var remind by remember { mutableStateOf(WellbeingReminder.isEnabled(context)) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Самочувствие", style = MaterialTheme.typography.titleLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(onClick = { date = date.minusDays(1) }, enabled = date > WellbeingEditorMinDate) {
                        Text("<")
                    }
                    Text(date.toString(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { date = date.plusDays(1) }, enabled = date < LocalDate.now()) {
                        Text(">")
                    }
                }
                SliderRow("Энергия", energy) { energy = it }
                SliderRow("Настроение", mood) { mood = it }
                SliderRow("Качество сна", sleep) { sleep = it }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Заметка (по желанию)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 3,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remind, onCheckedChange = { remind = it })
                    Text("Напоминать вечером, если день не отмечен", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss) { Text("Отмена") }
                    Button(onClick = {
                        db.upsert(
                            Wellbeing(
                                date = date,
                                energy = Wellbeing.clamp(energy.toInt()),
                                mood = Wellbeing.clamp(mood.toInt()),
                                sleepQuality = Wellbeing.clamp(sleep.toInt()),
                                note = note.takeIf { it.isNotBlank() },
                            )
                        )
                        WellbeingReminder.setEnabled(context, remind)
                        onDismiss()
                    }) { Text("Сохранить") }
                }
            }
        }
    }
}

private val WellbeingEditorMinDate: LocalDate = LocalDate.of(2026, 1, 1)

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text("${value.toInt()}", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = Wellbeing.MIN.toFloat()..Wellbeing.MAX.toFloat(),
            steps = Wellbeing.MAX - Wellbeing.MIN - 1,
            modifier = Modifier.weight(2f),
        )
    }
}
