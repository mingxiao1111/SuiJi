package com.zhao.suiji.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    /** 列表页：置顶在最前，其余按更新时间倒序（回收站里的不算）。 */
    @Query("SELECT * FROM notes WHERE deletedAt = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<Note>>

    /** 一次性全量（备份导出用）。 */
    @Query("SELECT * FROM notes WHERE deletedAt = 0 ORDER BY pinned DESC, updatedAt DESC")
    suspend fun getAllActive(): List<Note>

    /** 回收站页：按删除时间倒序。 */
    @Query("SELECT * FROM notes WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<Note>>

    /** 编辑页 / 悬浮窗订阅同一条笔记，实现两侧实时同步（计划 7.8）。 */
    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: Long): Flow<Note?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): Note?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: Note): Long

    /** 只更新内容与时间戳，避免整行替换覆盖并发写入。 */
    @Query("UPDATE notes SET title = :title, content = :content, updatedAt = :updatedAt WHERE id = :id")
    suspend fun update(id: Long, title: String, content: String, updatedAt: Long)

    /** 悬浮窗只编辑正文，标题保持不变（计划 M4）。 */
    @Query("UPDATE notes SET content = :content, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, updatedAt: Long)

    /** 最近编辑的一篇（正常笔记），悬浮窗默认绑定它（计划 4.5）。 */
    @Query("SELECT * FROM notes WHERE deletedAt = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatest(): Note?

    // ---- 回收站（v1.1）----

    /** 移入回收站：记录删除时间，30 天后可被自动清除。 */
    @Query("UPDATE notes SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun moveToTrash(id: Long, deletedAt: Long)

    /** 置顶/取消置顶（v1.2）。 */
    @Query("UPDATE notes SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    /** 从回收站恢复为正常笔记。 */
    @Query("UPDATE notes SET deletedAt = 0, updatedAt = :updatedAt WHERE id = :id")
    suspend fun restore(id: Long, updatedAt: Long)

    /** 彻底删除单条。 */
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 清空回收站，返回删除的条数。 */
    @Query("DELETE FROM notes WHERE deletedAt > 0")
    suspend fun purgeAllTrashed(): Int

    /** 自动清除：删掉进回收站超过保留期的笔记（App 启动时调用）。 */
    @Query("DELETE FROM notes WHERE deletedAt > 0 AND deletedAt < :cutoff")
    suspend fun purgeExpired(cutoff: Long): Int
}
