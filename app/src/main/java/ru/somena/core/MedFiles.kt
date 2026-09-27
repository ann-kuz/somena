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
