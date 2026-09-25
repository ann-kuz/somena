package ru.somena

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import ru.somena.core.Wellbeing
import ru.somena.data.SliceDb
import ru.somena.data.WellbeingReminder
import ru.somena.ui.GhostButton
import ru.somena.ui.GlassCard
import ru.somena.ui.GlowButton
import ru.somena.ui.ScaleDots
import ru.somena.ui.TextMuted
import ru.somena.ui.Violet
import ru.somena.ui.CardBorder

/** Блок Самочувствия на экране «Сегодня». */
@Composable
fun WellbeingSection(w: Wellbeing?, onEdit: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Самочувствие", style = MaterialTheme.typography.titleMedium)
            if (w == null) {
                Text("Сегодня ещё не отмечено", color = TextMuted)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScaleDots("Энергия", w.energy)
                    ScaleDots("Настроение", w.mood)
                    ScaleDots("Сон", w.sleepQuality)
                }
                w.note?.takeIf { it.isNotBlank() }?.let {
                    androidx.compose.foundation.text.selection.SelectionContainer(Modifier.fillMaxWidth()) {
                        Text("Заметка: $it", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            GhostButton(if (w == null) "Отметить" else "Изменить", onEdit, Modifier.fillMaxWidth())
        }
    }
}

/** Редактор: три шкалы + заметка + переключение дней задним числом + напоминание. */
@Composable
fun WellbeingEditorDialog(db: SliceDb, initialDate: LocalDate, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var date by remember { mutableStateOf(initialDate) }
    val existing = remember(date) { db.getWellbeing(date) }
    var energy by remember(date) { mutableStateOf((existing?.energy ?: 5).toFloat()) }
    var mood by remember(date) { mutableStateOf((existing?.mood ?: 5).toFloat()) }
    var sleep by remember(date) { mutableStateOf((existing?.sleepQuality ?: 5).toFloat()) }
    var note by remember(date) { mutableStateOf(existing?.note ?: "") }
    var remind by remember { mutableStateOf(WellbeingReminder.isEnabled(context)) }

    // На Android 13+ разрешение на уведомления запрашивается в момент включения галочки.
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    fun onRemindChanged(enabled: Boolean) {
        remind = enabled
        if (enabled && android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF171226),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
        ) {
            Column(
                Modifier.padding(20.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Самочувствие", style = MaterialTheme.typography.titleLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { date = date.minusDays(1) },
                        enabled = date > LocalDate.now().minusYears(2),
                    ) { Icon(Icons.Filled.KeyboardArrowLeft, "День назад", tint = TextMuted) }
                    Text(
                        date.toString(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextMuted,
                    )
                    IconButton(
                        onClick = { date = date.plusDays(1) },
                        enabled = date < LocalDate.now(),
                    ) { Icon(Icons.Filled.KeyboardArrowRight, "День вперёд", tint = TextMuted) }
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
                    Checkbox(checked = remind, onCheckedChange = { onRemindChanged(it) })
                    Text("Напоминать вечером, если день не отмечен", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GhostButton("Отмена", onDismiss, Modifier.weight(1f))
                    GlowButton(
                        "Сохранить",
                        onClick = {
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
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = TextMuted)
        Text(
            "${value.toInt()}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = Violet,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = Wellbeing.MIN.toFloat()..Wellbeing.MAX.toFloat(),
            steps = Wellbeing.MAX - Wellbeing.MIN - 1,
            modifier = Modifier.weight(2f),
            colors = SliderDefaults.colors(
                thumbColor = Violet,
                activeTrackColor = Violet,
                inactiveTrackColor = Color.White.copy(alpha = 0.12f),
            ),
        )
    }
}
