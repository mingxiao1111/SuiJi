package com.zhao.suiji.view

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * AI 组件共用样式（v2.1 苹果扁平语言）：纯白/深灰面板、发丝描边、iOS 灰气泡。
 * 与笔记窗的质感体系（渐变玻璃）刻意区分——两套组件两套语言。
 */
internal object AiStyle {

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun surface(night: Boolean): Int = if (night) 0xFF2C2C2E.toInt() else Color.WHITE
    fun stroke(night: Boolean): Int = if (night) 0x14EBEBF5 else 0x0F000000
    fun textPrimary(night: Boolean): Int = if (night) 0xFFEBEBF5.toInt() else 0xFF1D1D1F.toInt()
    fun textHint(night: Boolean): Int = if (night) 0xFF98989E.toInt() else 0xFF8E8E93.toInt()
    fun textSecondary(night: Boolean): Int = if (night) 0xFF98989E.toInt() else 0xFF86868B.toInt()
    fun userBubble(night: Boolean): Int = if (night) 0xFF3A3A3C.toInt() else 0xFFE9E9EB.toInt()
    fun accent(night: Boolean): Int = if (night) 0xFFEBEBF5.toInt() else 0xFF1D1D1F.toInt()
    fun error(night: Boolean): Int = if (night) 0xFFFF8A80.toInt() else 0xFFD93A2F.toInt()

    fun roundBg(color: Int, cornerDp: Float, density: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = cornerDp * density
            strokeColor?.let { setStroke(1, it) }
        }
}
