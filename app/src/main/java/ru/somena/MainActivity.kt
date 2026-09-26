package ru.somena

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.somena.core.DaySlice
import ru.somena.core.Profile
import ru.somena.core.ProfileValidator
import ru.somena.core.birthDateFieldError
import ru.somena.core.numericFieldError
import ru.somena.core.parseBirthDate
import ru.somena.core.parseOptionalDouble
import ru.somena.core.parseOptionalInt
import ru.somena.data.HcImporter
import ru.somena.data.ChatLog
import ru.somena.data.ProfileStore
import ru.somena.data.SliceDb
import ru.somena.ui.CardLabel
import ru.somena.ui.GhostButton
import ru.somena.ui.GlassCard
import ru.somena.ui.GlowButton
import ru.somena.ui.Gold
import ru.somena.ui.MetricCard
import ru.somena.ui.NebulaBackground
import ru.somena.ui.NavItem
import ru.somena.ui.ScreenHeader
import ru.somena.ui.SomenaNavBar
import ru.somena.ui.SomenaTheme
import ru.somena.ui.Sparkline
import ru.somena.ui.TextMuted
import ru.somena.ui.Violet

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ru.somena.data.WellbeingReminder.schedule(this, ru.somena.data.WellbeingReminder.isEnabled(this))
        // Приложение всегда тёмное — системные панели принудительно со светлыми иконками.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
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

private val NAV_ITEMS = listOf(
    NavItem(Icons.Filled.Home, "Сегодня"),
    NavItem(Icons.Filled.DateRange, "Графики"),
    NavItem(ru.somena.ui.ChatBubbleIcon, "Чат"),
    NavItem(Icons.Filled.Build, "Отладка HC"),
    NavItem(Icons.Filled.Settings, "Ещё"),
)

