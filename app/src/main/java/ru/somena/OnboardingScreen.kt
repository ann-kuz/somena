package ru.somena

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import ru.somena.ui.AccentBrush
import ru.somena.ui.CardBorder
import ru.somena.ui.GhostButton
import ru.somena.ui.GlassCard
import ru.somena.ui.GlowButton
import ru.somena.ui.StepBadge
import ru.somena.ui.TextMuted
import ru.somena.ui.Violet
import ru.somena.ui.neonHalo

/**
 * Онбординг Источников (тикет 02): без тупиков — «Готово» доступно всегда,
 * статусы Источников считаются по факту записей (ADR-0004).
 */
@Composable
fun OnboardingScreen(m: Modifier, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf<Int?>(null) }
    var sources by remember { mutableStateOf<List<SourceStatus>?>(null) }
    var busy by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        scope.launch { granted = HealthProbe.grantedPermissions(context).size }
    }

    fun check() {
        busy = true
        scope.launch {
            granted = HealthProbe.grantedPermissions(context).size
            sources = OnboardingChecker.sourceStatuses(context)
            busy = false
        }
    }

    Column(
        m.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Добро пожаловать в Somena",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            )
            Text("Три шага, и приложение начнёт собирать твои данные в одном месте.", color = TextMuted)
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepBadge(1)
                    Text("Health Connect", style = MaterialTheme.typography.titleMedium)
                }
                Text(HealthProbe.statusText(context), color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                if (!HealthProbe.isAvailable(context)) {
                    GhostButton(
                        "Открыть Health Connect",
                        onClick = {
                            runCatching { context.startActivity(Intent("android.settings.HEALTH_CONNECT_SETTINGS")) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepBadge(2)
                    Text("Разрешения для Somena", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    if (granted == null) "Проверяю…" else "Выдано разрешений: $granted из ${HC_PERMISSIONS.size}",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                GlowButton(
                    "Выдать разрешения",
                    onClick = { permissionLauncher.launch(HC_PERMISSIONS) },
                    enabled = HealthProbe.isAvailable(context),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepBadge(3)
                    Text("Источники", style = MaterialTheme.typography.titleMedium)
                }
                val list = sources
                if (list == null) {
                    Text(
                        if (busy) "Проверяю записи…" else "Нажми «Проверить» ниже",
                        color = TextMuted,
                    )
                } else {
                    list.forEach { st ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusDot(writing = st.writing)
                                Text(st.spec.name, style = MaterialTheme.typography.bodyLarge)
                            }
                            if (st.writing) {
                                val last = OnboardingChecker.formatLastWrite(st)
                                Text(
                                    "Пишет данные: ${st.foundTypes.joinToString()}${if (last.isNotEmpty()) ", последняя запись $last" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                    modifier = Modifier.padding(start = 20.dp),
                                )
                            } else {
                                Text(
                                    st.spec.instructions,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                    modifier = Modifier.padding(start = 20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            "Важно: некоторые приложения (например, Mi Fitness) отправляют в Health Connect " +
                "только новые данные: включи синхронизацию сразу, как поставишь браслет. " +
                "История задним числом может не заливаться.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            GhostButton(
                if (busy) "Проверяю…" else "Проверить",
                onClick = { check() },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
            GlowButton(
                "Готово",
                onClick = onDone,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "«Готово» можно нажать и если какой-то Источник ещё не подключён: онбординг доступен " +
                "заново в Настройках → Данные.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    LaunchedEffect(Unit) { check() }
}

@Composable
private fun StatusDot(writing: Boolean) {
    if (writing) {
        Box(
            Modifier
                .neonHalo(Violet, cornerRadius = 24.dp, glow = 3.dp, alpha = 0.5f)
                .size(8.dp)
                .clip(CircleShape)
                .background(AccentBrush)
        )
    } else {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .border(androidx.compose.foundation.BorderStroke(1.dp, CardBorder), CircleShape)
        )
    }
}
