package com.zhao.suiji.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhao.suiji.util.TimeUtils

/**
 * 编辑页（M4 反馈：主流笔记应用的极简风）。
 * 大标题无框输入 + 正文无框输入，底部保存状态；返回 / 系统返回键都走保存路径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(
    viewModel: NoteEditViewModel,
    noteId: Long,
    isNew: Boolean,
    onBack: () -> Unit,
) {
    // 系统返回键与顶栏返回走同一条保存路径
    BackHandler { viewModel.exit(onDone = onBack) }

    // 进入编辑页时加载笔记；load 内部对同 id 已加载的情况跳过，旋转重建不丢输入
    LaunchedEffect(noteId) { viewModel.load(noteId, isNew) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    val fontSize by viewModel.fontSizeSp.collectAsStateWithLifecycle()

    // 极简无框输入：透明指示器与容器
    val plainColors = TextFieldDefaults.colors(
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = { viewModel.exit(onDone = onBack) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除笔记")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (!viewModel.loaded) {
            // 加载完成前不渲染输入框，避免旧内容闪现
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {}
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            TextField(
                value = viewModel.title,
                onValueChange = viewModel::onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("标题", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.outline)
                },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                colors = plainColors,
            )
            // E1：元信息行（时间 · 字数），充实顶栏下方的空白
            Text(
                text = "${TimeUtils.formatMeta(viewModel.noteUpdatedAt)} · " +
                    "${viewModel.title.length + viewModel.content.length} 字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            TextField(
                value = viewModel.content,
                onValueChange = viewModel::onContentChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                placeholder = {
                    Text("开始输入…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                },
                textStyle = TextStyle(
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.65).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                colors = plainColors,
            )
            SaveStatusBar(viewModel.saveStatus)
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除笔记") },
            text = { Text("删除后无法恢复，确定删除？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete(onDone = onBack)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SaveStatusBar(status: SaveStatus) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.Start,
    ) {
        val text = when (status) {
            SaveStatus.Idle -> ""
            SaveStatus.Typing -> "输入中…"
            SaveStatus.Saving -> "保存中…"
            is SaveStatus.Saved -> "已保存 ${TimeUtils.formatTime(status.at)}"
        }
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
