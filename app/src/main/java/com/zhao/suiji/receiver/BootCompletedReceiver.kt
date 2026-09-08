package com.zhao.suiji.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhao.suiji.FloatNoteApp
import com.zhao.suiji.service.FloatingNoteService
import com.zhao.suiji.util.OverlayPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启（v1.2）：设置里打开"开机自启悬浮窗"后，开机直接拉起悬浮服务，
 * 不需要先手动打开 App。两个前置条件缺一不可：
 * ① 开关打开（DataStore 读取是挂起的，用 goAsync 保住接收器生命周期）；
 * ② 悬浮窗权限仍被授权（被撤销时静默不启动，避免服务起来又自杀）。
 * 注意：被系统"强制停止"过的应用收不到 BOOT_COMPLETED（平台限制，勿当 bug 修）。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as FloatNoteApp
                if (app.settingsRepository.bootAutoStart.first() && OverlayPermission.isGranted(context)) {
                    context.startForegroundService(Intent(context, FloatingNoteService::class.java))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
