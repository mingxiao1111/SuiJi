package com.zhao.suiji.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhao.suiji.R
import com.zhao.suiji.data.Note
import com.zhao.suiji.util.NoteDisplay
import com.zhao.suiji.util.TimeUtils

/**
 * 笔记列表页（v1.1 黑白系：灰底白卡片立体列表 + 时间分组 + 胶囊新建 + 空态引导；
 * v1.2：搜索 + 置顶分组 + 长按操作单）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    notes: List<Note>,
    overlayOn: Boolean,
    onOverlayToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onCreateNote: () -> Unit,
    onDeleteNote: (Long) -> Unit,
    onQueryChange: (String) -> Unit,
    onTogglePin: (id: Long, pinned: Boolean) -> Unit,
) {
    var actionNote by remember { mutableStateOf<Note?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        // 搜索态：顶栏整个变成输入框，避免再弹一层搜索页
                        androidx.compose.material3.OutlinedTextField(
                            value = query,
                            onValueChange = {
                                query = it
                                onQueryChange(query)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索标题和正文", style = MaterialTheme.typography.bodyLarge) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        Text(
                            "浮记",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = {
                            searching = false
                            query = ""
                            onQueryChange("")
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "退出搜索")
                        }
                    } else {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = onOpenTrash) {
                            Icon(Icons.Outlined.Delete, contentDescription = "回收站")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                        TextButton(onClick = onOverlayToggle) {
                            Text(
                                if (overlayOn) "关闭悬浮窗" else "悬浮窗",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            // D1：胶囊新建按钮（品牌色，与悬浮窗胶囊语言同源）
            Button(
                onClick = onCreateNote,
                shape = RoundedCornerShape(28.dp),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("新建")
            }
        },
    ) { padding ->
        val searchingNow = searching && query.isNotBlank()
        if (notes.isEmpty()) {
            EmptyState(
                onCreateNote = onCreateNote,
                modifier = Modifier.padding(padding),
                title = if (searchingNow) "没有匹配的笔记" else null,
                subtitle = if (searchingNow) "换个关键词试试" else null,
            )
        } else {
            GroupedNoteList(
                notes = notes,
                flat = searchingNow, // 搜索结果不分组，扁平直给
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onOpenNote = onOpenNote,
                onLongPress = { actionNote = it },
            )
        }
    }

    actionNote?.let { note ->
        NoteActionDialog(
            note = note,
            onDismiss = { actionNote = null },
            onTogglePin = {
                onTogglePin(note.id, !note.pinned)
                actionNote = null
            },
            onDelete = {
                onDeleteNote(note.id)
                actionNote = null
            },
        )
    }
}

/** 长按操作单（v1.2）：置顶与删除并列，替代原来直接弹删除确认。 */
@Composable
private fun NoteActionDialog(
    note: Note,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    NoteDisplay.deriveTitle(note.title, note.content).ifBlank { "无标题" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                )
                androidx.compose.material3.TextButton(
                    onClick = onTogglePin,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (note.pinned) "取消置顶" else "置顶",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                    )
                }
                androidx.compose.material3.TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "移入回收站",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                    )
                }
                androidx.compose.material3.TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "取消",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                    )
                }
            }
        }
    }
}

// ---- 时间分组列表（B1）----

@Composable
private fun GroupedNoteList(
    notes: List<Note>,
    flat: Boolean,
    modifier: Modifier,
    onOpenNote: (Long) -> Unit,
    onLongPress: (Note) -> Unit,
) {
    val groups = remember(notes, flat) {
        if (flat) {
            // 搜索结果：不分组扁平展示（置顶仍在前，来自 DAO 排序）
            listOf(null to notes)
        } else {
            // 常规态：置顶组在最前，其余按时间分组
            val (pinned, normal) = notes.partition { it.pinned }
            buildList<Pair<String?, List<Note>>> {
                if (pinned.isNotEmpty()) add("置顶" to pinned)
                groupByTimeBucket(normal).forEach { (bucket, list) -> add(bucket.label to list) }
            }
        }
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
    ) {
        groups.forEach { (label, groupNotes) ->
            if (label != null) {
                item(key = "header_$label") {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
                    )
                }
            }
            itemsIndexed(groupNotes, key = { _, n -> n.id }) { _, note ->
                NoteItem(
                    note = note,
                    modifier = Modifier
                        .animateItem()
                        .padding(top = 4.dp, bottom = 4.dp),
                    onClick = { onOpenNote(note.id) },
                    onLongClick = { onLongPress(note) },
                )
            }
        }
    }
}

private fun groupByTimeBucket(notes: List<Note>): List<Pair<TimeUtils.TimeBucket, List<Note>>> {
    val result = linkedMapOf<TimeUtils.TimeBucket, MutableList<Note>>()
    notes.forEach { note ->
        result.getOrPut(TimeUtils.bucketOf(note.updatedAt)) { mutableListOf() }.add(note)
    }
    return result.map { it.key to it.value.toList() }
}

// ---- 条目（v1.0.1：白色卡片浮于灰底之上，圆角 + 发丝描边 + 微阴影 = 立体分界）----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteItem(
    note: Note,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // 深浅模式各配一组卡片色：浅色白卡 / 深色深灰卡，页面底色见 Theme.kt surface
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val cardColor = if (dark) androidx.compose.ui.graphics.Color(0xFF1E1E22) else androidx.compose.ui.graphics.Color.White
    androidx.compose.material3.Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(14.dp),
        color = cardColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = NoteDisplay.deriveTitle(note.title, note.content).ifBlank { "无标题" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (note.pinned) "置顶 · ${TimeUtils.formatRelative(note.updatedAt)}"
                    else TimeUtils.formatRelative(note.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
            }
            val preview = NoteDisplay.previewLine(note.content)
            if (preview.isNotEmpty()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

// ---- 空态（G1：图形 + 文案 + 引导按钮）----

@Composable
private fun EmptyState(
    onCreateNote: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
) {
    val showGuide = title == null
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showGuide) {
                Icon(
                    painter = painterResource(R.drawable.ic_note),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .width(52.dp)
                        .height(52.dp)
                        .padding(bottom = 4.dp),
                )
                Spacer(Modifier.height(14.dp))
            }
            Text(
                title ?: "空空如也",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle ?: "记点什么，随时悬浮在手边",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (showGuide) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onCreateNote,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("写第一条")
                }
            }
        }
    }
}
