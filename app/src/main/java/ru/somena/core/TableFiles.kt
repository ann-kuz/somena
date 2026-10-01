package ru.somena.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** windows-1251: обычная кодировка русского Excel и старого Word, в kotlin Charsets её нет. */
internal val CP1251 = charset("windows-1251")

/**
 * Текст таблицы из файла Вложения (спека 0004): чистая логика без Android.
 * Кодировка: строгий UTF-8, при неудаче windows-1251 - обычный случай русского Excel.
 */

/** Лимит текста таблицы, уходящего в Бэкенд: больше - ошибка «разбей на части». */
const val MAX_ATTACHMENT_CHARS = 60_000

/** Лимит размера файла вложения. */
const val MAX_ATTACHMENT_BYTES = 2 * 1024 * 1024

/** Декодирует файл в текст таблицы: UTF-8 (BOM срезается), иначе windows-1251. */
fun decodeTableText(bytes: ByteArray): String {
    val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        bytes.copyOfRange(3, bytes.size)
    } else {
        bytes
    }
    val utf8 = try {
        strictDecode(body, StandardCharsets.UTF_8)
    } catch (e: CharacterCodingException) {
        null
    }
    return (utf8 ?: String(body, CP1251)).trim('\uFEFF', '\u0000')
}

private fun strictDecode(bytes: ByteArray, cs: java.nio.charset.Charset): String =
    cs.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
