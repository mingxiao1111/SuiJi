package com.zhao.suiji.data

import kotlinx.coroutines.flow.Flow

/**
 * 笔记数据仓库：主界面与悬浮窗共用同一实例（App 级单例），
 * 所有读写都经过这里，避免两处写入互相覆盖（计划 11.11）。
 *
 * 删除语义（v1.1 起）：用户可见的"删除"一律是软删除进回收站（30 天可恢复），
 * 仅"回收站彻底删除/清空/过期清除"和"新建空笔记退出时的清理"走物理删除。
 */
class NoteRepository(private val dao: NoteDao) {

    companion object {
        const val TITLE_PREVIEW_LENGTH = 20
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000 // 回收站保留 30 天
    }

    fun observeAll(): Flow<List<Note>> = dao.observeAll()

    fun observeTrashed(): Flow<List<Note>> = dao.observeTrashed()

    fun observeById(id: Long): Flow<Note?> = dao.observeById(id)

    suspend fun getNote(id: Long): Note? = dao.getById(id)

    /** 新建笔记，返回新笔记 id。 */
    suspend fun createNote(title: String = "", content: String = ""): Long {
        val now = System.currentTimeMillis()
        return dao.insert(Note(title = title, content = content, createdAt = now, updatedAt = now))
    }

    /** 保存编辑内容并刷新 updatedAt。 */
    suspend fun updateNote(id: Long, title: String, content: String) {
        dao.update(id, title, content, System.currentTimeMillis())
    }

    /** 悬浮窗保存：只改正文。 */
    suspend fun updateContent(id: Long, content: String) {
        dao.updateContent(id, content, System.currentTimeMillis())
    }

    /** 最近编辑的一篇（悬浮窗默认绑定，计划 4.5；回收站笔记不参与）。 */
    suspend fun getLatestNote(): Note? = dao.getLatest()

    // ---- 回收站（v1.1）----

    /** 置顶/取消置顶（v1.2）。 */
    suspend fun setPinned(id: Long, pinned: Boolean) = dao.setPinned(id, pinned)

    /** 移入回收站（用户主删除路径）。 */
    suspend fun moveToTrash(id: Long) = dao.moveToTrash(id, System.currentTimeMillis())

    suspend fun restoreNote(id: Long) = dao.restore(id, System.currentTimeMillis())

    suspend fun purgeNote(id: Long) = dao.deleteById(id)

    suspend fun emptyTrash(): Int = dao.purgeAllTrashed()

    /** 清掉超过保留期的回收站笔记（App 启动时调用）。 */
    suspend fun purgeExpiredTrash(): Int =
        dao.purgeExpired(System.currentTimeMillis() - TRASH_RETENTION_MS)

    /** 物理删除：仅用于"新建空笔记直接退出"的清理，不进回收站。 */
    suspend fun deleteNote(id: Long) = dao.deleteById(id)

    // ---- 备份（v1.1）----

    /** 导出正常笔记（回收站里的不导出）为人可读且可导回的文本。 */
    suspend fun buildBackupText(): String {
        val list = dao.getAllActive()
        return BackupFormat.build(list.map { BackupFormat.BackupNote(it.title, it.content, it.updatedAt) })
    }

    /** 从备份文本导入，跳过与现有笔记标题+正文完全相同的条目。返回 (导入数, 跳过数)。 */
    suspend fun importBackupText(text: String): Pair<Int, Int>? {
        val parsed = BackupFormat.parse(text) ?: return null
        val existing = dao.getAllActive()
            .map { it.title.trim() to it.content.trim() }
            .toSet()
        var imported = 0
        var skipped = 0
        parsed.forEach { b ->
            val key = b.title.trim() to b.content.trim()
            if (key in existing) {
                skipped++
            } else {
                val now = System.currentTimeMillis()
                dao.insert(
                    Note(title = b.title, content = b.content, createdAt = now, updatedAt = b.updatedAt),
                )
                imported++
            }
        }
        return imported to skipped
    }

    /** 标题为空时取正文第一个非空行的前 20 字当显示标题（计划 4.4）。 */
    fun autoDeriveTitle(title: String, content: String): String {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) return trimmed
        val firstLine = content.lines().firstOrNull { it.isNotBlank() } ?: ""
        return firstLine.trim().take(TITLE_PREVIEW_LENGTH)
    }
}
