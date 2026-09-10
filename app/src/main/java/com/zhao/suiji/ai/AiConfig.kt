package com.zhao.suiji.ai

/**
 * AI 助手配置（v2.1）。OpenAI 兼容接口：{base}/chat/completions 与 {base}/models。
 * 不自动补 /v1——各家路径前缀不同（OpenAI /v1、智谱 /api/paas/v4、DeepSeek 可不带），
 * 由用户在设置里填到 chats 路径的前一级。
 */
data class AiConfig(
    val baseUrl: String,
    val apiKey: String,
    val chatModel: String,
    val thinkModel: String = "",
    val visionModel: String = "",
) {
    /** 接口地址 + Key + 普通模型齐备才可用；思考/视觉模型按需选填。 */
    val isConfigured: Boolean
        get() = normalizeBaseUrl(baseUrl).isNotEmpty() && apiKey.isNotBlank() && chatModel.isNotBlank()
}

/** 规范化用户输入的接口地址：去空白、缺协议时补 https、协议统一小写、去结尾斜杠。 */
fun normalizeBaseUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    val withScheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "https://" + trimmed.substring(8)
        trimmed.startsWith("http://", ignoreCase = true) -> "http://" + trimmed.substring(7)
        else -> "https://$trimmed"
    }
    return withScheme.trimEnd('/')
}
