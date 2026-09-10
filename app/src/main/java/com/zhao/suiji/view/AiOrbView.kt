package com.zhao.suiji.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.zhao.suiji.R

/**
 * AI 圆钮（v2.1 三态入口）：常驻左下角的半透明圆 + 原子图标 + 生成中呼吸点。
 * 窗口自持（同 FloatingBallView 模式）；点击 [onActivate]，透明度由 [applyAlpha] 驱动。
 */
class AiOrbView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var bgAlpha = 0.35f
    private var breathAnim: ValueAnimator? = null
    private val breathDot = android.view.View(context)

    var onActivate: (() -> Unit)? = null

    init {
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_ai_atom)
            layoutParams = LayoutParams(dp(20), dp(20), Gravity.CENTER)
        })
        breathDot.apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            layoutParams = LayoutParams(dp(9), dp(9), Gravity.TOP or Gravity.END).apply {
                topMargin = 1; marginEnd = 1
            }
            visibility = GONE
        }
        addView(breathDot)
        elevation = dp(4).toFloat()
        setOnClickListener { onActivate?.invoke() }
    }

    fun attach(wm: WindowManager, screenW: Int, screenH: Int) {
        windowManager = wm
        params = WindowManager.LayoutParams(
            dp(SIZE_DP), dp(SIZE_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(MARGIN_DP)
            y = dp(MARGIN_DP)
        }
        render()
        alpha = 0f
        runCatching { wm.addView(this, params) }
        animate().alpha(1f).setDuration(150L).start()
    }

    fun detach() {
        breathAnim?.cancel()
        breathAnim = null
        runCatching { windowManager?.removeView(this) }
        windowManager = null
        params = null
    }

    /** 圆钮背景不透明度（0.15~0.9；图标保持清晰，只调底色）。 */
    fun applyAlpha(alpha: Float) {
        bgAlpha = alpha.coerceIn(0.15f, 0.9f)
        render()
    }

    /** 后台生成中：呼吸点提示（仅收起态可见时调用）。 */
    fun setGenerating(on: Boolean) {
        breathAnim?.cancel()
        breathAnim = null
        breathDot.visibility = if (on) VISIBLE else GONE
        if (on) {
            breathAnim = ValueAnimator.ofFloat(0.25f, 1f).apply {
                duration = 800L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { breathDot.alpha = it.animatedValue as Float }
                start()
            }
        }
    }

    private fun render() {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb((bgAlpha * 255).toInt(), 0x1C, 0x1C, 0x1E))
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    companion object {
        const val SIZE_DP = 46
        private const val MARGIN_DP = 16
    }
}
