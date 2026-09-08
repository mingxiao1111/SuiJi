package com.zhao.suiji.data

import com.zhao.suiji.util.TimeUtils

/**
 * 备份文件格式（v1.1）：既给人读、也能被自己解析回来。
 * 结构：文件头 + 若干笔记块，块与块用分隔行隔开；时间戳以毫秒数字写在括号里，
 * 解析优先取数字（人类可读时间仅辅助，不受时区/格式影响）。
 *
 * 例：
 * ```
 * 【浮记备份】导出时间：2026-08-30 16:00 (1753852800000)
 *
 * ===== 笔记 =====
 * 标题：MuMu Test
 * 更新：2026-08-30 15:29 (1753848540000)
 * --- 正文 ---
 * Hello from FloatNote
 * ===== 结束 =====
 * ```
 */
object BackupFormat {

    const val NOTE_BEGIN = "===== 笔记 ====="
    const val NOTE_END = "===== 结束 ====="
    const val BODY_MARKER = "--- 正文 ---"

    /** 一条待写入备份的笔记（title 原样，可为空）。 */
    data class BackupNote(val title: String, val content: String, val updatedAt: Long)

    fun build(notes: List<BackupNote>, now: Long = System.currentTimeMillis()): String = buildString {
        appendLine("【浮记备份】导出时间：${TimeUtils.formatDateTime(now)} ($now)")
        appendLine("笔记数：${notes.size}")
        appendLine()
        notes.forEach { n ->
            appendLine(NOTE_BEGIN)
            appendLine("标题：${n.title}")
            appendLine("更新：${TimeUtils.formatDateTime(n.updatedAt)} (${n.updatedAt})")
            appendLine(BODY_MARKER)
            appendLine(n.content)
            appendLine(NOTE_END)
            appendLine()
        }
    }

    /**
     * 解析备份文本。宽容策略：块内缺标题按空处理、时间戳解析失败按当前时间，
     * 保证任何一块损坏不拖垮整份导入。返回 null 表示整个文件不是浮记备份。
     * 游标式扫描：每块 = NOTE_BEGIN 之后到最近一个 NOTE_END——正文里出现
     * NOTE_BEGIN 不受影响（不能用整体 split，会被正文里的分隔行切碎）；
     * 仅正文里出现 NOTE_END 属于无法防御的极端情况。
     */
    fun parse(text: String, now: Long = System.currentTimeMillis()): List<BackupNote>? {
        if (!text.contains(NOTE_BEGIN)) return null
        val result = mutableListOf<BackupNote>()
        var cursor = text.indexOf(NOTE_BEGIN)
        while (cursor >= 0) {
            val blockStart = cursor + NOTE_BEGIN.length
            val end = text.indexOf(NOTE_END, blockStart)
            val block = if (end >= 0) text.substring(blockStart, end) else text.substring(blockStart)

            val lines = block.lines()
            var title = ""
            var updatedAt = now
            var bodyStart = lines.size
            lines.forEachIndexed { idx, line ->
                when {
                    line.startsWith("标题：") -> title = line.removePrefix("标题：").trim()
                    line.startsWith("更新：") ->
                        updatedAt = Regex("\\((\\d+)\\)").find(line)
                            ?.groupValues?.get(1)?.toLongOrNull() ?: now

                    line.trim() == BODY_MARKER && bodyStart == lines.size -> bodyStart = idx + 1
                }
            }
            val content = lines.drop(bodyStart).joinToString("\n").trim()
            if (title.isNotBlank() || content.isNotBlank()) {
                result.add(BackupNote(title, content, updatedAt))
            }

            cursor = if (end >= 0) text.indexOf(NOTE_BEGIN, end + NOTE_END.length) else -1
        }
        return result
    }
}
