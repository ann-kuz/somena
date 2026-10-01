package ru.somena

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
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
import ru.somena.core.Sex
import ru.somena.core.TodayLayout
import ru.somena.core.TodayPlate
import ru.somena.core.birthDateFieldError
import ru.somena.core.hiddenAs
import ru.somena.core.isMetric
import ru.somena.core.latestValues
import ru.somena.core.manualMetric
import ru.somena.core.numericFieldError
import ru.somena.core.parseBirthDate
import ru.somena.core.parseOptionalDouble
import ru.somena.core.parseOptionalInt
import ru.somena.core.todayPlatesFor
import ru.somena.data.HcImporter
import ru.somena.data.ProfileStore
import ru.somena.data.SliceDb
import ru.somena.data.CardLayoutStore
import ru.somena.ui.GhostButton
import ru.somena.ui.GlassCard
import ru.somena.ui.GlowButton
import ru.somena.ui.NebulaBackground
import ru.somena.ui.NavItem
import ru.somena.ui.PeriodChip
import ru.somena.ui.ScreenHeader
import ru.somena.ui.SomenaNavBar
import ru.somena.ui.SomenaTheme
import ru.somena.ui.TextMuted

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ru.somena.data.WellbeingReminder.schedule(this, ru.somena.data.WellbeingReminder.isEnabled(this))
        // Падения пишутся в Журнал (Настройки → Отладка) до передачи системному обработчику.
        val appContext = applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                ru.somena.data.AppLog.append(appContext, ru.somena.data.AppLog.ERROR, "падение: ${e.javaClass.simpleName}: ${e.message}")
            }
            defaultHandler?.uncaughtException(t, e)
        }
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
    HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(NutritionRecord::class),
)

private val NAV_ITEMS = listOf(
    NavItem(Icons.Filled.Home, "Сегодня"),
    NavItem(Icons.Filled.DateRange, "Графики"),
    NavItem(ru.somena.ui.ChatBubbleIcon, "Чат"),
    NavItem(Icons.Filled.Favorite, "Медкарта"),
    NavItem(Icons.Filled.Settings, "Настройки"),
)

