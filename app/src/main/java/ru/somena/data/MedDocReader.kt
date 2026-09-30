package ru.somena.data

import android.content.Context
import android.net.Uri
import ru.somena.core.MAX_ATTACHMENT_CHARS
import ru.somena.core.decodeDocxText
import ru.somena.core.decodeTableText
import ru.somena.core.pdfTextIsDense

/**
 * Чтение медицинского документа для Разбора (спека 0010, ADR-0009) - единая развилка
 * обоих входов (скрепка в Чате и файл из Хранилища Медкарты): плотный текстовый слой
 * pdf, docx и просто текстовые файлы уходят текстом по каналу вложения; скудный слой
 * pdf или слишком длинный текст - зрением (страницы pdf), картинка - сжатой картинкой.
 * Один конвейер - одно место решения.
 */
object MedDocReader {

    /** Прочитанный документ: текст или картинки; totalPages - страницы pdf для честности. */
    data class Parts(
        val text: String?,
        val images: List<String>,
        val totalPages: Int = 0,
    ) {
        val truncated: Boolean get() = totalPages > 0 && totalPages > PdfPages.MAX_PAGES
    }

    /** Тип файла по имени и MIME: одна развилка «документ или таблица» для всех входов. */
    enum class Kind { PDF, IMAGE, DOCX, TEXT, OTHER }

    /** Классификация до чтения файла: скрепка Чата решает, документ это или таблица. */
    fun kindOf(name: String, mime: String?): Kind = when {
        mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> Kind.PDF
        mime?.startsWith("image/") == true ||
            listOf("png", "jpg", "jpeg", "webp", "heic").any { name.endsWith(it, ignoreCase = true) } -> Kind.IMAGE
        mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            name.endsWith(".docx", ignoreCase = true) -> Kind.DOCX
        mime == "text/plain" || mime == "text/markdown" ||
            listOf("txt", "md").any { name.endsWith(it, ignoreCase = true) } -> Kind.TEXT
        else -> Kind.OTHER
    }

    /** Чтение по типу: pdf - двухступенчато, docx и txt - текстом, картинка - зрением. */
    fun read(context: Context, uri: Uri, name: String, mime: String?): Result<Parts> =
        when (val kind = kindOf(name, mime)) {
            Kind.IMAGE -> {
                val bytes = MedStorage(context).readBytes(uri)
                val image = bytes?.let { PdfPages.imageAsJpegBase64(it) }
                if (image == null) {
                    Result.failure(IllegalStateException("Не получилось прочитать картинку: выбери её заново."))
                } else {
                    Result.success(Parts(null, listOf(image)))
                }
            }
            Kind.PDF -> {
                val text = PdfText.extract(context, uri)
                when {
                    text == null -> Result.failure(
                        IllegalStateException("Не получилось открыть pdf: файл повреждён или недоступен.")
                    )
                    text.length > MAX_ATTACHMENT_CHARS && pdfTextIsDense(text) -> Result.failure(
                        IllegalStateException(
                            "Документ слишком длинный (${text.length} симв.): пришли его по частям."
                        )
                    )
                    pdfTextIsDense(text) -> Result.success(Parts(text, emptyList()))
                    else -> {
                        // Скан или чрезмерно длинный текст: страницы картинками, не больше 10.
                        val rendered = PdfPages.renderAsJpegBase64(context, uri)
                        if (rendered == null || rendered.images.isEmpty()) {
                            Result.failure(
                                IllegalStateException("Не получилось открыть pdf: файл повреждён или недоступен.")
                            )
                        } else {
                            Result.success(Parts(null, rendered.images, rendered.totalPages))
                        }
                    }
                }
            }
            Kind.DOCX -> {
                val text = MedStorage(context).readBytes(uri)?.let(::decodeDocxText)
                if (text == null) {
                    Result.failure(
                        IllegalStateException("Не получилось открыть docx: файл повреждён или это не docx.")
                    )
                } else {
                    textParts(text)
                }
            }
            Kind.TEXT -> {
                val bytes = MedStorage(context).readBytes(uri)
                if (bytes == null) {
                    Result.failure(IllegalStateException("Не удалось прочитать файл: выбери его заново."))
                } else {
                    textParts(decodeTableText(bytes))
                }
            }
            Kind.OTHER -> Result.failure(
                IllegalStateException(
                    "Это не документ Медкарты. Поддерживаются pdf, docx, txt, md и картинки; " +
                        "старый .doc пересохрани в docx."
                )
            )
        }

    /**
     * Текст документа без запасного зрения (docx и txt: страниц-картинок у них не
     * бывает): пустой - честная ошибка, длинный - «пришли по частям».
     */
    private fun textParts(text: String): Result<Parts> = when {
        text.isBlank() -> Result.failure(
            IllegalStateException("Текст в документе не найден: похоже, он пустой.")
        )
        text.length > MAX_ATTACHMENT_CHARS -> Result.failure(
            IllegalStateException("Документ слишком длинный (${text.length} симв.): пришли его по частям.")
        )
        else -> Result.success(Parts(text.trim(), emptyList()))
    }
}
