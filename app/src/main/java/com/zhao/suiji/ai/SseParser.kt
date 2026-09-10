package com.zhao.suiji.ai

/**
 * 增量 SSE 解析器（纯逻辑，便于单测）：feed 任意切割的网络文本块，
 * 吐出其中所有完整 data 负载（已去 "data:" 前缀与一个引导空格）。
 * 规则：只认 data: 行；注释行（:开头）与 event:/id: 行忽略；
 * 兼容 \n 与 \r\n 换行；"data: [DONE]" 原样返回，由调用方识别。
 */
class SseParser {

    private val pending = StringBuilder()

    fun feed(chunk: String): List<String> {
        pending.append(chunk)
        val out = mutableListOf<String>()
        while (true) {
            val nl = findNewline(pending) ?: break
            val line = pending.substring(0, nl.first).trimEnd('\r')
            pending.delete(0, nl.first + nl.second)
            val payload = line
                .takeIf { it.startsWith("data:") }
                ?.substring(5)
                ?.removePrefix(" ")
            if (!payload.isNullOrBlank()) out += payload
        }
        return out
    }

    /** 返回 (换行符下标, 换行符长度)；无换行返回 null。 */
    private fun findNewline(sb: StringBuilder): Pair<Int, Int>? {
        for (i in 0 until sb.length) {
            when (sb[i]) {
                '\n' -> return i to 1
                '\r' -> return if (i + 1 < sb.length && sb[i + 1] == '\n') i to 2 else i to 1
            }
        }
        return null
    }
}
