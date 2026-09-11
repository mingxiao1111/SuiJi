package com.zhao.suiji.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 对话消息（悬浮窗气泡的直接数据源；v1 只存服务内存，重启即清）。
 */
data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val state: State = State.DONE,
    /** 随问题携带的笔记正文（＋菜单"插入当前笔记"）：只进请求不进气泡（T4）。 */
    val context: String = "",
) {
    enum class Role { USER, ASSISTANT }

    /** DONE=完整；STREAMING=流式接收中；FAILED=请求失败（error 文案在气泡上）。 */
    enum class State { DONE, STREAMING, FAILED }
}

/** SSE 事件流（AiChatClient → 会话编排层）。HttpError 时 flow 结束。 */
sealed class ChatEvent {
    data class Delta(val text: String) : ChatEvent()
    data object Done : ChatEvent()
    data class HttpError(val code: Int, val message: String) : ChatEvent()
}

// ---- OpenAI 兼容 /chat/completions 的请求与流式响应 DTO ----

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<RequestMessage>,
    val stream: Boolean = true,
)

@Serializable
data class RequestMessage(
    val role: String, // "user" / "assistant" / "system"
    val content: String,
)

/** 一个流式分片：choices[0].delta。各家兼容实现都可能缺字段，全部给默认值。 */
@Serializable
data class StreamChunk(
    val choices: List<Choice> = emptyList(),
) {
    @Serializable
    data class Choice(val delta: Delta = Delta()) {
        /** reasoning_content 是 DeepSeek 等家的思考过程字段，v1 静默丢弃只展示 content。 */
        @Serializable
        data class Delta(
            @SerialName("reasoning_content") val reasoningContent: String? = null,
            val content: String? = null,
        )
    }
}
