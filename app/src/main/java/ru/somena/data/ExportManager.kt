package ru.somena.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import ru.somena.core.EXPORT_ENTRY_DB
import ru.somena.core.EXPORT_ENTRY_MANIFEST
import ru.somena.core.EXPORT_ENTRY_MED_DIR
import ru.somena.core.EXPORT_ENTRY_SETTINGS
import ru.somena.core.ExportCounts
import ru.somena.core.ExportManifest
import ru.somena.core.ExportProblem
import ru.somena.core.StorageEntry
import ru.somena.core.exportProblem
import ru.somena.core.jsonToPrefs
import ru.somena.core.prefsToJson

/** Результат восстановления (спека 0021). */
sealed interface RestoreResult {
    /** Данные заменены; medFilesRestored - сколько файлов Медкарты перенесено в Хранилище. */
    data class Done(val medFilesRestored: Int) : RestoreResult

    /** Ничего не заменено или заменено частично с понятной причиной. */
    data class Failure(val text: String) : RestoreResult
}

/**
 * Экспорт и восстановление (спека 0021): один файл-архив со всей базой,
 * настройками и файлами Хранилища Медкарты. Внутри архива: манифест (первой
 * записью - предпросмотр читает его одним куском), копия somena.db, настройки
 * из prefs и файлы Медкарты под medcard/. Ключ Чата копируется - файл Экспорта
 * личный секрет Пользователя (решение владелицы).
 */
class ExportManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val resolver = context.contentResolver

    // ---- Экспорт ----

    /**
     * Сборка архива в выбранное место. WAL-чекпоинт перед копией базы: всё
     * записанное попадает в файл somena.db. Возвращает манифест - он же итог.
     */
    fun exportTo(target: Uri, onStatus: (String) -> Unit = {}): ExportManifest {
        onStatus("Собираю копию…")
        val db = SliceDb(context)
        val counts = countsOf(db)
        val medFiles = listMedFiles()
        val manifest = ExportManifest(
            appVersion = appVersion(),
            dbVersion = SliceDb.VERSION,
            createdAt = System.currentTimeMillis(),
            counts = counts,
            medFiles = medFiles.size,
            medBytes = medFiles.sumOf { it.sizeBytes },
        )
        // Чекпоинт truncates WAL: главный файл базы полон и самодостаточен.
        db.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        db.close()

        val dbFile = context.getDatabasePath("somena.db")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storage = MedStorage(context)

        resolver.openOutputStream(target, "w")?.use { raw ->
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                zip.putZipText(EXPORT_ENTRY_MANIFEST, json.encodeToString(manifest))
                onStatus("Копирую базу…")
                zip.putZipFile(EXPORT_ENTRY_DB, dbFile)
                onStatus("Копирую настройки…")
                zip.putZipText(EXPORT_ENTRY_SETTINGS, json.encodeToString(prefsToJson(prefs.all)))
                medFiles.forEachIndexed { index, file ->
                    onStatus("Файлы Медкарты: ${index + 1} из ${medFiles.size}")
                    val doc = storage.fileOf(file.entry) ?: throw IllegalStateException("файл недоступен")
                    zip.putNextEntry(ZipEntry(EXPORT_ENTRY_MED_DIR + file.relativePath))
                    resolver.openInputStream(Uri.parse(doc.uri))?.use { input ->
                        input.copyTo(zip)
                    } ?: throw IllegalStateException("файл не читается")
                    zip.closeEntry()
                }
            }
        } ?: throw IllegalStateException("не удалось открыть место для сохранения")
        return manifest
    }

    private fun countsOf(db: SliceDb): ExportCounts = runCatching {
        db.readableDatabase.rawQuery(
            "SELECT (SELECT COUNT(*) FROM day_slices), (SELECT COUNT(*) FROM wellbeing), " +
                "(SELECT COUNT(*) FROM cycle_days), (SELECT COUNT(*) FROM chat_messages), " +
                "(SELECT COUNT(*) FROM med_records)",
            null,
        ).use { c ->
            c.moveToFirst()
            ExportCounts(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4))
        }
    }.getOrDefault(ExportCounts())

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** Файлы Хранилища одним списком: обход дерева папок с ограничением глубины. */
    private fun listMedFiles(): List<MedFileRef> {
        val storage = MedStorage(context)
        val rootId = storage.rootId() ?: return emptyList()
        val found = mutableListOf<MedFileRef>()
        fun walk(dirId: String, path: String, depth: Int) {
            if (depth > 10) return
            val children = storage.listChildren(dirId) ?: return
            children.files.forEach {
                found += MedFileRef(it, path + it.name, it.sizeBytes)
            }
            children.folders.forEach { walk(it.documentId, "$path${it.name}/", depth + 1) }
        }
        walk(rootId, "", 0)
        return found
    }

    private data class MedFileRef(val entry: StorageEntry, val relativePath: String, val sizeBytes: Long)

    // ---- Предпросмотр восстановления ----

    /** Манифест копии: первый маленький кусок архива; null - это не копия Somena. */
    fun readManifest(source: Uri): ExportManifest? = runCatching {
        resolver.openInputStream(source)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (entry.name == EXPORT_ENTRY_MANIFEST) {
                        val text = zip.readBytes().decodeToString()
                        return runCatching { json.decodeFromString<ExportManifest>(text) }.getOrNull()
                    }
                    entry = zip.nextEntry
                }
                null
            }
        }
    }.getOrNull()

    // ---- Восстановление ----

    /**
     * Полная замена данных копией (спека 0021). Порядок безопасен: сначала архив
     * распаковывается во временные файлы и проверяется, и только затем подменяются
     * база и настройки. Файлы Медкарты переносятся в выбранную папку напрямую из
     * архива, без копии на внутреннем хранилище; адреса записей переписываются.
     */
    fun restore(source: Uri, medTree: Uri?, onStatus: (String) -> Unit): RestoreResult {
        val stagingDir = context.getDatabasePath("somena-restore.db").parentFile ?: return failure("нет папки базы")
        stagingDir.mkdirs()
        val stagedDb = File(stagingDir, "somena-restore.db")
        val stagedSettings = File(stagingDir, "settings.json")
        stagedDb.delete()
        stagedSettings.delete()

        onStatus("Читаю копию…")
        val entries = mutableSetOf<String>()
        var parsed: ExportManifest? = null
        try {
            resolver.openInputStream(source)?.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        entries += name
                        when (name) {
                            EXPORT_ENTRY_MANIFEST -> parsed = runCatching {
                                json.decodeFromString<ExportManifest>(zip.readBytes().decodeToString())
                            }.getOrNull()
                            EXPORT_ENTRY_DB -> zip.copyToFile(stagedDb)
                            EXPORT_ENTRY_SETTINGS -> zip.copyToFile(stagedSettings)
                        }
                        entry = zip.nextEntry
                    }
                }
            } ?: return failure("не удалось открыть файл копии")
        } catch (e: Exception) {
            return failure("копия не читается: ${e.message}")
        }
        val manifest = parsed ?: return failure(ExportProblem.NotAnArchive.text)
        if (manifest.medFiles > 0 && medTree == null) {
            return failure("без папки Хранилища файлы Медкарты не перенести")
        }
        exportProblem(entries.isNotEmpty(), manifest, entries, SliceDb.VERSION)?.let { return failure(it.text) }
        val settingsJson = runCatching {
            json.parseToJsonElement(stagedSettings.readText())
        }.getOrNull()?.let { element ->
            runCatching { element.jsonObject }.getOrNull()
        } ?: return failure(ExportProblem.Damaged.text)
        val integrity = runCatching {
            SQLiteDatabase.openDatabase(stagedDb.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use { c ->
                    c.moveToFirst()
                    c.getString(0)
                }
            }
        }.getOrNull()
        if (integrity != "ok") return failure("база внутри копии повреждена")

        onStatus("Заменяю данные…")
        // Своя копия помощника закрыта; чужие соединения живут на старом файле
        // и исчезнут вместе с процессом: база подменяется переименованием,
        // а не перезаписью, поэтому новый файл цельный с первого момента.
        SliceDb(context).close()
        val target = context.getDatabasePath("somena.db")
        for (suffix in listOf("-wal", "-shm", "-journal")) File(target.path + suffix).delete()
        if (!stagedDb.renameTo(target)) return failure("не удалось заменить базу")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keepLog = prefs.getString("app_log", null)
        val editor = prefs.edit().clear()
        jsonToPrefs(settingsJson).forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
            }
        }
        keepLog?.let { editor.putString("app_log", it) }
        editor.apply()

        var restoredFiles = 0
        if (manifest.medFiles > 0 && medTree != null) {
            when (val med = restoreMedFiles(source, medTree, manifest.medFiles, onStatus)) {
                is MedRestore.Ok -> {
                    restoredFiles = med.restored
                    MedStorage(context).setFolder(medTree)
                    rebindMedUris(target, med.urisByName)
                }
                is MedRestore.Failed -> return RestoreResult.Failure(
                    "Данные заменены, но файлы Медкарты перенести не удалось: ${med.reason}. " +
                        "Выбери папку Хранилища в Медкарте заново."
                )
            }
        }
        stagedSettings.delete()
        AppLog.append(
            context,
            AppLog.DB,
            "восстановление из копии: дней ${manifest.counts.days}, файлов Медкарты $restoredFiles",
        )
        return RestoreResult.Done(restoredFiles)
    }

    private fun failure(text: String) = RestoreResult.Failure(text).also {
        File(context.getDatabasePath("somena-restore.db").path).delete()
    }

    private sealed interface MedRestore {
        data class Ok(val restored: Int, val urisByName: Map<String, String>) : MedRestore
        data class Failed(val reason: String) : MedRestore
    }

    /**
     * Второй проход по архиву: файлы из medcard/ создаются в выбранной папке
     * (папки рекурсивно), адреса новых документов собираются по имени файла -
     * по нему же записи Медкарты будут перепривязаны.
     */
    private fun restoreMedFiles(
        source: Uri,
        medTree: Uri,
        total: Int,
        onStatus: (String) -> Unit,
    ): MedRestore = try {
        val dirIds = HashMap<String, String>()
        val urisByName = HashMap<String, String>()
        var done = 0
        resolver.openInputStream(source)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.startsWith(EXPORT_ENTRY_MED_DIR) && !entry.isDirectory) {
                        val relative = name.removePrefix(EXPORT_ENTRY_MED_DIR)
                        val dirPath = relative.substringBeforeLast('/', missingDelimiterValue = "")
                        val fileName = relative.substringAfterLast('/')
                        val parentId = ensureDir(medTree, dirPath, dirIds)
                        val created = DocumentsContract.createDocument(
                            resolver,
                            DocumentsContract.buildDocumentUriUsingTree(medTree, parentId),
                            mimeByName(fileName),
                            fileName,
                        )
                        if (created == null) {
                            zip.closeEntry()
                            entry = zip.nextEntry
                            continue
                        }
                        resolver.openOutputStream(created)?.use { output -> zip.copyTo(output) }
                        urisByName[fileName] =
                            DocumentsContract.buildDocumentUriUsingTree(medTree, DocumentsContract.getDocumentId(created)).toString()
                        done++
                        onStatus("Файлы Медкарты: $done из $total")
                    }
                    entry = zip.nextEntry
                }
            }
        }
        MedRestore.Ok(done, urisByName)
    } catch (e: Exception) {
        MedRestore.Failed(e.message ?: "неизвестная ошибка")
    }

    /** Документ-Id папки relative внутри дерева, создавая недостающие уровни. */
    private fun ensureDir(tree: Uri, relative: String, cache: MutableMap<String, String>): String {
        if (relative.isEmpty()) return DocumentsContract.getTreeDocumentId(tree)
        cache[relative]?.let { return it }
        val parentPath = relative.substringBeforeLast('/', missingDelimiterValue = "")
        val parentId = ensureDir(tree, parentPath, cache)
        val name = relative.substringAfterLast('/')
        val existing = resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        add(c.getString(0))
                    }
                }
            }
        }?.firstOrNull()
        val id = existing ?: DocumentsContract.createDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, parentId),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        )?.let { DocumentsContract.getDocumentId(it) }
        ?: throw IllegalStateException("не создать папку $relative")
        cache[relative] = id
        return id
    }

    /**
     * Записи Медкарты ссылаются на документы старой папки (ADR-0008): после
     * переноса адреса переписываются по совпадению имён файлов.
     */
    private fun rebindMedUris(dbFile: File, urisByName: Map<String, String>) {
        if (urisByName.isEmpty()) return
        runCatching {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                urisByName.forEach { (name, uri) ->
                    db.execSQL(
                        "UPDATE med_records SET file_uri = ? WHERE file_name = ?",
                        arrayOf(uri, name),
                    )
                }
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "somena"
        fun ZipOutputStream.putZipText(name: String, text: String) {
            putNextEntry(ZipEntry(name))
            write(text.encodeToByteArray())
            closeEntry()
        }

        fun ZipOutputStream.putZipFile(name: String, file: File) {
            putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(this) }
            closeEntry()
        }

        fun ZipInputStream.copyToFile(file: File) {
            file.outputStream().use { copyTo(it) }
        }

        fun mimeByName(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "doc", "docx" -> "application/msword"
            "rtf" -> "application/rtf"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
