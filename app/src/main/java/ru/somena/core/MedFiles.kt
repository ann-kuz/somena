package ru.somena.core

/**
 * Индекс «файлы Хранилища ↔ записи Медкарты» (спека 0010, тикет 02): чистая логика
 * без Android. Бейдж «разобран» - свойство записи, а не файла; файл, переименованный
 * или удалённый снаружи, не роняет запись - она получает статус «оригинал не найден».
 */
class MedFileIndex(records: List<MedRecord>, folderFileUris: Set<String>) {

    private val recordByUri: Map<String, MedRecord> =
        records.filter { it.fileUri != null }.associateBy { it.fileUri!! }

    /** Записи, чей оригинал объявлен, но в Хранилище не найден. */
    val missingRecords: List<MedRecord> =
        records.filter { it.fileUri != null && it.fileUri !in folderFileUris }

    /** Есть ли у файла запись: бейдж «разобран» файлового списка. */
    fun isParsed(fileUri: String): Boolean = recordByUri.containsKey(fileUri)

    /** Запись файла: Предпросмотр замены при переразборе. */
    fun recordOf(fileUri: String): MedRecord? = recordByUri[fileUri]
}

/** Каталожная запись SAF: файл или папка. */
data class StorageEntry(
    val documentId: String,
    val name: String,
    val isDir: Boolean,
    val sizeBytes: Long = 0,
    val mime: String? = null,
)

/** Найденный файл Хранилища: путь папки от корня, у файлов корня - null. */
data class StorageHit(val entry: StorageEntry, val folder: String?)

/**
 * Файлы Хранилища на всю глубину вложенных папок (спека 0010): SAF отдаёт только
 * прямых детей документа, поэтому обходим дерево сами. Папки в список не попадают;
 * [folder] различает одинаковые имена из разных подпапок.
 */
fun collectStorageFiles(
    rootId: String,
    childrenOf: (documentId: String) -> List<StorageEntry>,
): List<StorageHit> {
    fun walk(dirId: String, folder: String?, out: MutableList<StorageHit>) {
        for (e in childrenOf(dirId)) {
            if (e.isDir) {
                walk(e.documentId, if (folder == null) e.name else "$folder/${e.name}", out)
            } else {
                out += StorageHit(e, folder)
            }
        }
    }
    return buildList { walk(rootId, null, this) }
}
