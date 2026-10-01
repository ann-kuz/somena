package ru.somena.core

import org.w3c.dom.Element

/**
 * Мини-ридеры текстовых форматов Разбора документа (спека 0013): rtf, odt, html,
 * fb2, epub - по образцу docx/xlsx-ридеров, zip + XML или разбор на месте, без
 * тяжёлых библиотек. Общий контракт: null - файл не этого формата или битый,
 * пустая строка - валидный файл без текста; решение «пусто или слишком длинно»
 * принимает MedDocReader.
 */

/** odt: zip + content.xml, абзацы text:p и text:h - строками. */
fun decodeOdtText(bytes: ByteArray): String? = try {
    val content = zipEntries(bytes)["content.xml"] ?: return null
    officeParagraphs(parseXml(content)).trim()
} catch (e: Exception) {
    null
}

/** Абзацы content.xml строками: спецузлы (таб, пробелы, перенос) - символами. */
private fun officeParagraphs(root: Element): String = buildString {
    fun inline(el: Element) {
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            when {
                node is org.w3c.dom.Text -> append(node.textContent)
                node is Element -> when (node.tagName) {
                    "text:tab" -> append('\t')
                    "text:s" -> append(' ')
                    "text:line-break" -> append('\n')
                    else -> inline(node)
                }
            }
        }
    }

    fun walk(el: Element) {
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node !is Element) continue
            when (node.tagName) {
                "text:p", "text:h" -> {
                    inline(node)
                    append('\n')
                }
                else -> walk(node)
            }
        }
    }
    walk(root)
}

/** fb2: XML, строки - абзацы p и стихотворные v, заголовки и подписи строками. */
private val FB2_LINE_TAGS = setOf("p", "v", "subtitle", "title", "text-author", "empty-line")

fun decodeFb2Text(bytes: ByteArray): String? = try {
    fb2Paragraphs(parseXml(bytes)).trim()
} catch (e: Exception) {
    null
}

/** Тело книги без сносок (body name=notes): сноски не мешают Разбору. */
private fun fb2Paragraphs(root: Element): String = buildString {
    fun localName(el: Element) = el.tagName.substringAfter(':')

    fun walk(el: Element) {
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node !is Element) continue
            val name = localName(node)
            when {
                name == "body" && node.getAttribute("name") == "notes" -> {} // сноски целиком
                // Спускаемся только мимо строк-тегов: title содержит свои p,
                // иначе те же слова попали бы в текст дважды.
                name in FB2_LINE_TAGS -> {
                    append(node.textContent.trim())
                    append('\n')
                }
                else -> walk(node)
            }
        }
    }
    walk(root)
}

/** html: теги прочь, блоки - строками, ячейки - через таб, спецсимволы - символами. */
fun decodeHtmlText(bytes: ByteArray): String = htmlToText(decodeTableText(bytes))

internal fun htmlToText(html: String): String {
    val noJunk = Regex("(?is)<(script|style|noscript|template)\\b[^>]*>.*?</\\1\\s*>")
        .replace(html, " ")
    val blocked = Regex(
        "(?i)</?(p|div|br|tr|li|h[1-6]|blockquote|section|article|table|thead|tbody|tfoot|" +
            "ul|ol|dl|dt|dd|pre|hr|figure|figcaption|form|fieldset)\\b[^>]*>",
    ).replace(noJunk, "\n")
    val cells = Regex("(?i)<t[dh]\\b[^>]*>").replace(blocked, "\t")
    val cellEnd = Regex("(?i)</t[dh]>").replace(cells, "")
    val untagged = Regex("<[^>]+>").replace(cellEnd, " ")
    return decodeHtmlEntities(untagged)
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

/** Частые именованные и любые числовые спецсимволы; неизвестные честно остаются. */
private fun decodeHtmlEntities(s: String): String {
    val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "laquo" to "«", "raquo" to "»", "ldquo" to "“", "rdquo" to "”",
        "lsquo" to "‘", "rsquo" to "’", "times" to "×", "deg" to "°",
    )
    return Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.substring(2).toIntOrNull(16)?.toChar()?.toString() ?: m.value
            body.startsWith("#") ->
                body.substring(1).toIntOrNull()?.toChar()?.toString() ?: m.value
            else -> named[body] ?: m.value
        }
    }
}

/** epub: zip, порядок чтения задаёт spine в content.opf, главы - html. */
fun decodeEpubText(bytes: ByteArray): String? {
    return try {
        val parts = zipEntries(bytes)
        val container = parts["META-INF/container.xml"] ?: return null
        val rootfileTags = parseXml(container).getElementsByTagName("rootfile")
        if (rootfileTags.length == 0) return null
        val rootfilePath = (rootfileTags.item(0) as Element).getAttribute("full-path")
        if (rootfilePath.isBlank()) return null
        val opfBytes = parts[rootfilePath] ?: return null
        val base = rootfilePath.substringBeforeLast('/', "")

        val hrefById = HashMap<String, String>()
        val opf = parseXml(opfBytes)
        val items = opf.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val item = items.item(i) as Element
            val id = item.getAttribute("id")
            if (id.isNotBlank()) hrefById[id] = item.getAttribute("href")
        }
        val refs = opf.getElementsByTagName("itemref")
        if (refs.length == 0) return null
        val chunks = (0 until refs.length)
            .mapNotNull { i -> hrefById[(refs.item(i) as Element).getAttribute("idref")] }
            .map { href -> normalizeZipPath(if (base.isBlank()) href else "$base/$href") }
            .mapNotNull { path -> parts[path] }
            .map { chapter -> htmlToText(decodeTableText(chapter)) }
            .filter { it.isNotBlank() }
        chunks.joinToString("\n\n")
    } catch (e: Exception) {
        null
    }
}

