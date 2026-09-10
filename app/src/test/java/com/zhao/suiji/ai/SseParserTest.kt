package com.zhao.suiji.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class SseParserTest {

    private val p = SseParser()

    // ---- 基本解析 ----

    @Test
    fun `单块单行 data 负载`() {
        assertEquals(listOf("""{"a":1}"""), p.feed("data: {\"a\":1}\n\n"))
    }

    @Test
    fun `data 冒号后无空格也能解析`() {
        assertEquals(listOf("hello"), p.feed("data:hello\n"))
    }

    @Test
    fun `一个块里多行 data 各自成负载`() {
        assertEquals(
            listOf("aa", "bb"),
            p.feed("data: aa\ndata: bb\n"),
        )
    }

    @Test
    fun `DONE 标记原样返回由调用方识别`() {
        assertEquals(listOf("[DONE]"), p.feed("data: [DONE]\n\n"))
    }

    // ---- 网络块切割 ----

    @Test
    fun `块从 data 前缀中间断开`() {
        assertEquals(emptyList<String>(), p.feed("da"))
        assertEquals(emptyList<String>(), p.feed("ta: he"))
        assertEquals(listOf("hello"), p.feed("llo\n"))
    }

    @Test
    fun `块从负载中间断开`() {
        assertEquals(emptyList<String>(), p.feed("data: 你好"))
        assertEquals(listOf("你好世界"), p.feed("世界\n"))
    }

    @Test
    fun `换行符本身被拆成单独的块`() {
        assertEquals(emptyList<String>(), p.feed("data: x"))
        assertEquals(listOf("x"), p.feed("\r")) // 缓冲区末尾的孤立 CR 即视为换行
        assertEquals(emptyList<String>(), p.feed("\n")) // 残余 \n 成空行，忽略
    }

    // ---- 换行与噪声 ----

    @Test
    fun `CRLF 换行`() {
        assertEquals(listOf("a", "b"), p.feed("data: a\r\ndata: b\r\n"))
    }

    @Test
    fun `孤立 CR 也当换行`() {
        assertEquals(listOf("a"), p.feed("data: a\rdata: b"))
        // b 尚无换行结算，再补一个
        assertEquals(listOf("b"), p.feed("\n"))
    }

    @Test
    fun `注释行与 event 行被忽略`() {
        assertEquals(listOf("x"), p.feed(": keep-alive\nevent: message\ndata: x\nid: 1\n"))
    }

    @Test
    fun `空 data 行不产出负载`() {
        assertEquals(emptyList<String>(), p.feed("data:\ndata:   \n"))
    }

    @Test
    fun `中文负载完整保留`() {
        assertEquals(listOf("回复：两件事"), p.feed("data: 回复：两件事\n"))
    }
}
