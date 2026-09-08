package com.zhao.suiji.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** 悬浮窗（overlay）权限的检查与引导（计划 7.1）。 */
object OverlayPermission {

    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 跳转系统"显示在其他应用上层"授权页，直接定位到本应用。 */
    fun requestIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
}
