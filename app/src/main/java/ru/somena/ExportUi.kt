package ru.somena

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.somena.core.EXPORT_FORMAT
import ru.somena.core.ExportManifest
import ru.somena.core.ExportProblem
import ru.somena.core.exportFileName
import ru.somena.core.exportSummary
import ru.somena.data.ExportManager
import ru.somena.data.RestoreResult
import ru.somena.data.SliceDb
import ru.somena.ui.GlassCard
import ru.somena.ui.GhostButton
import ru.somena.ui.GlowButton
import ru.somena.ui.TextMuted

/**
 * Экспорт и восстановление (спека 0021), «Настройки → Данные». «Сохранить копию»
 * собирает один файл-архив и открывает системное окно «куда сохранить».
 * «Восстановить из копии» показывает, что внутри файла, и после явного
 * «Заменить всё» полностью заменяет данные; если в копии есть файлы Медкарты,
 * система сначала просит выбрать папку Хранилища. Копия содержит ключ Чата -
 * файл личный секрет Пользователя.
 */
@Composable
fun ExportSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { ExportManager(context) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // Копия выбрана и ждёт подтверждения замены.
    var pending by remember { mutableStateOf<Pair<Uri, ExportManifest>?>(null) }
    // Замена подтверждена, ждём папку Хранилища для файлов Медкарты.
    var needMedFolder by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                status = runCatching {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        manager.exportTo(uri) { status = it }
                    }
                    "Копия сохранена ✓ Файл храни бережно: внутри ключ Чата."
                }.getOrElse { "Не получилось: ${it.message}" }
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val manifest = withContext(kotlinx.coroutines.Dispatchers.IO) { manager.readManifest(uri) }
                busy = false
                when {
                    manifest == null -> status = ExportProblem.NotAnArchive.text
                    manifest.format > EXPORT_FORMAT || manifest.dbVersion > SliceDb.VERSION ->
                        status = ExportProblem.TooNew.text
                    else -> pending = uri to manifest
                }
            }
        }
    }

    val medTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        val source = needMedFolder
        needMedFolder = null
        when {
            tree != null && source != null -> runRestore(scope, manager, context, source, tree) { status = it }
            else -> status = "Восстановление отменено: без папки Хранилища файлы Медкарты не перенести."
        }
    }

    fun restoreWithoutMed(source: Uri) = runRestore(scope, manager, context, source, null) { status = it }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Экспорт", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Один файл со всеми данными: отметки, Самочувствие, цикл, Медкарта " +
                    "с её файлами, переписка Чата и настройки. Для переноса на новый " +
                    "телефон и восстановления. Файл содержит ключ Чата - храни бережно.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            GlowButton(
                "Сохранить копию…",
                onClick = { exportLauncher.launch(exportFileName(LocalDate.now())) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            GhostButton(
                "Восстановить из копии…",
                onClick = {
                    importLauncher.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            status?.let {
                Text(it, color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    pending?.let { (source, manifest) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Восстановить из копии?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        exportSummary(manifest).forEach { line -> Text(line) }
                    }
                    Text(
                        "Текущие данные в телефоне будут целиком заменены данными " +
                            "из копии. Это нельзя отменить.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (manifest.medFiles > 0) {
                        Text(
                            "Для файлов Медкарты система дальше попросит выбрать " +
                                "папку Хранилища.",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    if (manifest.medFiles > 0) {
                        needMedFolder = source
                        medTreeLauncher.launch(null)
                    } else {
                        restoreWithoutMed(source)
                    }
                }) { Text("Заменить всё") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Отмена") }
            },
        )
    }
}

private fun runRestore(
    scope: kotlinx.coroutines.CoroutineScope,
    manager: ExportManager,
    context: Context,
    source: Uri,
    medTree: Uri?,
    onStatus: (String) -> Unit,
) {
    onStatus("Восстанавливаю…")
    scope.launch {
        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
            manager.restore(source, medTree, onStatus)
        }
        when (result) {
            is RestoreResult.Done -> {
                onStatus("Готово: данные восстановлены, перезапускаюсь…")
                delay(1500)
                restartApp(context)
            }
            is RestoreResult.Failure -> onStatus(result.text)
        }
    }
}

/** Перезапуск после восстановления: свежий процесс перечитает новую базу и настройки. */
private fun restartApp(context: Context) {
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }
    Runtime.getRuntime().exit(0)
}
