package ru.somena

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.somena.core.HcSource
import ru.somena.core.SourceKind
import ru.somena.core.SourceGroupChoice
import ru.somena.core.mergeSources
import ru.somena.data.HcSourceScan
import ru.somena.data.SourceStore
import ru.somena.ui.CardLabel
import ru.somena.ui.PeriodChip
import ru.somena.ui.TextMuted
import ru.somena.ui.Violet

/**
 * Источники данных (ADR-0010), Настройки → Данные: показывает, какие приложения
 * писали каждый тип данных в Health Connect за последние 30 дней, и даёт задать
 * порядок - приоритет, как в Google Fit: за день берётся первый Источник порядка,
 * у которого в этот день есть записи. «Все источники» возвращает чтение без
 * порядка; порядок применяется при следующем «Обновить» на «Сегодня».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourcesSection() {
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
            "Приложения, писавшие в Health Connect за последние 30 дней. Если один показатель " +
                "идёт из нескольких приложений, задай порядок: за день берётся первый в порядке, " +
                "у которого есть записи, - остальные запасные на дни его молчания.",
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
            else -> mergeSources(scan, choices).forEach { g ->
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
                        else -> SourcePriorityRows(g) { order ->
                            store.choose(g.group, order)
                            choices = store.load()
                        }
                    }
                }
            }
        }
        Text(
            "Порядок применится при следующем «Обновить» на «Сегодня».",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    LaunchedEffect(Unit) { rescan() }
}

/**
 * Строки Источников группы со стрелками: номер - место в порядке, стрелки двигают
 * вверх и вниз. Показаны все найденные Источники: непоставленные в порядок стоят
 * после поставленных и читаются только в дни, когда у всех выше записей нет.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcePriorityRows(g: SourceGroupChoice, onOrder: (List<String>) -> Unit) {
    // Действующий порядок: сохранённый плюс новые Источники с конца.
    val ordered = g.priority + g.sources.map { it.packageName }.filter { it !in g.priority }

    fun move(pkg: String, delta: Int) {
        val from = ordered.indexOf(pkg)
        val to = (from + delta).coerceIn(0, ordered.lastIndex)
        if (from < 0 || to == from) return
        val list = ordered.toMutableList()
        list.add(to, list.removeAt(from))
        onOrder(list)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PeriodChip(
                label = "Все источники",
                selected = g.priority.isEmpty(),
                onClick = { onOrder(emptyList()) },
            )
        }
        g.sources.sortedBy { ordered.indexOf(it.packageName) }.forEachIndexed { i, src ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${i + 1}.",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (i < g.priority.size) Violet else TextMuted,
                    modifier = Modifier.size(width = 28.dp, height = 24.dp),
                )
                Text(
                    src.label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (i < g.priority.size) androidx.compose.ui.graphics.Color.Unspecified else TextMuted,
                )
                IconButton(onClick = { move(src.packageName, -1) }, enabled = i > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, "Выше в приоритете", tint = TextMuted, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = { move(src.packageName, +1) }, enabled = i < ordered.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Ниже в приоритете", tint = TextMuted, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
