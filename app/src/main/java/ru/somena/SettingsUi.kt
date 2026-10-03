package ru.somena

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import ru.somena.data.AppLog
import ru.somena.ui.CardBorder
import ru.somena.ui.ChatBubbleIcon
import ru.somena.ui.GhostButton
import ru.somena.ui.GlassCard
import ru.somena.ui.GlowButton
import ru.somena.ui.ScreenHeader
import ru.somena.ui.TextMuted
import ru.somena.ui.Violet

/** Подэкраны Настроек; null - корневое меню списка. */
object SettingsPages {
    const val PROFILE = "profile"
    const val DATA = "data"
    const val CHAT = "chat"
    const val DEBUG = "debug"
}

/**
 * Вкладка «Настройки»: корневое меню списком, как в системных настройках, и подэкраны.
 * Кнопка «назад» из подэкрана возвращается в меню, из меню - на вкладку «Сегодня».
 */
@Composable
fun SettingsScreen(
    m: Modifier,
    page: String?,
    onPage: (String?) -> Unit,
    onRepeatOnboarding: () -> Unit,
    onOpenToday: () -> Unit,
) {
    BackHandler(enabled = page != null) { onPage(null) }
    BackHandler(enabled = page == null) { onOpenToday() }
    when (page) {
        null -> Column(
            m.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader("Настройки", "Профиль, данные, чат и отладка")
            SettingsMenu(onPage)
            Text(
                "Приложение всегда тёмное и хранит данные только на этом телефоне.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SettingsPages.PROFILE -> SettingsSubScreen(
            m, "Профиль", "Рост, пол, дата рождения, цель по весу и базовый расход", onPage,
        ) { ProfileSection() }
        SettingsPages.DATA -> SettingsSubScreen(
            m, "Данные", "Источники Health Connect и расчёт расхода от шагов", onPage,
        ) {
            StepsBurnSection()
            SourcesSection()
            GhostButton("Пройти онбординг заново", onRepeatOnboarding, Modifier.fillMaxWidth())
            Text(
                "Онбординг - это разрешение Health Connect и подсказки по подключению " +
                    "браслета, весов и дневника еды.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SettingsPages.CHAT -> SettingsSubScreen(
            m, "Чат по данным", "ИИ-сервис, ключ API и модели Ступеней", onPage,
        ) { ChatSettingsSection() }
        SettingsPages.DEBUG -> SettingsSubScreen(
            m, "Отладка", "Журнал чата, записи базы, ошибки и диагностика Health Connect", onPage,
        ) {
            DebugLogSection()
            HcToolsSection()
        }
    }
}

/** Подэкран настроек: заголовок, «Назад» и прокручиваемое содержимое. */
@Composable
private fun SettingsSubScreen(
    m: Modifier,
    title: String,
    subtitle: String,
    onPage: (String?) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        m.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(title, subtitle)
        GhostButton("Назад", { onPage(null) }, Modifier.fillMaxWidth())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

private data class MenuRow(val icon: ImageVector, val title: String, val subtitle: String, val page: String)

/** Меню списком: одна стеклянная карточка, ряды с иконкой, подписью и шевроном. */
@Composable
private fun SettingsMenu(onPage: (String?) -> Unit) {
    val rows = listOf(
        MenuRow(Icons.Filled.Person, "Профиль", "Рост, пол, цель по весу, базовый расход", SettingsPages.PROFILE),
        MenuRow(Icons.Filled.Refresh, "Данные", "Источники Health Connect, расход от шагов", SettingsPages.DATA),
        MenuRow(ChatBubbleIcon, "Чат", "ИИ-сервис, ключ API, модели Ступеней", SettingsPages.CHAT),
        MenuRow(Icons.Filled.Build, "Отладка", "Журнал чата, базы и ошибок", SettingsPages.DEBUG),
    )
    GlassCard(Modifier.fillMaxWidth()) {
        Column {
            rows.forEachIndexed { i, row ->
                if (i > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(CardBorder)
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable { onPage(row.page) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(row.icon, contentDescription = null, tint = Violet, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.title, style = MaterialTheme.typography.bodyLarge)
                        Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Журнал приложения (Настройки → Отладка): запросы Чата, записи базы и ошибки за
 * последние 7 дней. Окошко с прокруткой, копированием в один нажим и очисткой.
 */
@Composable
fun DebugLogSection() {
    val context = LocalContext.current
    var log by remember { mutableStateOf(AppLog.get(context)) }
    val clipboard = LocalClipboardManager.current
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Журнал", style = MaterialTheme.typography.titleMedium)
            Text(
                "Запросы Чата, записи базы и ошибки. Хранится 7 дней, ранние строки стираются.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
                    .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(14.dp))
                    .border(androidx.compose.foundation.BorderStroke(1.dp, CardBorder), RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                if (log.isBlank()) {
                    Text(
                        "Пусто: журнал соберёт запросы Чата, записи базы и ошибки по мере " +
                            "работы приложения.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    SelectionContainer {
                        Text(
                            log,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton(
                    "Скопировать журнал",
                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(log)) },
                    modifier = Modifier.weight(1f),
                )
                GhostButton(
                    "Очистить",
                    onClick = {
                        AppLog.clear(context)
                        log = AppLog.get(context)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Диагностика Health Connect: статус хаба, выдача разрешений и скан записей за неделю
 * (пережила переезд из прежней «Отладки HC»).
 */
@Composable
fun HcToolsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
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
                AppLog.append(context, AppLog.HC, "скан не удался: ${e.message}")
                "Ошибка: ${e.message}"
            }
            granted = HealthProbe.grantedPermissions(context).size
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(Modifier.fillMaxWidth()) {
            androidx.compose.foundation.text.selection.SelectionContainer {
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
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(it))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        granted = HealthProbe.grantedPermissions(context).size
    }
}
