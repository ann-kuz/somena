package ru.somena.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Картинки Разбора документа (спека 0010, ADR-0009, вторая ступень): страницы pdf
 * рендерятся в JPEG (до 10 за Разбор), фото и скриншоты сжимаются на устройстве -
 * трафик к Бэкенду растёт только на них. Всё в base64 без префиксов.
 */
object PdfPages {

    /** Страниц за один Разбор (спека 0010): развёрнутые панели и многостраничные заключения. */
    const val MAX_PAGES = 10
    private const val MAX_DIM = 1568
    private const val JPEG_QUALITY = 82

    /** Отрендеренные страницы pdf: картинки и честное число страниц документа. */
    data class RenderedPdf(val images: List<String>, val totalPages: Int) {
        val truncated: Boolean get() = totalPages > images.size
    }

    /** Страницы pdf картинками JPEG base64 (до [MAX_PAGES]); null - pdf не открылся. */
    fun renderAsJpegBase64(context: Context, uri: Uri): RenderedPdf? =
        runCatching {
            val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return null
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val pages = (0 until minOf(renderer.pageCount, MAX_PAGES)).map { i ->
                        renderer.openPage(i).use { page ->
                            val scale = minOf(
                                MAX_DIM.toFloat() / page.width,
                                MAX_DIM.toFloat() / page.height,
                                2f,
                            )
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                            // PdfRenderer рисует на прозрачном фоне: белый лист честнее.
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            encodeJpegBase64(bitmap)
                        }
                    }
                    RenderedPdf(pages, renderer.pageCount)
                }
            }
        }.getOrNull()

    /** Сжатие фото и скриншотов: длинная сторона не больше 1568, JPEG base64; null - не картинка. */
    fun imageAsJpegBase64(bytes: ByteArray): String? = runCatching {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val longSide = maxOf(bmp.width, bmp.height)
        val scale = if (longSide > MAX_DIM) MAX_DIM.toFloat() / longSide else 1f
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bmp
        }
        encodeJpegBase64(scaled)
    }.getOrNull()

    private fun encodeJpegBase64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
