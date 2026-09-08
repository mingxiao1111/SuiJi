package com.zhao.suiji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhao.suiji.data.Note
import com.zhao.suiji.util.NoteDisplay
import com.zhao.suiji.util.TimeUtils

/**
 * 回收站（v1.1）：删除的笔记保留 30 天。
 * 点条目弹操作（恢复/彻底删除）；顶栏"清空"一次清掉全部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    trashed: List<Note>,
    onBack: () -> Unit,
    onRestore: (Long) -> Unit,
    onPurge: (Long) -> Unit,
    onEmptyAll: () -> Unit,
) {
    var actionNote by remember { mutableStateOf<Note?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    // 单 Activity 内部导航：系统返回键必须接到 onBack，否则会直接退出应用
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (trashed.isNotEmpty()) {
                        TextButton(onClick = { confirmEmpty = true }) { Text("清空") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        if (trashed.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("回收站是空的", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "删除的笔记会在这里保留 30 天",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
            ) {
                items(trashed, key = { it.id }) { note ->
                    TrashItem(
                        note = note,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        onClick = { actionNote = note },
                    )
                }
            }
        }
    }

    actionNote?.let { note ->
        AlertDialog(
            onDismissRequest = { actionNote = null },
            title = { Text(NoteDisplay.deriveTitle(note.title, note.content)) },
            text = { Text("删除于 ${TimeUtils.formatRelative(note.deletedAt)}") },
            confirmButton = {
                TextButton(onClick = {
                    onRestore(note.id)
                    actionNote = null
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onPurge(note.id)
                    actionNote = null
                }) { Text("彻底删除") }
            },
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("清空回收站") },
            text = { Text("将彻底删除回收站里的 ${trashed.size} 条笔记，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onEmptyAll()
                    confirmEmpty = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TrashItem(note: Note, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // 与主列表同款卡片（灰底白卡），颜色统一走深浅模式判断
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1E1E22) else Color.White
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = cardColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Text(
                text = NoteDisplay.deriveTitle(note.title, note.content).ifBlank { "无标题" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
            Text(
                text = "删除于 ${TimeUtils.formatRelative(note.deletedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}
