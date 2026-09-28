package ru.somena.data

import android.content.Context
import android.net.Uri
import ru.somena.core.MAX_ATTACHMENT_CHARS
import ru.somena.core.pdfTextIsDense

/**
 * Чтение медицинского документа для Разбора (спека 0010, ADR-0009) - единая двухступенчатая
 * развилка обоих входов (скрепка в Чате и файл из Хранилища): плотный текстовый слой pdf
 * уходит текстом по каналу вложения; скудный, слишком длинный или отсутствующий - зрением
 * (страницы pdf или сжатая картинка). Один конвейер - одно место решения.
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

    /** Чтение по типу: pdf - двухступенчато, картинка - сразу зрением. */
    fun read(context: Context, uri: Uri, name: String, mime: String?): Result<Parts> {
        val isPdf = mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)
        val isImage = mime?.startsWith("image/") == true ||
            listOf("png", "jpg", "jpeg", "webp", "heic").any { name.endsWith(it, ignoreCase = true) }
        return when {
            isImage -> {
                val bytes = MedStorage(context).readBytes(uri)
                val image = bytes?.let { PdfPages.imageAsJpegBase64(it) }
                if (image == null) {
                    Result.failure(IllegalStateException("Не получилось прочитать картинку: выбери её заново."))
                } else {
                    Result.success(Parts(null, listOf(image)))
                }
            }
            isPdf -> {
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
            else -> Result.failure(IllegalStateException("Это не документ Медкарты: pdf или картинка."))
        }
    }
}
