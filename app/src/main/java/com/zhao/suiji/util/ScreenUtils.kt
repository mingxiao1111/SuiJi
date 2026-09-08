package com.zhao.suiji.util

import android.content.Context

/**
 * dp / px 换算与屏幕尺寸。
 * 设置层统一存 dp（换设备尺寸一致，计划 11.12），WindowManager 层统一用 px，
 * 所有换算必须经过这里，禁止散落手写 density 乘法。
 */
object ScreenUtils {

    fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    fun pxToDp(context: Context, px: Int): Int =
        (px / context.resources.displayMetrics.density).toInt()

    fun screenWidthPx(context: Context): Int = context.resources.displayMetrics.widthPixels

    fun screenHeightPx(context: Context): Int = context.resources.displayMetrics.heightPixels

    fun clamp(value: Int, min: Int, max: Int): Int = value.coerceIn(min, max)
}
