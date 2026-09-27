package ru.somena.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** Файл Хранилища: uri документа SAF, имя, размер и тип. */
data class StorageFile(val uri: String, val name: String, val sizeBytes: Long, val mime: String?)

/** Оригинал, скопированный в Хранилище: uri и имя (SAF мог дописать суффикс от коллизии). */
data class StoredOriginal(val uri: String, val name: String)

/**
 * Хранилище Медкарты (спека 0010, ADR-0008): папка на телефоне, выбранная Пользователем
 * через системный диалог (долговременный доступ SAF, без разрешения «на все файлы»).
 * Приложение файлы читает, показывает и открывает внешним просмотрщиком; копирует
 * Вложения скрепки. Не удаляет и не переименовывает файлы никогда - это право Пользователя.
 */
class MedStorage(private val context: Context) {

    private val prefs = context.getSharedPreferences("somena", Context.MODE_PRIVATE)

    fun folderUri(): Uri? =
        prefs.getString(KEY_FOLDER, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** Выбор папки: долговременное разрешение и адрес в настройках Медкарты. */
    fun setFolder(uri: Uri) {
        // Пишем только для копий скрепки; если провайдер дал лишь чтение - остаёмся на нём.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
    }

    fun clearFolder() {
        prefs.edit().remove(KEY_FOLDER).apply()
    }

    /** Файлы Хранилища без каталогов, насколько SAF их отдаёт. */
    fun listFiles(): List<StorageFile> {
        val tree = folderUri() ?: return emptyList()
        return runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                        add(
                            StorageFile(
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString(),
                                name = c.getString(1)?.takeIf { it.isNotBlank() } ?: "файл",
                                sizeBytes = c.getLong(3),
                                mime = c.getString(2),
                            )
                        )
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** Открыть оригинал внешним просмотрщиком; false - не нашлось чем открыть. */
    fun openFile(file: StorageFile): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(file.uri), file.mime ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name))
        true
    }.getOrDefault(false)

    /**
     * Копия Вложения в Хранилище (ADR-0008): скрепка кладёт оригинал сама.
     * SAF не перезаписывает существующее - дописывает суффикс коллизии. null - нет папки
     * или скопировать не удалось.
     */
    fun copyIntoStorage(source: Uri, displayName: String, mime: String?): StoredOriginal? {
        val tree = folderUri() ?: return null
        return runCatching {
            val dir = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val target = DocumentsContract.createDocument(
                context.contentResolver, dir, mime ?: "application/octet-stream", displayName,
            ) ?: return null
            context.contentResolver.openInputStream(source)?.use { input ->
                context.contentResolver.openOutputStream(target)?.use { output -> input.copyTo(output) }
            } ?: return null
            StoredOriginal(target.toString(), displayNameOf(target) ?: displayName)
        }.getOrNull()
    }

    /** Имя документа по uri; null - спросить не у кого. */
    fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    fun readBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    private companion object {
        const val KEY_FOLDER = "med_folder_uri"
    }
}
