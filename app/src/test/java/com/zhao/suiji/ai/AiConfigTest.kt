package com.zhao.suiji.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConfigTest {

    // ---- normalizeBaseUrl ----

    @Test
    fun `空与纯空白返回空串`() {
        assertEquals("", normalizeBaseUrl(""))
        assertEquals("", normalizeBaseUrl("   "))
    }

    @Test
    fun `缺协议自动补 https`() {
        assertEquals("https://api.deepseek.com", normalizeBaseUrl("api.deepseek.com"))
    }

    @Test
    fun `保留用户指定的 http 明文端点（本地模型）`() {
        assertEquals("http://192.168.1.10:11434/v1", normalizeBaseUrl("http://192.168.1.10:11434/v1"))
    }

    @Test
    fun `去掉一个或多个结尾斜杠`() {
        assertEquals("https://api.openai.com/v1", normalizeBaseUrl("https://api.openai.com/v1/"))
        assertEquals("https://a.com", normalizeBaseUrl("https://a.com//"))
    }

    @Test
    fun `智谱式多级路径原样保留`() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            normalizeBaseUrl("  https://open.bigmodel.cn/api/paas/v4  "),
        )
    }

    @Test
    fun `协议大小写不敏感`() {
        assertEquals("https://a.com", normalizeBaseUrl("HTTPS://a.com"))
    }

    // ---- isConfigured ----

    @Test
    fun `地址 Key 普通模型齐备才算已配置`() {
        assertTrue(AiConfig("api.deepseek.com", "sk-x", "deepseek-chat").isConfigured)
    }

    @Test
    fun `缺任一项视为未配置`() {
        assertFalse(AiConfig("", "sk-x", "m").isConfigured)
        assertFalse(AiConfig("https://a.com", " ", "m").isConfigured)
        assertFalse(AiConfig("https://a.com", "sk-x", "").isConfigured)
    }

    @Test
    fun `思考与视觉模型不参与已配置判定`() {
        assertTrue(AiConfig("https://a.com", "sk-x", "m", thinkModel = "", visionModel = "").isConfigured)
    }
}
