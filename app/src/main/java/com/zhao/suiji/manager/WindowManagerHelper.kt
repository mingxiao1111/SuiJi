package com.zhao.suiji.manager

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * WindowManager 的 add/update/remove 封装（计划 6）。
 * 用 key 区分各悬浮 View，创建前检查是否已存在，防止重复创建多个悬浮球（计划 11.7）。
 * 所有 remove 都吞掉 "View not attached" 异常——Service 生命周期与窗口操作竞态时的常见崩溃。
 */
class WindowManagerHelper(context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val views = mutableMapOf<String, View>()

    val screenWidth: Int get() = wm.defaultDisplay.width
    val screenHeight: Int get() = wm.defaultDisplay.height

    fun contains(key: String): Boolean = views.containsKey(key)

    fun addView(key: String, view: View, width: Int, height: Int, x: Int, y: Int) {
        if (views.containsKey(key)) return
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        wm.addView(view, params)
        view.tag = params
        views[key] = view
    }

    fun updateView(view: View) {
        val params = view.tag as? WindowManager.LayoutParams ?: return
        runCatching { wm.updateViewLayout(view, params) }
    }

    fun removeView(key: String) {
        views.remove(key)?.let { view ->
            runCatching { wm.removeView(view) }
        }
    }

    fun removeAll() {
        views.keys.toList().forEach { removeView(it) }
    }
}
