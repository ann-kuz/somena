package ru.somena.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Автозаглавие чата: каждое новое предложение начинается с большой буквы.
 * Шов - пара «текст поля до правки, текст после», как её видит onValueChange:
 * чистая логика без клавиатуры и эмулятора. Вмешивается только в чистую
 * вставку (печать, вставка из буфера) и не трогает удаления и автозамену
 * клавиатуры, чтобы не бороться с ней за буквы.
 */
class SentenceCaseTest {

    @Test
    fun `первое предложение сообщения начинается с заглавной`() {
        assertEquals("П", sentenceCaseTyped("", "п"))
        assertEquals("Привет", sentenceCaseTyped("Приве", "Привет"))
        // продолжение набора в середине - первая буква уже напечатана, задним числом не правим
        assertEquals("почему вес встал", sentenceCaseTyped("почему", "почему вес встал"))
    }

    @Test
    fun `новое предложение после знака конца получает заглавную`() {
        assertEquals("Вес встал. Почему?", sentenceCaseTyped("Вес встал. ", "Вес встал. почему?"))
        assertEquals("Вес встал! Почему?", sentenceCaseTyped("Вес встал! ", "Вес встал! почему?"))
        assertEquals("Вес встал? Почему?", sentenceCaseTyped("Вес встал? ", "Вес встал? почему?"))
        assertEquals("Вес встал… Почему?", sentenceCaseTyped("Вес встал… ", "Вес встал… почему?"))
    }

    @Test
    fun `новая строка тоже начинает предложение`() {
        assertEquals("Первая.\nВторая", sentenceCaseTyped("Первая.\n", "Первая.\nвторая"))
        assertEquals("Первая\nВторая", sentenceCaseTyped("Первая\n", "Первая\nвторая"))
    }

    @Test
    fun `вставка посреди предложения остаётся строчной`() {
        assertEquals("почему вес", sentenceCaseTyped("почему ", "почему вес"))
        assertEquals("Вес: почему", sentenceCaseTyped("Вес: ", "Вес: почему"))
    }

    @Test
    fun `цифра перед вставкой - не конец предложения`() {
        // «2100.» и следом вставка: перед ней цифра, заглавие не нужно
        assertEquals("запиши 2100.5 ккал", sentenceCaseTyped("запиши 2100.", "запиши 2100.5 ккал"))
    }

    @Test
    fun `много пробелов после точки не мешают заглавию`() {
        assertEquals("Встал.   Почему", sentenceCaseTyped("Встал.   ", "Встал.   почему"))
    }

    @Test
    fun `удаление не трогается`() {
        assertEquals("Вес вста", sentenceCaseTyped("Вес встал", "Вес вста"))
    }

    @Test
    fun `автозамена клавиатуры - не вставка, не вмешиваемся`() {
        assertEquals("привет", sentenceCaseTyped("превет", "привет"))
        assertEquals("Вес встал. привет", sentenceCaseTyped("Вес встал. превет", "Вес встал. привет"))
    }

    @Test
    fun `вставка не-буквы не меняется`() {
        assertEquals("Сожжено 2100", sentenceCaseTyped("Сожжено ", "Сожжено 2100"))
        assertEquals("Встал. ", sentenceCaseTyped("Встал", "Встал. "))
    }

    @Test
    fun `целая фраза после точки - заглавная только первая буква`() {
        assertEquals("Встал. Почему, спросила она", sentenceCaseTyped("Встал. ", "Встал. почему, спросила она"))
    }

    @Test
    fun `уже заглавная буква остаётся как есть`() {
        assertEquals("Вес встал. Почему", sentenceCaseTyped("Вес встал. ", "Вес встал. Почему"))
    }
}
