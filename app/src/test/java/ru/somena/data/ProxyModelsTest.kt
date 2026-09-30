package ru.somena.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каталог «Популярных API» (proxyapi): пять лёгких и пять тяжёлых моделей, дешёвые
 * сверху, у каждой свой адрес и протокол запроса (проверено живыми вызовами 30.09.2026).
 */
class ProxyModelsTest {

    @Test
    fun `в каждой ступени пять моделей`() {
        assertEquals(5, ProxyModels.FAST.size)
        assertEquals(5, ProxyModels.MAX.size)
    }

    @Test
    fun `модели отсортированы по цене ввода - дешёвые сверху`() {
        assertTrue(ProxyModels.FAST.zipWithNext().all { (a, b) -> a.inputRub <= b.inputRub })
        assertTrue(ProxyModels.MAX.zipWithNext().all { (a, b) -> a.inputRub <= b.inputRub })
    }

    @Test
    fun `у каждой модели известный протокол и адрес proxyapi`() {
        (ProxyModels.FAST + ProxyModels.MAX).forEach { m ->
            assertTrue("модель ${m.id}: протокол ${m.protocol}", m.protocol == PROTOCOL_OPENAI || m.protocol == PROTOCOL_ANTHROPIC)
            assertTrue("модель ${m.id}: адрес ${m.baseUrl}", m.baseUrl.startsWith("https://api.proxyapi.ru/"))
        }
    }

    @Test
    fun `модель находится по id а чужой id даёт null`() {
        assertEquals("claude-sonnet-5-5", ProxyModels.byId("claude-sonnet-5-5")?.id)
        assertEquals(null, ProxyModels.byId("нет-такой"))
    }

    @Test
    fun `адрес модели зависит от протокола - claude на anthropic, gpt на openai`() {
        val claude = ProxyModels.byId("claude-haiku-4-5-20251001")!!
        assertEquals(ProxyModels.ANTHROPIC_URL, claude.baseUrl)
        assertEquals(PROTOCOL_ANTHROPIC, claude.protocol)
        val gpt = ProxyModels.byId("gpt-5.1")!!
        assertEquals(ProxyModels.OPENAI_URL, gpt.baseUrl)
        assertEquals(PROTOCOL_OPENAI, gpt.protocol)
    }

    @Test
    fun `подпись цены читается человеком`() {
        assertEquals("13 ₽ за 1М", ProxyModels.priceLabel(ProxyModels.byId("gpt-5-nano")!!))
        assertEquals("13 ₽", ProxyModels.shortPriceLabel(ProxyModels.byId("gpt-5-nano")!!))
        assertEquals("1 520 ₽", ProxyModels.shortPriceLabel(ProxyModels.byId("gpt-5.5")!!))
    }
}
