package com.zhao.suiji.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 备份格式（v1.1）：构建与解析必须完全互通，损坏块不能拖垮整份导入。 */
class BackupFormatTest {

    private val notes = listOf(
        BackupFormat.BackupNote("标题A", "第一行\n第二行", updatedAt = 1000L),
        BackupFormat.BackupNote("", "无标题笔记正文", updatedAt = 2000L),
        BackupFormat.BackupNote("空正文", "", updatedAt = 3000L),
    )

    @Test
    fun `build 后 parse 能无损还原标题正文与时间戳`() {
        val text = BackupFormat.build(notes, now = 0L)
        val parsed = BackupFormat.parse(text, now = 0L)!!
        assertEquals(3, parsed.size)
        assertEquals("标题A", parsed[0].title)
        assertEquals("第一行\n第二行", parsed[0].content)
        assertEquals(1000L, parsed[0].updatedAt)
        assertEquals("", parsed[1].title)
        assertEquals("无标题笔记正文", parsed[1].content)
        assertEquals("空正文", parsed[2].title)
        assertEquals("", parsed[2].content)
    }

    @Test
    fun `多行正文中的分隔线干扰不影响解析`() {
        // 用户正文里可能出现与分隔线相似的行，块必须以 NOTE_END 收束
        val tricky = BackupFormat.BackupNote("t", "正文\n===== 笔记 =====\n继续", 1L)
        val parsed = BackupFormat.parse(BackupFormat.build(listOf(tricky), 0L), 0L)!!
        assertEquals(1, parsed.size)
        assertEquals("正文\n===== 笔记 =====\n继续", parsed[0].content)
    }

    @Test
    fun `非备份文件返回 null`() {
        assertNull(BackupFormat.parse("随便一段文字\n没有分隔线"))
    }

    @Test
    fun `时间戳解析失败回退到当前时间`() {
        val text = """
            【浮记备份】
            ===== 笔记 =====
            标题：X
            更新：某个手改坏的时间
            --- 正文 ---
            内容
            ===== 结束 =====
        """.trimIndent()
        val parsed = BackupFormat.parse(text, now = 42L)!!
        assertEquals(42L, parsed[0].updatedAt)
    }

    @Test
    fun `全空块被跳过不产生空笔记`() {
        val text = BackupFormat.build(notes, 0L) +
            "===== 笔记 =====\n标题：\n--- 正文 ---\n\n===== 结束 =====\n"
        val parsed = BackupFormat.parse(text, 0L)!!
        assertTrue(parsed.all { it.title.isNotBlank() || it.content.isNotBlank() })
        assertEquals(3, parsed.size)
    }
}
