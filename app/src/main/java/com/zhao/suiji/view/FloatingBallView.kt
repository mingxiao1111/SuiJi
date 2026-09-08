package com.zhao.suiji.view

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import com.zhao.suiji.data.BarTexture
import kotlin.math.abs

/**
 * 贴边竖条（v2.0：唯一收起形态，半把手款已废弃——设计见 V2-设计说明.md §2.2）。
 *
 * 交互：
 * - 单击 / 左右滑动 -> 唤醒悬浮窗
 * - 上下拖动 -> 沿所在边缘调整高度位置（持久化）
 * - 贴边侧别由 [attach] 的 side 参数决定（v2.0 二期镜像系统，左/右通用）
 *
 * 宽度（粗细）与高度（大小）均可设置；颜色 = 原悬浮球主题色。
 * 收起落位：竖条中心 = 长条中心（Service 用 stickCenterY 锚定）。
 */
class FloatingBallView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val touchSlopPx = (10 * density).toInt()

    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var side = com.zhao.suiji.data.Side.LEFT
    private var texture = com.zhao.suiji.data.BarTexture.STANDARD

    // ---- 外观（由设置驱动）----
    private var colorHex = "#2E2E2E"
    private var fadeAlphaValue = DEFAULT_FADE_ALPHA
    private var fixedPosition = false
    private var stickWidthPx = dp(DEFAULT_STICK_WIDTH_DP.toFloat())
    private var stickHeightPx = dp(DEFAULT_STICK_HEIGHT_DP.toFloat())
    private val stickEdgeMarginPx = dp(4f)
    var onStickActivate: (() -> Unit)? = null
    var onPositionSettled: ((xDp: Int, yDp: Int) -> Unit)? = null

    /** 按下轻振动（v2.0-a3 用户拍板：竖条与长条一致的手感；由 Service 接统一的振动开关）。 */
    var onHapticTick: (() -> Unit)? = null

    /** 当前纵向位置（px），展开锚定用；未挂载返回 -1。 */
    val stickY: Int get() = params?.y ?: -1

    /** 竖条中心（px）：v2.0 展开锚定用（长条中心 = 竖条中心）；未挂载返回 -1。 */
    val stickCenterY: Int get() = params?.let { it.y + stickHeightPx / 2 } ?: -1

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 加入 WindowManager：贴指定侧边缘的竖条，[yPx] 为纵向位置。
     *  [slideIn]：动效 A——从屏幕外滑入贴边 + 淡入（M5.8）。
     *  [fadeIn]：a7.1 收起衔接——原地淡入。必须在 addView **之前**把 alpha 压到 0：
     *  挂载后才在外层补设的话，部分合成路径（MuMu 实测）新 Surface 首帧会以终值
     *  透明度先合成，肉眼即"收起后闪一帧弹出"。 */
    fun attach(
        wm: WindowManager,
        screenW: Int,
        screenH: Int,
        yPx: Int,
        slideIn: Boolean = false,
        side: com.zhao.suiji.data.Side = com.zhao.suiji.data.Side.LEFT,
        fadeIn: Boolean = false,
    ) {
        windowManager = wm
        screenWidth = screenW
        screenHeight = screenH
        this.side = side
        params = WindowManager.LayoutParams(
            stickWidthPx,
            stickHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = edgeX()
            y = clampY(yPx)
        }
        renderStick()
        if (slideIn) {
            // 从屏幕外滑入：起点贴所属侧屏外仅 2px（v2.0-a5：行程过长减速尾段观感像"回弹"，
            // 缩短行程+放缓时长，落位更干脆）
            val targetX = edgeX()
            params?.x = if (side.isRight) screenW + 2 else -stickWidthPx - 2
            alpha = 0f
            wm.addView(this, params)
            val p = params
            if (p != null) {
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = SLIDE_IN_ANIM_MS
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener { anim ->
                        val t = anim.animatedValue as Float
                        val start = if (side.isRight) screenW + 2f else -stickWidthPx - 2f
                        p.x = (start + ((targetX - start) * t)).toInt()
                        alpha = fadeAlphaValue * t
                        updateLayout()
                    }
                    start()
                }
            }
        } else if (fadeIn) {
            alpha = 0f
            wm.addView(this, params)
            animate().alpha(fadeAlphaValue)
                .setDuration(FADE_IN_ANIM_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            alpha = fadeAlphaValue
            wm.addView(this, params)
        }
    }

    /** 当前侧别的贴边 x（左：边距；右：屏宽-条宽-边距）。 */
    private fun edgeX(): Int = if (side.isRight) screenWidth - stickWidthPx - stickEdgeMarginPx else stickEdgeMarginPx

    fun detach() {
        runCatching { windowManager?.removeView(this) }
        windowManager = null
        params = null
    }

    /** 横竖屏/分屏切换：更新屏幕尺寸并把竖条拉回可视区（M6 边界处理）。 */
    fun onScreenChanged(screenW: Int, screenH: Int) {
        screenWidth = screenW
        screenHeight = screenH
        params?.let { p ->
            p.x = edgeX()
            p.y = clampY(p.y)
            updateLayout()
        }
    }

    /** 外观刷新入口。[texture] 与长条同源（v2.0 三期：两形态一套质感语言）。 */
    fun applyAppearance(
        colorHex: String,
        fadeAlpha: Float,
        fixedPosition: Boolean,
        stickWidthDp: Int,
        stickHeightDp: Int,
        texture: BarTexture = BarTexture.STANDARD,
    ) {
        this.colorHex = colorHex
        this.fadeAlphaValue = fadeAlpha
        this.fixedPosition = fixedPosition
        this.texture = texture
        this.stickWidthPx = dp(stickWidthDp.toFloat())
        this.stickHeightPx = dp(stickHeightDp.toFloat())
        params?.let { p ->
            p.width = stickWidthPx
            p.height = stickHeightPx
            p.y = clampY(p.y)
            windowManager?.let { wm -> runCatching { wm.updateViewLayout(this, p) } }
        }
        renderStick()
        alpha = fadeAlphaValue
    }

    // ---- 触摸：单击/左右滑唤醒，上下拖调位置 ----

    private var downRawX = 0f
    private var downRawY = 0f
    private var downY = 0
    private var dragging = false
    private var horizontalWake = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val p = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downY = p.y
                dragging = false
                horizontalWake = false
                onHapticTick?.invoke() // 按下即振，与长条手感一致
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
                    dragging = true
                }
                if (dragging && !fixedPosition) {
                    // 横向明显拨动 = 唤醒；纵向拖动 = 沿边缘调高度
                    if (abs(dx) > abs(dy) * 1.5f && abs(dx) > touchSlopPx * 2) {
                        horizontalWake = true
                    } else if (!horizontalWake) {
                        p.y = clampY(downY + dy.toInt())
                        updateLayout()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    onStickActivate?.invoke() // 单击唤醒
                } else if (!fixedPosition) {
                    if (horizontalWake) {
                        onStickActivate?.invoke() // 左右滑唤醒
                    } else {
                        onPositionSettled?.invoke(pxToDp(p.x), pxToDp(p.y))
                    }
                }
                dragging = false
                horizontalWake = false
            }
        }
        return true
    }

    private fun updateLayout() {
        params?.let { p -> windowManager?.let { wm -> runCatching { wm.updateViewLayout(this, p) } } }
    }

    private fun renderStick() {
        val base = runCatching { Color.parseColor(colorHex) }.getOrDefault(Color.parseColor("#2E2E2E"))
        val isLight = Color.luminance(base) > 0.6f
        // v2.0 三期：与长条同一套质感语言——垂直渐变（同 lit/shade 因子）+ 同款描边
        val lit = androidx.core.graphics.ColorUtils.blendARGB(base, Color.WHITE, texture.litFactor)
        val shade = androidx.core.graphics.ColorUtils.blendARGB(base, Color.BLACK, texture.shadeFactor)
        background = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(lit, base, shade)
            cornerRadius = minOf(dp(13f).toFloat(), stickWidthPx / 2f)
            setStroke(1, if (isLight) 0x26212121 else texture.strokeAlpha)
        }
        // 贴边元素不需要强浮起感；4dp 投影在窄条旁像"第二条竖条"造成重影（M6 反馈），降到 2dp
        elevation = dp(2f).toFloat()
        removeAllViews()
    }

    private fun clampY(y: Int): Int =
        y.coerceIn(stickEdgeMarginPx, (screenHeight - stickHeightPx - stickEdgeMarginPx).coerceAtLeast(stickEdgeMarginPx))

    private fun dp(value: Float): Int = (value * density).toInt()

    private fun pxToDp(px: Int): Int = (px / density).toInt()

    companion object {
        const val DEFAULT_FADE_ALPHA = 0.30f
        const val DEFAULT_STICK_WIDTH_DP = 24
        const val DEFAULT_STICK_HEIGHT_DP = 90
        const val STICK_EDGE_MARGIN_DP = 4 // 贴边留白（Service 计算形变落位共用此值）
        private const val SLIDE_IN_ANIM_MS = 200L
        private const val FADE_IN_ANIM_MS = 150L // 收起衔接原地淡入（a7.1）
    }
}
