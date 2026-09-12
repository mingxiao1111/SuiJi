package com.zhao.suiji.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 剪贴板自采历史（a7-1）。 */
class ClipHistoryTest {

    @Test
    fun `空白与纯空白字符被忽略`() {
        val h = ClipHistory()
        assertFalse(h.add(""))
        assertFalse(h.add("   \n "))
        assertTrue(h.items().isEmpty())
    }

    @Test
    fun `新条目置顶且去重`() {
        val h = ClipHistory()
        h.add("甲")
        h.add("乙")
        assertEquals(listOf("乙", "甲"), h.items())
        h.add("甲") // 旧条目重录 -> 置顶
        assertEquals(listOf("甲", "乙"), h.items())
    }

    @Test
    fun `与最新一条相同不重复入列`() {
        val h = ClipHistory()
        h.add("甲")
        assertFalse(h.add("甲"))
        assertEquals(listOf("甲"), h.items())
    }

    @Test
    fun `超限淘汰最旧`() {
        val h = ClipHistory(max = 3)
        h.add("甲")
        h.add("乙")
        h.add("丙")
        h.add("丁") // 淘汰最旧的甲
        assertEquals(listOf("丁", "丙", "乙"), h.items())
    }
}
