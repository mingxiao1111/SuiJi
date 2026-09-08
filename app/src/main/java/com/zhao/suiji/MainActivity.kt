package com.zhao.suiji

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhao.suiji.service.FloatingNoteService
import com.zhao.suiji.ui.FloatNoteVMFactory
import com.zhao.suiji.ui.NoteEditScreen
import com.zhao.suiji.ui.NoteEditViewModel
import com.zhao.suiji.ui.NoteListScreen
import com.zhao.suiji.ui.NoteListViewModel
import com.zhao.suiji.ui.TrashScreen
import com.zhao.suiji.ui.theme.FloatNoteTheme
import com.zhao.suiji.util.OverlayPermission
import kotlinx.coroutines.launch

/** 单 Activity 内部导航：列表 / 编辑 / 回收站三个屏，不引入 navigation 依赖。 */
private sealed interface Screen {
    data object List : Screen
    data object Trash : Screen
    data class Edit(val noteId: Long, val isNew: Boolean) : Screen
}

class MainActivity : ComponentActivity() {

    private val app get() = application as FloatNoteApp

    // 悬浮窗权限与服务状态：onResume 复查（计划 7.1）
    private var overlayGranted by mutableStateOf(false)
    private var serviceOn by mutableStateOf(false)
    private var showOverlayDialog by mutableStateOf(false)

    // Android 13+ 通知权限：被拒不阻塞服务启动，仅通知不可见（计划 11.2），结果无需处理
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayGranted = OverlayPermission.isGranted(this)
        serviceOn = FloatingNoteService.isRunning
        setContent {
            FloatNoteTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.List) }

                // F1：列表 <-> 编辑 转场（编辑页从右滑入，返回滑出，形成空间关系）
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        if (targetState is Screen.Edit) {
                            (slideInHorizontally { it } + fadeIn()) togetherWith
                                (slideOutHorizontally { -it / 4 } + fadeOut())
                        } else {
                            (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                                (slideOutHorizontally { it } + fadeOut())
                        }
                    },
                    label = "screen",
                ) { current ->
                    when (current) {
                        Screen.List -> {
                            val vm: NoteListViewModel = viewModel(factory = FloatNoteVMFactory)
                            val notes by vm.notes.collectAsStateWithLifecycle()
                            NoteListScreen(
                                notes = notes,
                                overlayOn = serviceOn,
                                onOverlayToggle = ::toggleFloatingService,
                                onOpenSettings = {
                                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                },
                                onOpenTrash = { screen = Screen.Trash },
                                onOpenNote = { screen = Screen.Edit(it, isNew = false) },
                                onCreateNote = {
                                    lifecycleScope.launch {
                                        val id = app.noteRepository.createNote()
                                        screen = Screen.Edit(id, isNew = true)
                                    }
                                },
                                onDeleteNote = vm::trashNote,
                                onQueryChange = vm::setQuery,
                                onTogglePin = vm::togglePin,
                            )
                        }

                        Screen.Trash -> {
                            val vm: NoteListViewModel = viewModel(factory = FloatNoteVMFactory)
                            val trashed by vm.trashed.collectAsStateWithLifecycle()
                            TrashScreen(
                                trashed = trashed,
                                onBack = { screen = Screen.List },
                                onRestore = vm::restoreNote,
                                onPurge = vm::purgeNote,
                                onEmptyAll = vm::emptyTrash,
                            )
                        }

                        is Screen.Edit -> {
                            val vm: NoteEditViewModel = viewModel(
                                key = "edit",
                                factory = FloatNoteVMFactory,
                            )
                            NoteEditScreen(
                                viewModel = vm,
                                noteId = current.noteId,
                                isNew = current.isNew,
                                onBack = { screen = Screen.List },
                            )
                        }
                    }
                }

                if (showOverlayDialog) {
                    OverlayPermissionDialog(
                        onGrant = {
                            showOverlayDialog = false
                            startActivity(OverlayPermission.requestIntent(this))
                        },
                        onDismiss = { showOverlayDialog = false },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = OverlayPermission.isGranted(this)
        serviceOn = FloatingNoteService.isRunning
    }

    /** 顶栏「悬浮窗」按钮：未授权先引导授权，已授权则开 / 关服务（计划 F6）。 */
    private fun toggleFloatingService() {
        if (!overlayGranted) {
            showOverlayDialog = true
            return
        }
        if (serviceOn) {
            stopService(Intent(this, FloatingNoteService::class.java))
            serviceOn = false
        } else {
            requestNotificationPermissionIfNeeded()
            startForegroundService(Intent(this, FloatingNoteService::class.java))
            serviceOn = true
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun OverlayPermissionDialog(
    onGrant: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要悬浮窗权限") },
        text = {
            Text(
                "悬浮窗需要「显示在其他应用上层」权限，才能在任意界面上显示。\n\n" +
                    "部分国产系统还需额外设置：\n" +
                    "· 小米/红米：设置 → 应用管理 → 浮记 → 其他权限 → 开启「显示悬浮窗」和「后台弹出界面」；" +
                    "省电策略选「无限制」\n" +
                    "· 华为/荣耀：设置 → 应用 → 浮记 → 显示在其他应用上层开启；" +
                    "电池启动管理改为「手动管理」并允许后台活动\n" +
                    "· vivo/OPPO：类似路径，开启悬浮窗与后台弹出权限",
            )
        },
        confirmButton = { TextButton(onClick = onGrant) { Text("去授权") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
