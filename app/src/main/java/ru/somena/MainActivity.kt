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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import java.time.LocalDate
import ru.somena.core.DaySlice
import ru.somena.data.HcImporter
import ru.somena.data.SliceDb

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

val HC_PERMISSIONS = setOf(
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
    val context = LocalContext.current
    var onboarding by remember { mutableStateOf(!onboardingCompleted(context)) }

    if (onboarding) {
        OnboardingScreen(onDone = {
            setOnboardingCompleted(context)
            onboarding = false
        })
        return
    }

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
            else -> MoreScreen(Modifier.padding(pad), onRepeatOnboarding = { onboarding = true })
        }
    }
}

@Composable
fun TodayScreen(m: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { SliceDb(context) }
    val importer = remember { HcImporter(db) }
    var slice by remember { mutableStateOf<DaySlice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        busy = true
        scope.launch {
            status = try {
                if (HealthProbe.isAvailable(context)) {
                    val imported = importer.importRecent(context)
                    if (imported == 0) "Новых данных нет"
                    else null
                } else "Health Connect недоступен — проверь вкладку «Отладка HC»"
            } catch (e: Exception) {
                "Импорт не удался: ${e.message}"
            }
            slice = db.get(LocalDate.now())
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        m.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Сегодня", style = MaterialTheme.typography.titleLarge)
        val s = slice
        if (s == null) {
            Text(if (busy) "Читаю Health Connect…" else "Данных пока нет — нажми «Обновить»")
        } else {
            MetricRow("Шаги", s.steps?.let { "%,d".format(it) })
            MetricRow("Сон", s.sleepMinutes?.let { "%d ч %02d мин".format(it / 60, it % 60) })
            MetricRow("Сожжено", s.burnedKcal?.let { "%,.0f ккал".format(it) })
            MetricRow("Съедено", s.eatenKcal?.let { "%,.0f ккал".format(it) })
            if (s.proteinG != null || s.fatG != null || s.carbsG != null) {
                Text(
                    "   Б %,.0f · Ж %,.0f · У %,.0f".format(s.proteinG ?: 0.0, s.fatG ?: 0.0, s.carbsG ?: 0.0),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            MetricRow("Вес", s.weightKg?.let { "%,.1f кг".format(it) })
            MetricRow("Жир", s.bodyFatPct?.let { "%,.1f %%".format(it) })
            MetricRow("Кости", s.boneMassKg?.let { "%,.1f кг".format(it) })
            MetricRow("Обмен", s.bmrKcal?.let { "%,.0f ккал/дн".format(it) })
        }
        Button(onClick = { refresh() }, enabled = !busy) {
            Text(if (busy) "Обновляю…" else "Обновить")
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            "Шаги считаются только с браслета. «—» значит «данных нет за день».",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String?) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.padding(end = 12.dp))
        Text(value ?: "—", fontFamily = FontFamily.Monospace)
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
fun MoreScreen(m: Modifier, onRepeatOnboarding: () -> Unit) {
    Column(
        m.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Ещё", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = onRepeatOnboarding) {
            Text("Онбординг источников")
        }
        Text(
            "Заготовка под: чат по данным (нужен ключ proxyapi), вечерний опрос самочувствия, " +
                "экспорт/импорт файла, профиль (рост, возраст, цель по весу)."
        )
    }
}
