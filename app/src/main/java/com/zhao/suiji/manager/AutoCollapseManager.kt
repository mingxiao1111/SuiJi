package com.zhao.suiji.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 自动收起计时（计划 4.3 / 7.6）。
 * 三源重置，缺一不可：
 * 1. 窗口任意触摸（onUserInteraction）
 * 2. 每一次文本变化（打字时触摸在输入法窗口，不经过悬浮窗）
 * 3. 工具栏按钮点击
 * delayMs <= 0 表示"永不"。
 */
class AutoCollapseManager(
    private val scope: CoroutineScope,
    private val onCollapse: () -> Unit,
) {

    @Volatile
    private var delayMs: Long = DEFAULT_DELAY_MS

    private var job: Job? = null

    /** 设置生效时长；0 = 永不（停止当前计时）。 */
    fun updateDelay(ms: Long) {
        delayMs = ms
        if (ms <= 0) {
            stop()
        } else {
            reset()
        }
    }

    /** 任意交互重置计时。 */
    fun reset() {
        if (delayMs <= 0) return
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            onCollapse()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        const val DEFAULT_DELAY_MS = 30_000L
    }
}
