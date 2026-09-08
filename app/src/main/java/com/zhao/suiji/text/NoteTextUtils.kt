package com.zhao.suiji.text

/**
 * 文本格式化核心（计划 7.9 / 12.7）。
 * 全部为纯函数：输入"文本 + 光标位置 + 样式"，输出一次文本编辑 [TextEdit]，
 * 不持有状态、不碰数据库 / EditText，便于单元测试（计划 13.4）。
 * View 层负责把 [TextEdit] 应用到 Editable 并移动光标。
 */
object NoteTextUtils {

    /** 一次文本编辑：把 [start, end) 替换为 [insert]，然后把光标移到 [newSelection]。 */
    data class TextEdit(val start: Int, val end: Int, val insert: String, val newSelection: Int)

    /** 有序前缀：上一行有编号则续号，否则从 1 开始。已有前缀返回 null（不重复插入）。 */
    fun insertOrderedPrefix(text: String, selection: Int, olStyle: Int): TextEdit? {
        val sel = coerceSelection(text, selection)
        val lineStart = lineStartOf(text, sel)
        val lineEnd = lineEndOf(text, sel)
        val line = text.substring(lineStart, lineEnd)
        if (orderedNumberOf(line) != null || circledNumberOf(line) != null) return null

        val prevNum = if (lineStart == 0) 0 else {
            val prevStart = lineStartOf(text, lineStart - 1)
            val prevLine = text.substring(prevStart, lineStart - 1)
            orderedNumberOf(prevLine) ?: circledNumberOf(prevLine) ?: 0
        }
        val prefix = orderedPrefix(prevNum + 1, olStyle)
        return TextEdit(lineStart, lineStart, prefix, sel + prefix.length)
    }

    /** 无序前缀。行首已有任意无序符号则不重复。 */
    fun insertUnorderedPrefix(text: String, selection: Int, ulStyle: Int): TextEdit? {
        val sel = coerceSelection(text, selection)
        val lineStart = lineStartOf(text, sel)
        val lineEnd = lineEndOf(text, sel)
        val line = text.substring(lineStart, lineEnd)
        if (line.startsWith("•") || line.startsWith("- ") || line.startsWith("·")) return null
        val prefix = unorderedPrefix(ulStyle)
        return TextEdit(lineStart, lineStart, prefix, sel + prefix.length)
    }

    /** 待办切换：☐ -> ☑ -> ☐，都没有则插入 ☐（计划 4.2）。 */
    fun toggleTodo(text: String, selection: Int): TextEdit {
        val sel = coerceSelection(text, selection)
        val lineStart = lineStartOf(text, sel)
        val lineEnd = lineEndOf(text, sel)
        val line = text.substring(lineStart, lineEnd)
        return when {
            line.startsWith(TODO_DONE) ->
                TextEdit(lineStart, lineStart + TODO_DONE.length, TODO_TODO, sel)

            line.startsWith(TODO_TODO) ->
                TextEdit(lineStart, lineStart + TODO_TODO.length, TODO_DONE, sel)

            else -> TextEdit(lineStart, lineStart, "$TODO_TODO ", sel + TODO_TODO.length + 1)
        }
    }

    /** 行首缩进两个空格。 */
    fun indentLine(text: String, selection: Int): TextEdit {
        val sel = coerceSelection(text, selection)
        val lineStart = lineStartOf(text, sel)
        return TextEdit(lineStart, lineStart, "  ", sel + 2)
    }

    /** 行首去掉最多两个空格。 */
    fun outdentLine(text: String, selection: Int): TextEdit? {
        val sel = coerceSelection(text, selection)
        val lineStart = lineStartOf(text, sel)
        val lineEnd = lineEndOf(text, sel)
        val spaces = text.substring(lineStart, lineEnd).takeWhile { it == ' ' }.length
        val remove = spaces.coerceAtMost(2)
        if (remove == 0) return null
        return TextEdit(lineStart, lineStart + remove, "", sel - remove)
    }

    fun timestamp(): String = java.text.SimpleDateFormat(
        "MM-dd HH:mm",
        java.util.Locale.getDefault(),
    ).format(java.util.Date())

    // ---- 样式 ----

    /** ol_style：0 -> "1. "，1 -> "1、"，2 -> "① "（1~20 圈数字，超出回退阿拉伯，计划 4.2）。 */
    fun orderedPrefix(num: Int, style: Int): String = when (style) {
        1 -> "$num、"
        2 -> circledNumber(num)?.let { "$it " } ?: "$num. "
        else -> "$num. "
    }

    /** ul_style：0 -> "• "，1 -> "- "，2 -> "· "。 */
    fun unorderedPrefix(style: Int): String = when (style) {
        1 -> "- "
        2 -> "· "
        else -> "• "
    }

    fun circledNumber(n: Int): String? =
        if (n in 1..20) String(Character.toChars(CIRCLED_START + n - 1)) else null

    // ---- 内部工具 ----

    private const val CIRCLED_START = 0x2460 // ①
    private const val CIRCLED_END = 0x2473 // ⑳
    const val TODO_TODO = "☐"
    const val TODO_DONE = "☑"

    private val OL_REGEX = Regex("^(\\d+)[.、]")

    /** 行首有序编号（"3. " / "3、"），无则 null。 */
    private fun orderedNumberOf(line: String): Int? =
        OL_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()

    /** 行首圈数字（①~⑳），无则 null。 */
    private fun circledNumberOf(line: String): Int? =
        line.firstOrNull()?.code?.let { code ->
            if (code in CIRCLED_START..CIRCLED_END) code - CIRCLED_START + 1 else null
        }

    private fun coerceSelection(text: String, selection: Int): Int = selection.coerceIn(0, text.length)

    /** 光标（或 [forPosition] 前一个字符）所在行的行首下标。 */
    private fun lineStartOf(text: String, forPosition: Int): Int {
        val pos = forPosition.coerceIn(0, text.length)
        if (pos == 0) return 0
        return text.lastIndexOf('\n', pos - 1) + 1
    }

    /** 光标所在行的行尾下标（不含换行符）。 */
    private fun lineEndOf(text: String, selection: Int): Int {
        val sel = selection.coerceIn(0, text.length)
        return text.indexOf('\n', sel).takeIf { it >= 0 } ?: text.length
    }
}
