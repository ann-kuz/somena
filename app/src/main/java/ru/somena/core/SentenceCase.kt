package ru.somena.core

/**
 * Автозаглавие поля ввода Чата: каждое новое предложение начинается с большой
 * буквы, даже если клавиатура не делает этого сама. На входе - текст поля до
 * и после правки, как их видит onValueChange. Правится только чистая вставка
 * (печать или вставка из буфера): удаления и автозамену слов клавиатурой
 * функция не трогает, чтобы не бороться с ней за буквы.
 */
fun sentenceCaseTyped(old: String, new: String): String {
    var p = 0
    while (p < old.length && p < new.length && old[p] == new[p]) p++
    var s = 0
    while (s < old.length - p && s < new.length - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
    // Между общими префиксом и суффиксом должен лежать только добавленный текст:
    // если там остался старый кусок, клавиатура заменила слово - не вмешиваемся.
    if (s < old.length - p) return new
    if (new.length - s <= p) return new
    val c = new[p]
    if (!c.isLetter() || !c.isLowerCase()) return new
    if (!startsSentence(new, p)) return new
    return new.substring(0, p) + c.uppercaseChar() + new.substring(p + 1)
}

/** Начинает ли позиция новое предложение: начало текста, строка выше (перевод
 *  строки кончает предложение даже без точки) или пробелы после знака конца. */
private fun startsSentence(text: String, at: Int): Boolean {
    var i = at - 1
    while (i >= 0) {
        val c = text[i]
        if (c == '\n') return true
        if (!c.isWhitespace()) return c in ".!?…"
        i--
    }
    return true
}