/** «a/./b/../c» → «a/c»: пути href внутри epub бывают с точками. */
internal fun normalizeZipPath(path: String): String {
    val out = mutableListOf<String>()
    for (part in path.split('/')) {
        when (part) {
            "", "." -> {}
            ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            else -> out.add(part)
        }
    }
    return out.joinToString("/")
}

/**
 * rtf: управляющие слова на месте, без библиотек. Служебные группы (шрифты,
 * стили, картинки, сведения) пропускаются, текст собирается из \'hh (cp1251)
 * и \uN с пропуском анси-запаса после юникода (uc). null - не rtf.
 */
fun decodeRtfText(bytes: ByteArray): String? {
    if (bytes.size < 5) return null
    if (!String(bytes.copyOfRange(0, 5), Charsets.US_ASCII).startsWith("{\\rtf")) return null
    return try {
        // Байты ≥128 встречаются только внутри пропускаемых групп (картинки),
        // анси-текст приходит эскейпами \'hh - ASCII-развёртка файла безопасна.
        rtfBody(String(bytes, Charsets.US_ASCII)).trim()
    } catch (e: Exception) {
        null
    }
}

/** Служебные группы rtf: их содержимое текстом не бывает. */
private val RTF_SKIP_DESTS = setOf(
    "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer",
    "headerl", "headerr", "headerf", "footerl", "footerr", "footerf", "footnote",
    "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "themedata",
    "colorschememapping", "latentstyles", "datastore", "wgrffmtfilter", "listpicture",
    "panose", "fname", "falt", "private", "pwptable",
)

/** Слово управления сразу после позиции: для распознавания destination-группы. */
private fun destWordAt(rtf: String, pos: Int): String? {
    var j = pos
    while (j < rtf.length && (rtf[j] == '\\' || rtf[j] == '*')) j++
    if (j >= rtf.length || !rtf[j].isLetter()) return null
    val word = StringBuilder()
    while (j < rtf.length && rtf[j].isLetter()) word.append(rtf[j++])
    return word.toString()
}

private fun rtfBody(rtf: String): String = buildString {
    var i = 0
    var skipping = 0 // глубина внутри пропускаемой группы; 0 - читаем текст
    var skipAnsi = 0 // анси-запас после \uN: столько следующих символов - мусор
    var uc = 1

    while (i < rtf.length) {
        val c = rtf[i]
        when {
            c == '{' -> {
                if (skipping > 0) {
                    skipping++
                } else {
                    val dest = destWordAt(rtf, i + 1)
                    if (dest != null && dest in RTF_SKIP_DESTS) skipping = 1
                }
                i++
            }
            c == '}' -> {
                if (skipping > 0) skipping--
                i++
            }
            c == '\\' && i + 1 < rtf.length -> when (val next = rtf[i + 1]) {
                '\\', '{', '}' -> {
                    if (skipAnsi > 0) skipAnsi-- else if (skipping == 0) append(next)
                    i += 2
                }
                '\'' -> {
                    val code = rtf.substring(i + 2, i + 4).toIntOrNull(16)
                    if (code == null) {
                        i += 2
                    } else {
                        if (skipAnsi > 0) skipAnsi-- else if (skipping == 0) append(cp1251Char(code))
                        i += 4
                    }
                }
                '~' -> {
                    if (skipAnsi > 0) skipAnsi-- else if (skipping == 0) append(' ')
                    i += 2
                }
                else -> {
                    var j = i + 1
                    val word = StringBuilder()
                    while (j < rtf.length && rtf[j].isLetter()) word.append(rtf[j++])
                    var param: Int? = null
                    var negative = false
                    if (j < rtf.length && rtf[j] == '-') {
                        negative = true
                        j++
                    }
                    if (j < rtf.length && rtf[j].isDigit()) {
                        val digits = StringBuilder()
                        while (j < rtf.length && rtf[j].isDigit()) digits.append(rtf[j++])
                        param = digits.toString().toInt() * if (negative) -1 else 1
                    }
                    if (j < rtf.length && rtf[j] == ' ') j++
                    if (skipping == 0) {
                        when (word.toString()) {
                            "par", "line" -> append('\n')
                            "tab" -> append('\t')
                            "emdash" -> append('—')
                            "endash" -> append('–')
                            "lquote", "lsquo" -> append('‘')
                            "rquote", "rsquo" -> append('’')
                            "ldquo" -> append('“')
                            "rdquo" -> append('”')
                            "bullet" -> append('•')
                            "uc" -> param?.let { uc = it }
                            "u" -> {
                                val code = param?.let { if (it < 0) it + 65536 else it }
                                if (code != null && code in 1..65535) append(code.toChar())
                                skipAnsi = uc
                            }
                            else -> {} // прочие слова на текст не влияют
                        }
                    }
                    i = j
                }
            }
            else -> {
                // Переносы самого файла - форматирование, текстом не являются.
                if (skipping == 0 && c != '\r' && c != '\n') {
                    if (skipAnsi > 0) skipAnsi-- else append(c)
                }
                i++
            }
        }
    }
}

/** cp1251-символ по байту: та же кодировка, что у табличных файлов. */
private fun cp1251Char(code: Int): Char = String(byteArrayOf(code.toByte()), CP1251).first()
