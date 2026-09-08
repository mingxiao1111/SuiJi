package com.zhao.suiji.view

import androidx.annotation.DrawableRes
import com.zhao.suiji.R
import com.zhao.suiji.data.ToolbarButton

/**
 * 工具栏按钮（计划 4.2 + M4 用户反馈：扁平线性图标）。
 * iconRes 优先渲染；symbol 供无图标项（字号 A±）使用。
 */
enum class ToolbarAction(
    val id: String,
    val label: String,
    @DrawableRes val iconRes: Int?,
    val symbol: String?,
) {
    NEW_NOTE(ToolbarButton.NEW_NOTE, "新建笔记", R.drawable.ic_t_new, null),
    SWAP_SIDE(ToolbarButton.SWAP_SIDE, "左右换边", R.drawable.ic_bar_swap, null),
    ORDERED_LIST(ToolbarButton.ORDERED_LIST, "有序分点", R.drawable.ic_t_ordered, null),
    UNORDERED_LIST(ToolbarButton.UNORDERED_LIST, "无序分点", R.drawable.ic_t_unordered, null),
    TODO(ToolbarButton.TODO, "待办勾选", R.drawable.ic_t_todo, null),
    INDENT(ToolbarButton.INDENT, "缩进", R.drawable.ic_t_indent, null),
    OUTDENT(ToolbarButton.OUTDENT, "反缩进", R.drawable.ic_t_outdent, null),
    TIMESTAMP(ToolbarButton.TIMESTAMP, "插入时间", R.drawable.ic_t_time, null),
    FONT_MINUS(ToolbarButton.FONT_MINUS, "减小字号", null, "A−"),
    FONT_PLUS(ToolbarButton.FONT_PLUS, "增大字号", null, "A+"),
    HOME(ToolbarButton.HOME, "回到笔记页", R.drawable.ic_t_home, null);

    companion object {
        fun fromId(id: String): ToolbarAction? = entries.firstOrNull { it.id == id }

        /** 按固定顺序渲染显示中的按钮。 */
        fun visibleActions(visibleIds: Set<String>): List<ToolbarAction> =
            entries.filter { it.id in visibleIds }
    }
}
