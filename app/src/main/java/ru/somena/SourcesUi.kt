package ru.somena

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.somena.core.HcSource
import ru.somena.core.SourceKind
import ru.somena.core.mergeSources
import ru.somena.data.HcSourceScan
import ru.somena.data.SourceStore
import ru.somena.ui.CardLabel
import ru.somena.ui.PeriodChip
import ru.somena.ui.TextMuted
import ru.somena.ui.Violet

/**
 * Источники данных (ADR-0010) на вкладке «Ещё»: показывает, какие приложения
 * писали каждый тип данных в Health Connect за последние 30 дней. Если тип
 * пишут несколько, Пользователь выбирает один; выбор применяется фильтром
 * чтения при следующем «Обновить» на «Сегодня».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourcesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SourceStore(context) }
    var choices by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var writers by remember { mutableStateOf<Map<SourceKind, List<HcSource>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Источники данных", style = MaterialTheme.typography.titleMedium)
        Text(
            "Приложения, писавшие в Health Connect за последние 30 дней. Если один показатель " +
                "идёт из нескольких приложений, выбери одно: данные будут читаться только из него.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
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
                mergeSources(scan, choices).forEach { g ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CardLabel(g.group.title, Violet)
                        when {
                            g.sources.isEmpty() -> Text(
                                "Никто не пишет",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            !g.needsChoice -> Text(
                                "Пишет: ${g.sources.single().label}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            else -> FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PeriodChip(
                                    label = "Все",
                                    selected = g.selected == null,
                                    onClick = {
                                        store.choose(g.group, null)
                                        choices = store.load()
                                    },
                                )
                                g.sources.forEach { src ->
                                    PeriodChip(
                                        label = src.label,
                                        selected = g.selected == src.packageName,
                                        onClick = {
                                            store.choose(g.group, src.packageName)
                                            choices = store.load()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            "Выбор применится при следующем «Обновить» на «Сегодня».",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    LaunchedEffect(Unit) { rescan() }
}