@Composable
fun App() {
    val context = LocalContext.current
    var onboarding by remember { mutableStateOf(!onboardingCompleted(context)) }
    var tab by remember { mutableIntStateOf(0) }
    var showCycle by remember { mutableStateOf(false) }
    // Счётчик правок цикла: карточка на «Сегодня» перечитывает базу, когда он растёт.
    var cycleRevision by remember { mutableIntStateOf(0) }

    SomenaTheme {
        Box(Modifier.fillMaxSize()) {
            NebulaBackground()
            if (onboarding) {
                OnboardingScreen(
                    Modifier.fillMaxSize(),
                    onDone = {
                        setOnboardingCompleted(context)
                        onboarding = false
                    },
                )
            } else {
                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = { SomenaNavBar(NAV_ITEMS, tab) { tab = it } },
                ) { pad ->
                    when (tab) {
                        0 -> TodayScreen(
                            Modifier.padding(pad),
                            cycleRevision = cycleRevision,
                            onCycleChanged = { cycleRevision++ },
                            onOpenCycle = { showCycle = true },
                        )
                        1 -> ChartsScreen(Modifier.padding(pad))
                        2 -> ChatScreen(Modifier.padding(pad))
                        3 -> HcDebugScreen(Modifier.padding(pad))
                        else -> MoreScreen(Modifier.padding(pad), onRepeatOnboarding = { onboarding = true })
                    }
                }
                if (showCycle) {
                    Box(Modifier.fillMaxSize().background(ru.somena.ui.BgBase)) {
                        NebulaBackground()
                        CycleScreen(
                            Modifier.fillMaxSize(),
                            onBack = { showCycle = false },
                            onChanged = { cycleRevision++ },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TodayScreen(m: Modifier, cycleRevision: Int, onCycleChanged: () -> Unit, onOpenCycle: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { SliceDb(context) }
    val importer = remember { HcImporter(db) }
    var slice by remember { mutableStateOf<DaySlice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var wellbeing by remember { mutableStateOf(db.dayWellbeing(LocalDate.now())) }
    var showWellbeingEditor by remember { mutableStateOf(false) }
    val weekSteps = remember(db) { db.all().takeLast(7).map { it.steps?.toDouble() } }

    fun refresh() {
        busy = true
        scope.launch {
            status = try {
                if (HealthProbe.isAvailable(context)) {
                    val imported = importer.importRecent(context)
                    if (imported == 0) "Новых данных нет"
                    else null
                } else "Health Connect недоступен: проверь вкладку «Отладка HC»"
            } catch (e: Exception) {
                "Импорт не удался: ${e.message}"
            }
            slice = db.get(LocalDate.now())
            wellbeing = db.dayWellbeing(LocalDate.now())
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    if (showWellbeingEditor) {
        WellbeingEditorDialog(
            db = db,
            initialDate = LocalDate.now(),
            initialSlot = if (wellbeing.any { it.slot == ru.somena.core.Wellbeing.SLOT_FIRST }) {
                ru.somena.core.Wellbeing.SLOT_SECOND
            } else {
                ru.somena.core.Wellbeing.SLOT_FIRST
            },
            onDismiss = {
                showWellbeingEditor = false
                wellbeing = db.dayWellbeing(LocalDate.now())
            },
        )
    }

    Column(
        m.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader("Сегодня", LocalDate.now().format(dateHeaderFormat))
        val s = slice
        if (s == null) {
            GlassCard(Modifier.fillMaxWidth(), padding = 28.dp) {
                Text(
                    if (busy) "Читаю Health Connect…" else "Данных пока нет: нажми «Обновить»",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            // Главная карточка: шаги + мини-график недели
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CardLabel("Шаги", Gold)
                        Text(
                            s.steps?.let { "%,d".format(it) } ?: "—",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-1).sp,
                            color = if (s.steps == null) TextMuted else Color.Unspecified,
                        )
                    }
                    if (weekSteps.count { it != null } >= 2) {
                        Sparkline(weekSteps, Modifier.size(width = 96.dp, height = 56.dp), color = Gold)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    "Сон", s.sleepMinutes?.let { "${it / 60}" }, Modifier.weight(1f),
                    accent = Violet, unit = s.sleepMinutes?.let { "ч %02d мин".format(it % 60) } ?: "",
                )
                MetricCard(
                    "Сожжено", s.burnedKcal?.let { "%,.0f".format(it) }, Modifier.weight(1f),
                    accent = Violet, unit = "ккал",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    "Съедено", s.eatenKcal?.let { "%,.0f".format(it) }, Modifier.weight(1f),
                    accent = Gold, unit = "ккал",
                    sub = if (s.proteinG != null || s.fatG != null || s.carbsG != null) {
                        "Б %,.0f · Ж %,.0f · У %,.0f".format(s.proteinG ?: 0.0, s.fatG ?: 0.0, s.carbsG ?: 0.0)
                    } else null,
                )
                MetricCard(
                    "Вес", s.weightKg?.let { "%,.1f".format(it) }, Modifier.weight(1f),
                    accent = Violet, unit = "кг",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("Жир", s.bodyFatPct?.let { "%,.1f".format(it) }, Modifier.weight(1f), accent = Violet, unit = "%")
                MetricCard("Кости", s.boneMassKg?.let { "%,.1f".format(it) }, Modifier.weight(1f), accent = Violet, unit = "кг")
            }
            MetricCard(
                "Обмен", s.bmrKcal?.let { "%,.0f".format(it) }, Modifier.fillMaxWidth(),
                accent = Violet, unit = "ккал/дн",
            )
        }
        GlowButton(
            if (busy) "Обновляю…" else "Обновить",
            onClick = { refresh() },
            enabled = !busy,
            icon = Icons.Filled.Refresh,
            modifier = Modifier.fillMaxWidth(),
        )
        status?.let {
            SelectionContainer { Text(it, color = TextMuted, style = MaterialTheme.typography.bodySmall) }
        }
        WellbeingSection(wellbeing, onEdit = { showWellbeingEditor = true })
        CycleCard(
            db = db,
            revision = cycleRevision,
            onOpen = onOpenCycle,
            onChanged = onCycleChanged,
        )
        Text(
            "Шаги считаются только с браслета. «—» значит «данных нет за день».",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private val dateHeaderFormat = DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale("ru", "RU"))

@Composable
fun HcDebugScreen(m: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf<Int?>(null) }
    var chatLog by remember { mutableStateOf(ChatLog.get(context)) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

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
        ScreenHeader("Отладка Health Connect", "Статус хаба, записи Источников и журнал чата")
        GlassCard(Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(HealthProbe.statusText(context), style = MaterialTheme.typography.bodyMedium)
            }
            granted?.let {
                Text(
                    "Разрешений выдано: $it из ${HC_PERMISSIONS.size}",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        GlowButton(
            "Выдать разрешения",
            onClick = { permissionLauncher.launch(HC_PERMISSIONS) },
            enabled = HealthProbe.isAvailable(context),
            icon = Icons.Filled.Lock,
            modifier = Modifier.fillMaxWidth(),
        )
        GhostButton(
            if (busy) "Читаю…" else "Сканировать последние 7 дней",
            onClick = { scan() },
            enabled = HealthProbe.isAvailable(context) && !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        result?.let {
            GlassCard(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        it,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                GhostButton(
                    "Скопировать скан",
                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Журнал чата", style = MaterialTheme.typography.titleMedium)
                if (chatLog.isBlank()) {
                    Text(
                        "Пусто: задай вопрос во вкладке «Чат», и здесь появятся запросы к Бэкенду " +
                            "с исходами: удобно копировать и переслать при проблемах.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    SelectionContainer {
                        Text(
                            chatLog,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GhostButton(
                            "Скопировать журнал",
                            onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(chatLog)) },
                            modifier = Modifier.weight(1f),
                        )
                        GhostButton(
                            "Очистить",
                            onClick = {
                                ChatLog.clear(context)
                                chatLog = ""
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MoreScreen(m: Modifier, onRepeatOnboarding: () -> Unit) {
    Column(
        m.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader("Ещё", "Профиль и настройки приложения")
        GlassCard(Modifier.fillMaxWidth()) {
            GhostButton("Онбординг источников", onRepeatOnboarding, Modifier.fillMaxWidth())
        }
        GlassCard(Modifier.fillMaxWidth()) {
            ProfileSection()
        }
        GlassCard(Modifier.fillMaxWidth()) {
            ChatSettingsSection()
        }
        Text(
            "Скоро: экспорт/импорт файла, картинка дня для друзей.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ProfileSection() {
    val context = LocalContext.current
    val store = remember { ProfileStore(context) }
    val saved = remember { store.load() }
    var height by remember { mutableStateOf(saved.heightCm?.toString() ?: "") }
    var birth by remember {
        mutableStateOf(saved.birthDate?.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu")) ?: "")
    }
    var goal by remember { mutableStateOf(saved.goalWeightKg?.let { "%,.1f".format(it) } ?: "") }
    var status by remember { mutableStateOf<String?>(null) }

    val today = LocalDate.now()
    val draft = Profile(
        heightCm = parseOptionalInt(height),
        birthDateIso = parseBirthDate(birth)?.toString(),
        goalWeightKg = parseOptionalDouble(goal),
    )
    val errors = buildList {
        addAll(ProfileValidator.validate(draft, today))
        numericFieldError(height, integer = true)?.let { add(ProfileValidator.Error("heightCm", it)) }
        birthDateFieldError(birth, today)?.let { add(ProfileValidator.Error("birthDate", it)) }
        numericFieldError(goal, integer = false)?.let { add(ProfileValidator.Error("goalWeightKg", it)) }
    }
    fun errorFor(field: String): String? = errors.firstOrNull { it.field == field }?.message

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Профиль", style = MaterialTheme.typography.titleMedium)
        Text(
            "Нужен для осмысленных советов ИИ. Можно оставить пустым.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = height,
            onValueChange = { height = it },
            label = { Text("Рост, см") },
            isError = errorFor("heightCm") != null,
            supportingText = { errorFor("heightCm")?.let { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = birth,
            onValueChange = { birth = it },
            label = { Text("Дата рождения, ДД.ММ.ГГГГ") },
            isError = errorFor("birthDate") != null,
            supportingText = {
                val age = draft.ageYears(today)
                val err = errorFor("birthDate")
                when {
                    err != null -> Text(err)
                    age != null -> Text("Полных лет: $age (считается само)")
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            label = { Text("Цель по весу, кг") },
            isError = errorFor("goalWeightKg") != null,
            supportingText = { errorFor("goalWeightKg")?.let { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        GlowButton(
            "Сохранить",
            onClick = { store.save(draft); status = "Сохранено ✓" },
            enabled = errors.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
        status?.let {
            Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
