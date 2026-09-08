package com.zhao.suiji.util

/** 列表展示用的标题 / 摘要派生（计划 4.4：标题为空取正文前 20 字）。 */
object NoteDisplay {

    fun deriveTitle(title: String, content: String): String {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) return trimmed
        return previewLine(content).take(20)
    }

    /** 摘要：正文第一个非空行。 */
    fun previewLine(content: String): String =
        content.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
}
