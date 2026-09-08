package com.zhao.suiji.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 笔记实体（v1.2 起带置顶）。
 * title 允许为空，展示层用 autoDeriveTitle 取正文前 20 字兜底。
 * deletedAt：0 = 正常笔记；> 0 = 进回收站的时间戳（30 天后自动清除）。
 * pinned：置顶笔记在列表最前，其余仍按更新时间倒序。
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long = 0,
    val pinned: Boolean = false,
)
