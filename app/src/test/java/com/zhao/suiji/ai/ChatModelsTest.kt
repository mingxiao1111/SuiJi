package com.zhao.suiji.ai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // 与 AiChatClient 的配置保持一致
    }

    // ---- StreamChunk：各家 OpenAI 兼容实现的流式分片 ----

    @Test
    fun `标准 content 分片`() {
        val c = json.decodeFromString(
            StreamChunk.serializer(),
            """{"choices":[{"delta":{"content":"你好"}}]}""",
        )
        assertEquals("你好", c.choices[0].delta.content)
        assertNull(c.choices[0].delta.reasoningContent)
    }

    @Test
    fun `DeepSeek 式思考过程字段被容忍`() {
        val c = json.decodeFromString(
            StreamChunk.serializer(),
            """{"choices":[{"delta":{"reasoning_content":"让我想想","content":null}}]}""",
        )
        assertEquals("让我想想", c.choices[0].delta.reasoningContent)
        assertNull(c.choices[0].delta.content)
    }

    @Test
    fun `结束分片缺 delta 与未知字段`() {
        val c = json.decodeFromString(
            StreamChunk.serializer(),
            """{"choices":[{}],"usage":{"total_tokens":42},"id":"chatcmpl-x"}""",
        )
        assertEquals(1, c.choices.size)
        assertNull(c.choices[0].delta.content)
    }

    @Test
    fun `choices 缺省为空列表`() {
        val c = json.decodeFromString(StreamChunk.serializer(), """{}""")
        assertEquals(0, c.choices.size)
    }

    // ---- ChatRequest 序列化 ----

    @Test
    fun `请求体带 stream 与消息数组`() {
        val req = ChatRequest(
            model = "deepseek-chat",
            messages = listOf(
                RequestMessage("user", "你好"),
                RequestMessage("assistant", "你好！"),
            ),
        )
        val s = json.encodeToString(ChatRequest.serializer(), req)
        assertEquals(
            """{"model":"deepseek-chat","messages":[{"role":"user","content":"你好"},""" +
                """{"role":"assistant","content":"你好！"}],"stream":true}""",
            s,
        )
    }
}
