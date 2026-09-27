package ru.somena.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Текстовый слой pdf на телефоне (спека 0010, ADR-0009): плотный слой уходит текстом
 * по существующему каналу вложения - точнее зрения и дешевле; скудный или отсутствующий
 * значит «скан» и зовёт зрение (тикет 04). null - pdf не открылся вообще.
 */
object PdfText {

    @Volatile
    private var inited = false

    fun extract(context: Context, uri: Uri): String? = runCatching {
        if (!inited) {
            PDFBoxResourceLoader.init(context.applicationContext)
            inited = true
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc -> PDFTextStripper().getText(doc) }
        }
    }.getOrNull()
}
