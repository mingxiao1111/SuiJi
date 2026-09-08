package com.zhao.suiji.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 自动保存防抖（计划 7.5 / 12.4）。
 * 泛型 payload：主界面编辑页传 NoteDraft（标题+正文），M4 悬浮窗传 String（正文）。
 * 所有内容变化统一走这里防抖，避免两处各自实现计时逻辑。
 */
class AutoSaveManager<T>(
    private val scope: CoroutineScope,
    private val delayMs: Long = SAVE_DELAY_MS,
    private val save: (T) -> Unit,
) {

    private var job: Job? = null

    /** 内容变化：重置计时，静置 [delayMs] 后落库。 */
    fun onContentChanged(value: T) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            save(value)
        }
    }

    /** 立即保存（返回 / 最小化 / 关闭时调用，不等防抖）。 */
    fun saveNow(value: T) {
        job?.cancel()
        save(value)
    }

    /** 丢弃未触发的防抖任务（删除笔记时避免迟到写入）。 */
    fun cancelPending() {
        job?.cancel()
        job = null
    }

    companion object {
        const val SAVE_DELAY_MS = 1_000L
    }
}
