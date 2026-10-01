package ru.somena.data

import android.content.Context
import android.net.Uri
import ru.somena.core.MAX_ATTACHMENT_CHARS
import ru.somena.core.decodeDocText
import ru.somena.core.decodeDocxText
import ru.somena.core.decodeEpubText
import ru.somena.core.decodeFb2Text
import ru.somena.core.decodeHtmlText
import ru.somena.core.decodeOdtText
import ru.somena.core.decodeRtfText
import ru.somena.core.decodeTableText
import ru.somena.core.pdfTextIsDense

/**
 * Чтение медицинского документа для Разбора (спека 0010, ADR-0009; форматы -
 * спека 0013) - единая развилка обоих входов (скрепка в Чате и файл из Хранилища
 * Медкарты): плотный текстовый слой pdf и все текстовые форматы уходят текстом
 * по каналу вложения; скудный слой pdf или слишком длинный текст - зрением
 * (страницы pdf), картинка - сжатой картинкой. Один конвейер - одно место решения.
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
    enum class Kind { PDF, IMAGE, DOCX, DOC, RTF, ODT, HTML, FB2, EPUB, TEXT, OTHER }

    /** Классификация до чтения файла: скрепка Чата решает, документ это или таблица. */
    fun kindOf(name: String, mime: String?): Kind = when {
        mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> Kind.PDF
        mime?.startsWith("image/") == true ||
            listOf("png", "jpg", "jpeg", "webp", "heic").any { name.endsWith(it, ignoreCase = true) } -> Kind.IMAGE
        mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            name.endsWith(".docx", ignoreCase = true) -> Kind.DOCX
        mime == "application/msword" || name.endsWith(".doc", ignoreCase = true) -> Kind.DOC
        mime == "application/rtf" || mime == "text/rtf" ||
            name.endsWith(".rtf", ignoreCase = true) -> Kind.RTF
        mime == "application/vnd.oasis.opendocument.text" ||
            name.endsWith(".odt", ignoreCase = true) -> Kind.ODT
        mime == "text/html" || name.endsWith(".html", ignoreCase = true) ||
            name.endsWith(".htm", ignoreCase = true) -> Kind.HTML
        listOf("application/x-fictionbook+xml", "application/fictionbook+xml",
            "application/x-fictionbook").contains(mime) ||
            name.endsWith(".fb2", ignoreCase = true) -> Kind.FB2
        mime == "application/epub+zip" || name.endsWith(".epub", ignoreCase = true) -> Kind.EPUB
        mime == "text/plain" || mime == "text/markdown" ||
            listOf("txt", "md").any { name.endsWith(it, ignoreCase = true) } -> Kind.TEXT
        else -> Kind.OTHER
    }

    /** Чтение по типу: pdf - двухступенчато, текстовые форматы - текстом, картинка - зрением. */
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
            // Текстовые форматы одним куском: читалка байтов одна, отказы - честные.
            Kind.DOCX -> textFormat(context, uri, ::decodeDocxText, "docx", resaveHint = false)
            Kind.DOC -> textFormat(context, uri, ::decodeDocText, "doc", resaveHint = true)
            Kind.RTF -> textFormat(context, uri, ::decodeRtfText, "rtf", resaveHint = false)
            Kind.ODT -> textFormat(context, uri, ::decodeOdtText, "odt", resaveHint = false)
            Kind.HTML -> {
                val bytes = MedStorage(context).readBytes(uri)
                if (bytes == null) {
                    Result.failure(IllegalStateException("Не удалось прочитать файл: выбери его заново."))
                } else {
                    textParts(decodeHtmlText(bytes))
                }
            }
            Kind.FB2 -> textFormat(context, uri, ::decodeFb2Text, "fb2", resaveHint = false)
            Kind.EPUB -> textFormat(context, uri, ::decodeEpubText, "epub", resaveHint = false)
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
                    "Это не документ Медкарты. Поддерживаются pdf, doc, docx, rtf, odt, html, " +
                        "fb2, epub, txt, md и картинки."
                )
            )
        }

    /** Байты → ридером → в общий конвейер текста; null ридера - честная ошибка формата. */
    private fun textFormat(
        context: Context,
        uri: Uri,
        reader: (ByteArray) -> String?,
        format: String,
        resaveHint: Boolean,
    ): Result<Parts> {
        val text = MedStorage(context).readBytes(uri)?.let(reader)
        return if (text == null) {
            val hint = if (resaveHint) " Пересохрани его в docx." else ""
            Result.failure(
                IllegalStateException("Не получилось открыть $format: файл повреждён или это не $format.$hint")
            )
        } else {
            textParts(text)
        }
    }

    /**
     * Текст документа без запасного зрения (текстовые форматы: страниц-картинок
     * у них не бывает): пустой - честная ошибка, длинный - «пришли по частям».
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
