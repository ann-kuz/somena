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

/** Каталожная запись SAF: файл или папка. */data class StorageEntry(
    val documentId: String,
    val name: String,
    val isDir: Boolean,
    val sizeBytes: Long = 0,
    val mime: String? = null,
)

/** Дети папки одного уровня: подпапки и файлы, как их отдал провайдер. */
data class DirChildren(val folders: List<StorageEntry>, val files: List<StorageEntry>)

/** Строка дерева Хранилища: папка (с признаками раскрытия и загрузки) или файл. */
sealed interface TreeRow {
    val entry: StorageEntry
    val depth: Int

    data class Folder(
        override val entry: StorageEntry,
        override val depth: Int,
        val expanded: Boolean,
        val loaded: Boolean,
    ) : TreeRow

    data class File(override val entry: StorageEntry, override val depth: Int) : TreeRow
}

/**
 * Видимые строки дерева Хранилища (спека 0010): раскрытая папка вставляет своих
 * детей под собой, свёрнутая прячет поддерево; папки стоят вперёд файлов по
 * алфавиту. Уровни читаются лениво - по запросу на раскрытие, а не всё дерево.
 */
fun treeRows(
    rootChildren: DirChildren?,
    children: Map<String, DirChildren>,
    expanded: Set<String>,
): List<TreeRow> {
    if (rootChildren == null) return emptyList()

    fun level(c: DirChildren): List<StorageEntry> =
        c.folders.sortedBy { it.name.lowercase() } + c.files.sortedBy { it.name.lowercase() }

    return buildList {
        fun walk(parts: List<StorageEntry>, depth: Int) {
            for (e in parts) {
                if (!e.isDir) {
                    add(TreeRow.File(e, depth))
                } else {
                    val isOpen = e.documentId in expanded
                    add(TreeRow.Folder(e, depth, expanded = isOpen, loaded = e.documentId in children))
                    if (isOpen) walk(level(children[e.documentId] ?: DirChildren(emptyList(), emptyList())), depth + 1)
                }
            }
        }
        walk(level(rootChildren), 0)
    }
}
