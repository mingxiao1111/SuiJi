package com.zhao.suiji.ai

/**
 * 剪贴板自采历史（a7-1）：Android 10+ 禁止后台监听剪贴板（仅持焦窗口可读），
 * 历史只能在"AI 输入框打开 / 选择面板打开"这两个持焦时机读取当前剪贴板累积。
 * 仅内存、不持久化（隐私）；纯逻辑便于单测。
 */
class ClipHistory(private val max: Int = 10) {

    private val items = ArrayDeque<String>()

    /** 入列：空白忽略，重复则置顶，超限淘汰最旧。返回是否真的发生了变化。 */
    fun add(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (items.firstOrNull() == t) return false // 与最新一条相同（常见：面板反复打开）
        items.remove(t)
        items.addFirst(t)
        while (items.size > max) items.removeLast()
        return true
    }

    /** 当前历史，最新在前。 */
    fun items(): List<String> = items.toList()
}