@Composable
fun App() {
    val context = LocalContext.current
    var onboarding by remember { mutableStateOf(!onboardingCompleted(context)) }
    var tab by remember { mutableIntStateOf(0) }
    var showCycle by remember { mutableStateOf(false) }
    // Подэкран Настроек (null - корневое меню): кнопка «назад» ходит по нему же.
    var settingsPage by remember { mutableStateOf<String?>(null) }
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
                        3 -> MedCardScreen(Modifier.padding(pad))
                        else -> SettingsScreen(
                            Modifier.padding(pad),
                            page = settingsPage,
                            onPage = { settingsPage = it },
                            onRepeatOnboarding = {
                                settingsPage = null
                                onboarding = true
                            },
                            onOpenToday = {
                                settingsPage = null
                                tab = 0
                            },
                        )
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
    // Календарь цикла скрыт для пола «м»: пол читается один раз при входе на экран.
    val sex = remember { ProfileStore(context).load().sex }
    // Порядок и скрытость плашек - настройка Пользователя, хранится локально.
    val plates = remember { todayPlatesFor(sex == Sex.MALE) }
    val layoutStore = remember { CardLayoutStore(context, "today_order", "today_hidden", TodayPlate::byId) }
    var layout by remember { mutableStateOf(layoutStore.load(plates)) }
    fun saveLayout(l: TodayLayout) {
        layout = l
        layoutStore.save(l)
    }

    // Последние известные значения: каждая метрика из своего самого позднего дня,
    // свежесть (получено сегодня) решает подсветку карточек. Суточные (Съедено
    // с БЖУ, Сожжено) читаются только из сегодняшнего среза - вчерашнее не подтекает.
    // День не кэшируется: полночь посреди сеанса не оставляет вчерашнюю свежесть.
    val today = LocalDate.now()
    var latest by remember { mutableStateOf(latestValues(db.all())) }
    var todaySlice by remember(today) { mutableStateOf<DaySlice?>(db.get(today)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var wellbeing by remember { mutableStateOf(db.dayWellbeing(LocalDate.now())) }
    var showWellbeingEditor by remember { mutableStateOf(false) }

    // Меню плашки, окошко ручного ввода и режим переноса.
    var menuPlate by remember { mutableStateOf<TodayPlate?>(null) }
    var entryPlate by remember { mutableStateOf<TodayPlate?>(null) }
    var reorder by remember { mutableStateOf(false) }

    fun reload() {
        latest = latestValues(db.all())
        todaySlice = db.get(today)
        wellbeing = db.dayWellbeing(today)
    }

    fun refresh() {
        busy = true
        scope.launch {
            status = try {
                if (HealthProbe.isAvailable(context)) {
                    val imported = importer.importRecent(context)
                    if (imported == 0) "Новых данных нет" else null
                } else "Health Connect недоступен: проверь Настройки → Отладка"
            } catch (e: Exception) {
                ru.somena.data.AppLog.append(
                    context,
                    ru.somena.data.AppLog.ERROR,
                    "импорт Health Connect не удался: ${e.message}",
                )
                "Импорт не удался: ${e.message}"
            }
            reload()
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
        if (latest.isEmpty) {
            GlassCard(Modifier.fillMaxWidth(), padding = 28.dp) {
                Text(
                    if (busy) "Читаю Health Connect…" else "Данных пока нет: нажми «Обновить» " +
                        "или внеси сама через меню плашки",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        // Плашки в порядке Пользователя: метрики идут парами одного размера,
        // Самочувствие и Цикл - полными строками на своих местах порядка.
        val visible = layout.visible
        var i = 0
        while (i < visible.size) {
            val plate = visible[i]
            if (!plate.isMetric) {
                if (plate == TodayPlate.WELLBEING) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClickLabel = "открыть меню плашки") { menuPlate = plate },
                    ) {
                        WellbeingSection(wellbeing, onEdit = { showWellbeingEditor = true })
                    }
                } else {
                    CycleCard(
                        db = db,
                        revision = cycleRevision,
                        onOpen = { menuPlate = plate },
                        onOpenCalendar = onOpenCycle,
                        onChanged = onCycleChanged,
                    )
                }
                i++
            } else {
                val row = mutableListOf(plate)
                i++
                if (i < visible.size && visible[i].isMetric) {
                    row += visible[i]
                    i++
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach {
                        MetricPlate(
                            plate = it,
                            latest = latest,
                            todaySlice = todaySlice,
                            today = today,
                            onOpenMenu = { menuPlate = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
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
        Text(
            "Данные читаются из Health Connect; если пишут несколько приложений, порядок " +
                "источников задаётся в Настройках → Данные: за день берётся первый в порядке, " +
                "у которого есть записи. Светящиеся плашки получены сегодня, серые " +
                "с подписью «на ДД.ММ» - раньше; Съедено и Сожжено копятся за день и новым " +
                "днём начинаются с нуля. Нажатие на плашку открывает меню: внести данные, " +
                "скрыть или переместить; скрытые собираются внизу экрана.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        HiddenCardsSection(
            hiddenCards = layout.order.filter { it in layout.hidden },
            onOpenMenu = { menuPlate = it },
        )
    }

    menuPlate?.let { plate ->
        CardMenuDialog(
            card = plate,
            isHidden = plate in layout.hidden,
            canEnter = true,
            enterLabel = if (plate == TodayPlate.CYCLE) "Открыть календарь" else "Внести данные",
            onEnterData = {
                menuPlate = null
                when (plate) {
                    TodayPlate.WELLBEING -> showWellbeingEditor = true
                    TodayPlate.CYCLE -> onOpenCycle()
                    else -> entryPlate = plate
                }
            },
            onMove = {
                menuPlate = null
                reorder = true
            },
            onHide = {
                saveLayout(layout.hiddenAs(plate, hide = true))
                menuPlate = null
            },
            onShow = {
                saveLayout(layout.hiddenAs(plate, hide = false))
                menuPlate = null
            },
            onDismiss = { menuPlate = null },
        )
    }

    entryPlate?.let { plate ->
        plate.manualMetric()?.let { metric ->
            PlateEntryDialog(
                metric = metric,
                db = db,
                onSaved = {
                    entryPlate = null
                    reload()
                },
                onDismiss = { entryPlate = null },
            )
        }
    }

    if (reorder) {
        Box(Modifier.fillMaxSize().background(ru.somena.ui.BgBase)) {
            NebulaBackground()
            ReorderCardsScreen(
                initial = layout,
                onDone = {
                    saveLayout(it)
                    reorder = false
                },
            )
        }
    }
}

private val dateHeaderFormat = DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale("ru", "RU"))

@Composable
fun ProfileSection() {
    val context = LocalContext.current
    val store = remember { ProfileStore(context) }
    val saved = remember { store.load() }
    var height by remember { mutableStateOf(saved.heightCm?.toString() ?: "") }
    var birth by remember {
        mutableStateOf(saved.birthDate?.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu")) ?: "")
    }
    var goal by remember { mutableStateOf(saved.goalWeightKg?.let { "%,.1f".format(it) } ?: "") }
    var bmr by remember { mutableStateOf(saved.bmrKcal?.let { "%,.0f".format(it) } ?: "") }
    var sex by remember { mutableStateOf(saved.sex) }
    var status by remember { mutableStateOf<String?>(null) }

    val today = LocalDate.now()
    val draft = Profile(
        heightCm = parseOptionalInt(height),
        birthDateIso = parseBirthDate(birth)?.toString(),
        goalWeightKg = parseOptionalDouble(goal),
        bmrKcal = parseOptionalDouble(bmr),
        sex = sex,
    )
    val errors = buildList {
        addAll(ProfileValidator.validate(draft, today))
        numericFieldError(height, integer = true)?.let { add(ProfileValidator.Error("heightCm", it)) }
        birthDateFieldError(birth, today)?.let { add(ProfileValidator.Error("birthDate", it)) }
        numericFieldError(goal, integer = false)?.let { add(ProfileValidator.Error("goalWeightKg", it)) }
        numericFieldError(bmr, integer = false)?.let { add(ProfileValidator.Error("bmrKcal", it)) }
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
        Text("Пол", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PeriodChip(
                "Женский",
                selected = sex == Sex.FEMALE,
                onClick = { sex = if (sex == Sex.FEMALE) null else Sex.FEMALE },
                modifier = Modifier.weight(1f),
            )
            PeriodChip(
                "Мужской",
                selected = sex == Sex.MALE,
                onClick = { sex = if (sex == Sex.MALE) null else Sex.MALE },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Влияет на советы ИИ; «мужской» прячет Календарь цикла. Повторный тап снимает выбор.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
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
        OutlinedTextField(
            value = bmr,
            onValueChange = { bmr = it },
            label = { Text("Базовый расход, ккал/дн") },
            isError = errorFor("bmrKcal") != null,
            supportingText = { errorFor("bmrKcal")?.let { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Прибавляется к Дефициту, пока умные весы не передают свой.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
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
