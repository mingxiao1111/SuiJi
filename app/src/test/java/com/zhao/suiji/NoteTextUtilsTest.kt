package com.zhao.suiji

import com.zhao.suiji.text.NoteTextUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NoteTextUtils 全函数覆盖（计划 13.4 必写单测）。 */
class NoteTextUtilsTest {

    // ---- 有序分点 ----

    @Test
    fun `首行点有序分点从1开始`() {
        val edit = NoteTextUtils.insertOrderedPrefix("hello", 0, 0)!!
        assertEquals(0, edit.start)
        assertEquals("1. ", edit.insert)
        assertEquals(3, edit.newSelection)
    }

    @Test
    fun `上一行有编号则续号`() {
        val text = "1. first\n2. second\nthird"
        // 光标在第三行行首（index 19，第二个换行符之后）
        val edit = NoteTextUtils.insertOrderedPrefix(text, 19, 0)!!
        assertEquals(19, edit.start)
        assertEquals("3. ", edit.insert)
    }

    @Test
    fun `有序分点不重复插入`() {
        assertNull(NoteTextUtils.insertOrderedPrefix("2. already", 0, 0))
        assertNull(NoteTextUtils.insertOrderedPrefix("2. already", 5, 0))
        assertNull(NoteTextUtils.insertOrderedPrefix("① already", 2, 0))
    }

    @Test
    fun `圈数字样式与回退`() {
        assertEquals("① ", NoteTextUtils.orderedPrefix(1, 2))
        assertEquals("⑳ ", NoteTextUtils.orderedPrefix(20, 2))
        assertEquals("21. ", NoteTextUtils.orderedPrefix(21, 2))
        assertEquals("1、", NoteTextUtils.orderedPrefix(1, 1))
    }

    @Test
    fun `圈数字上一行续号`() {
        val text = "① one\nrest"
        val edit = NoteTextUtils.insertOrderedPrefix(text, 7, 2)!!
        assertEquals("② ", edit.insert)
    }

    // ---- 无序分点 ----

    @Test
    fun `无序分点三种样式`() {
        assertEquals("• ", NoteTextUtils.unorderedPrefix(0))
        assertEquals("- ", NoteTextUtils.unorderedPrefix(1))
        assertEquals("· ", NoteTextUtils.unorderedPrefix(2))
    }

    @Test
    fun `无序分点不重复插入`() {
        assertNull(NoteTextUtils.insertUnorderedPrefix("• item", 0, 0))
        assertNull(NoteTextUtils.insertUnorderedPrefix("- item", 3, 0))
    }

    @Test
    fun `无序分点插入行首`() {
        val edit = NoteTextUtils.insertUnorderedPrefix("abc", 1, 0)!!
        assertEquals(0, edit.start)
        assertEquals("• ", edit.insert)
    }

    // ---- 待办 ----

    @Test
    fun `待办三态循环`() {
        val insert = NoteTextUtils.toggleTodo("task", 0)
        assertEquals("☐ ", insert.insert)

        val toDone = NoteTextUtils.toggleTodo("☐ task", 2)
        assertEquals(NoteTextUtils.TODO_DONE, toDone.insert)
        assertEquals(0, toDone.start)
        assertEquals(NoteTextUtils.TODO_DONE.length, toDone.end)

        val toTodo = NoteTextUtils.toggleTodo("☑ task", 2)
        assertEquals(NoteTextUtils.TODO_TODO, toTodo.insert)
    }

    // ---- 缩进 ----

    @Test
    fun `缩进加两空格`() {
        val edit = NoteTextUtils.indentLine("line", 2)
        assertEquals(0, edit.start)
        assertEquals("  ", edit.insert)
        assertEquals(4, edit.newSelection)
    }

    @Test
    fun `反缩进最多去两空格`() {
        val two = NoteTextUtils.outdentLine("  line", 4)!!
        assertEquals(2, two.end)
        assertEquals("", two.insert)
        assertEquals(2, two.newSelection)

        val one = NoteTextUtils.outdentLine(" line", 3)!!
        assertEquals(1, one.end)

        assertNull(NoteTextUtils.outdentLine("line", 2))
    }

    // ---- 时间戳 ----

    @Test
    fun `时间戳格式`() {
        assertTrue(NoteTextUtils.timestamp().matches(Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    }
}
