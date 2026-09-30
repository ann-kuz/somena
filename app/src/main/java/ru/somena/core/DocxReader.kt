package ru.somena.core

import org.w3c.dom.Element

/**
 * Мини-ридер docx (Разбор документа Медкарты): zip + XML, без тяжёлых библиотек,
 * по образцу xlsx-ридера. Абзацы w:p становятся строками: текст собирается из
 * прогонов w:t, w:tab - табуляция, w:br - перенос; абзацы таблиц попадают в тот
 * же поток строк. null - файл не docx (битый zip или без word/document.xml);
 * пустая строка - валидный документ без текста.
 */
fun decodeDocxText(bytes: ByteArray): String? = try {
    val document = zipEntries(bytes)["word/document.xml"] ?: return null
    val paragraphs = parseXml(document).getElementsByTagName("w:p")
    (0 until paragraphs.length)
        .joinToString("\n") { i -> paragraphText(paragraphs.item(i) as Element) }
        .trim()
} catch (e: Exception) {
    null
}

/** Текст одного абзаца по дочерним узлам в порядке документа: спецузлы - символами. */
private fun paragraphText(p: Element): String = buildString {
    fun walk(el: Element) {
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element) {
                when (node.tagName) {
                    "w:t" -> append(node.textContent)
                    "w:tab" -> append('\t')
                    "w:br", "w:cr" -> append('\n')
                    else -> walk(node)
                }
            }
        }
    }
    walk(p)
}
