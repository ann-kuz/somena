package ru.somena

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch
import ru.somena.core.AiChartSpec
import ru.somena.core.AiMetric
import ru.somena.core.CHAT_SYSTEM_PROMPT
import ru.somena.core.DayData
import ru.somena.core.WELLBEING_METRICS
import ru.somena.core.Wellbeing
import ru.somena.core.buildChatContext
import ru.somena.core.lastDays
import ru.somena.core.metricSeries
import ru.somena.core.parseAiCharts
import ru.somena.data.ChatClient
import ru.somena.data.ChatLog
import ru.somena.data.ChatMessage
import ru.somena.data.ChatSettings
import ru.somena.data.ProfileStore
import ru.somena.data.SliceDb
import ru.somena.ui.GhostButton
import ru.somena.ui.GlassCard
import ru.somena.ui.GlowButton
import ru.somena.ui.Gold
import ru.somena.ui.Indigo
import ru.somena.ui.PeriodChip
import ru.somena.ui.ScreenHeader
import ru.somena.ui.TextMuted
import ru.somena.ui.Violet
import ru.somena.ui.neonSurface

private val SUGGESTIONS = listOf(
    "Почему вес встал?",
    "Что изменить на этой неделе?",
    "Как сон влияет на самочувствие?",
)

/** Цвет метрики на графике ИИ: правило цветов метрик спеки 0002, единое для всех экранов. */
fun aiMetricColor(m: AiMetric): Color = when (m) {
    AiMetric.STEPS, AiMetric.EATEN, AiMetric.FAT -> Gold
    AiMetric.CARBS, AiMetric.MOOD -> Indigo
    AiMetric.SLEEP_QUALITY -> TextMuted
    else -> Violet
}

/** Экран «Чат по данным» (тикет 07): свободный вопрос, ответ ИИ, графики от модели, история. */
@Composable
fun ChatScreen(m: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { SliceDb(context) }
    val settings = remember { ChatSettings(context) }
    val client = remember {
        ChatClient(settings.endpoint()) { line -> ChatLog.append(context, line) }
    }
    // Данные для контекста вопроса и графиков ИИ: свежие на входе на экран.
    val data = remember { DayData(db.all().associateBy { it.date }, db.allWellbeing().associateBy { it.date }) }
    var messages by remember { mutableStateOf(db.chatHistory()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    fun send(question: String) {
        val text = question.trim()
        if (text.isEmpty() || busy || !settings.isConfigured) return
        error = null
        input = ""
        db.addChatMessage(ChatMessage.USER, text)
        messages = db.chatHistory()
        busy = true
        scope.launch {
            client.ask(
                history = db.chatHistory(),
                system = CHAT_SYSTEM_PROMPT,
                context = buildChatContext(
                    today = LocalDate.now(),
                    data = data,
                    profile = ProfileStore(context).load(),
                ),
            ).fold(
                onSuccess = { reply -> db.addChatMessage(ChatMessage.ASSISTANT, reply) },
                onFailure = { e -> error = e.message ?: "Чат не удался." },
            )
            busy = false
            messages = db.chatHistory()
        }
    }

    // Новое сообщение или «Думаю…» — держим конец диалога на виду.
    LaunchedEffect(messages.size, busy) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(m.fillMaxSize().padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenHeader("Чат по данным", "ИИ видит твои срезы, Самочувствие и профиль. Не врач: диагнозов не ставит.")
        if (!settings.isConfigured) {
            GlassCard(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        "Чат не настроен: введи токен приложения на вкладке «Ещё». " +
                            "Остальное приложение работает и без него.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && !busy) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Text(
                                "Спроси что угодно о своих данных: ИИ видит дневные срезы за 30 дней, " +
                                    "Самочувствие и профиль. Попроси показать график, например: «покажи вес за месяц».",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                items(SUGGESTIONS) { q ->
                    PeriodChip(q, selected = false, onClick = { send(q) })
                }
            }
            items(messages) { msg ->
                MessageBubble(msg, data)
            }
            if (busy) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        GlassCard(Modifier.fillMaxWidth(0.5f)) {
                            Text("Думаю…", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        error?.let {
            SelectionContainer {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Спроси о своих данных…") },
                enabled = settings.isConfigured && !busy,
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            SendButton(
                enabled = settings.isConfigured && !busy && input.isNotBlank(),
                onClick = { send(input) },
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, data: DayData) {
    if (msg.role == ChatMessage.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                Modifier
                    .fillMaxWidth(0.85f)
                    .neonSurface(active = true, shape = RoundedCornerShape(20.dp))
                    .padding(12.dp),
            ) {
                SelectionContainer { Text(msg.content, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    } else {
        val (text, specs) = remember(msg.content) { parseAiCharts(msg.content) }
        // График привязан к дню ответа: история показывает данные «на момент вопроса».
        val anchor = remember(msg.sentAt) {
            Instant.ofEpochMilli(msg.sentAt).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            GlassCard(Modifier.fillMaxWidth(0.94f)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (text.isNotBlank()) {
                        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
                    }
                    specs.forEach { spec -> AiChartCard(spec, anchor, data) }
                }
            }
        }
    }
}

/** График, построенный моделью: метрики и окно из ответа, линии — из локальных данных. */
@Composable
fun AiChartCard(spec: AiChartSpec, anchor: LocalDate, data: DayData) {
    val dates = lastDays(anchor, spec.windowDays)
    val metrics = spec.metrics.mapNotNull { AiMetric.byKey(it) }
    if (metrics.isEmpty()) return
    val seriesList = metrics.map { m ->
        ChartSeries(m.label, metricSeries(m, dates, data), aiMetricColor(m), m.unit)
    }
    val wbOnly = metrics.all { it in WELLBEING_METRICS }
    LineChart(
        title = spec.title.ifBlank { "График" },
        dates = dates,
        seriesList = seriesList,
        yMin = if (wbOnly) Wellbeing.MIN.toDouble() else null,
        yMax = if (wbOnly) Wellbeing.MAX.toDouble() else null,
    )
}

/** Круглая неоновая кнопка отправки: то же неоновое стекло, в форме круга под иконку. */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .neonSurface(active = enabled, shape = CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Send,
            contentDescription = "Отправить вопрос",
            tint = if (enabled) MaterialTheme.colorScheme.onBackground else TextMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Настройки Чата по данным на вкладке «Ещё»: адрес Бэкенда, токен, очистка истории. */
@Composable
fun ChatSettingsSection() {
    val context = LocalContext.current
    val settings = remember { ChatSettings(context) }
    var url by remember { mutableStateOf(settings.backendUrl) }
    var token by remember { mutableStateOf(settings.appToken) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Чат по данным", style = MaterialTheme.typography.titleMedium)
        Text(
            "Адрес Бэкенда-прокси и токен приложения. Без токена чат не работает, " +
                "остальные вкладки работают всегда.",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Адрес Бэкенда") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Токен приложения") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        GlowButton(
            "Сохранить",
            onClick = {
                settings.backendUrl = url
                settings.appToken = token
                status = "Сохранено ✓"
            },
            enabled = url.isBlank() || (url.startsWith("http") && token.isNotBlank()),
            modifier = Modifier.fillMaxWidth(),
        )
        GhostButton(
            "Очистить историю чата",
            onClick = {
                SliceDb(context).clearChat()
                status = "История очищена"
            },
            modifier = Modifier.fillMaxWidth(),
        )
        status?.let {
            SelectionContainer {
                Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
