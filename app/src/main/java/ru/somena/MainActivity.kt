package ru.somena

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

private val HC_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(WeightRecord::class),
    HealthPermission.getReadPermission(BodyFatRecord::class),
    HealthPermission.getReadPermission(BoneMassRecord::class),
    HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
    HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(NutritionRecord::class),
)

private val TABS = listOf("Сегодня", "Отладка HC", "Ещё")

@Composable
fun App() {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text("•") },
                        label = { Text(label) },
                    )
                }
            }
        }
    ) { pad ->
        when (tab) {
            0 -> TodayScreen(Modifier.padding(pad))
            1 -> HcDebugScreen(Modifier.padding(pad))
            else -> MoreScreen(Modifier.padding(pad))
        }
    }
}

@Composable
fun TodayScreen(m: Modifier) {
    Column(
        m.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Somena 0.1 — каркас", style = MaterialTheme.typography.titleLarge)
        Text(
            "Здесь будет «Сегодня»: дневной срез (шаги, сон, расход, съедено, вес, самочувствие) и графики.\n\n" +
                "Сейчас проверь вкладку «Отладка HC»: она показывает, какие источники реально пишут данные в Health Connect."
        )
    }
}

@Composable
fun HcDebugScreen(m: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf<Int?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        scope.launch { granted = HealthProbe.grantedPermissions(context).size }
    }

    fun scan() {
        busy = true
        scope.launch {
            result = try {
                HealthProbe.probe(context)
            } catch (e: Exception) {
                "Ошибка: ${e.message}"
            }
            granted = HealthProbe.grantedPermissions(context).size
            busy = false
        }
    }

    Column(
        m.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Отладка Health Connect", style = MaterialTheme.typography.titleLarge)
        Text(HealthProbe.statusText(context))
        granted?.let {
            Text("Разрешений выдано: $it из ${HC_PERMISSIONS.size}")
        }
        Button(
            onClick = { permissionLauncher.launch(HC_PERMISSIONS) },
            enabled = HealthProbe.isAvailable(context),
        ) { Text("Выдать разрешения") }
        Button(onClick = { scan() }, enabled = HealthProbe.isAvailable(context) && !busy) {
            Text(if (busy) "Читаю…" else "Сканировать последние 7 дней")
        }
        result?.let {
            Text(
                it,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun MoreScreen(m: Modifier) {
    Column(
        m.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Ещё", style = MaterialTheme.typography.titleLarge)
        Text(
            "Заготовка под: чат по данным (нужен ключ proxyapi), вечерний опрос самочувствия, " +
                "экспорт/импорт файла, профиль (рост, возраст, цель по весу)."
        )
    }
}
