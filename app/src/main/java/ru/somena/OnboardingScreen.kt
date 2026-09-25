package ru.somena

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch

/**
 * Онбординг Источников (тикет 02): без тупиков — «Готово» доступно всегда,
 * статусы Источников считаются по факту записей (ADR-0004).
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
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
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Добро пожаловать в Somena", style = MaterialTheme.typography.titleLarge)
        Text("Три шага — и приложение начнёт собирать твои данные в одном месте.")

        Text("1. Health Connect", style = MaterialTheme.typography.titleMedium)
        Text(HealthProbe.statusText(context))
        if (!HealthProbe.isAvailable(context)) {
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent("android.settings.HEALTH_CONNECT_SETTINGS")) }
            }) { Text("Открыть Health Connect") }
        }

        Text("2. Разрешения для Somena", style = MaterialTheme.typography.titleMedium)
        Text(if (granted == null) "Проверяю…" else "Выдано разрешений: $granted из 9")
        Button(onClick = { permissionLauncher.launch(HC_PERMISSIONS) }, enabled = HealthProbe.isAvailable(context)) {
            Text("Выдать разрешения")
        }

        Text("3. Источники", style = MaterialTheme.typography.titleMedium)
        val list = sources
        if (list == null) {
            Text(if (busy) "Проверяю записи…" else "Нажми «Проверить» ниже")
        } else {
            list.forEach { st ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        (if (st.writing) "✓ " else "• ") + st.spec.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (st.writing) {
                        val last = OnboardingChecker.formatLastWrite(st)
                        Text(
                            "Пишет данные: ${st.foundTypes.joinToString()}${if (last.isNotEmpty()) ", последняя запись $last" else ""}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(st.spec.instructions, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Text(
            "Важно: Mi Fitness отправляет в Health Connect только новые данные — включи синхронизацию " +
                "сразу, как поставишь браслет. История задним числом не заливается.",
            style = MaterialTheme.typography.bodySmall
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { check() }, enabled = !busy) {
                Text(if (busy) "Проверяю…" else "Проверить")
            }
            Button(onClick = onDone, enabled = !busy) {
                Text("Готово")
            }
        }
        Text(
            "«Готово» можно нажать и если какой-то Источник ещё не подключён — онбординг доступен " +
                "заново во вкладке «Ещё».",
            style = MaterialTheme.typography.bodySmall
        )
    }

    LaunchedEffect(Unit) { check() }
}
