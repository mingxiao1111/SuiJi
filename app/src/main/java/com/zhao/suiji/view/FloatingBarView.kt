package com.zhao.suiji.view

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import com.zhao.suiji.R
import com.zhao.suiji.data.BarTexture
import com.zhao.suiji.util.ScreenUtils

/**
 * 一体式长条手柄（v2.0-a3，设计见 V2-设计说明.md §2.1）：一整条、不分段、无分界线——
 * 轻点任意处 = 收起、按住拖动 = 移窗（触摸由 FloatingNoteWindowView 统一接管）。
 * 换位键已挪到工具栏（a2 的两段式因"破坏一体感"废弃）。
 *
 * 视觉（用户反馈三轮迭代后的定稿方向）：
 * - 可见高度只占窗高一部分（默认 75%），居中缩短、上下留白；触摸热区仍为全窗高；
 * - 圆角可设置（默认 10dp，渲染时 clamp 到半厚防胶囊）；
 * - 渐变为**垂直方向**（顶受光->底背光）——a2 的对角渐变在厚条上会产生竖向色带
 *   （厚度 28 时中间现"竖线"），垂直渐变左右均匀、立体感保留；
 * - 中央一个 ◄ 收起箭头图标提示"点这收起"。
 *
 * 本视图自身高度仍为窗高（match_parent），真实可见范围由
 * [contentTop]/[contentBottom] 圈定，背景与图标全部自绘/手摆。
 */
class FloatingBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null, // XML inflate 必需（窗口布局里以标签引用本类）
) : FrameLayout(context, attrs) {

    companion object {
        private const val ICON_SIZE_DP = 14
        private const val STROKE_DP = 1
    }

    private var colorHex = "#2E2E2E"
    private var alphaValue = 1f
    private var gradientStrength = 1f
    private var lengthRatio = 0.75f // 可见长条高度 / 视图高度
    private var cornerDp = 13
    private var texture = BarTexture.STANDARD

    private val collapseIcon = ImageView(context) // ◄ 收起提示

    /** 可见长条范围（px），背景绘制、图标摆位共用一份。 */
    private var contentTop = 0
    private var contentBottom = 0

    private val barDrawable = GradientDrawable()
    private val outlineRect = Rect()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false) // ViewGroup 默认不走 onDraw，自绘背景必须关掉
        val side = ScreenUtils.dpToPx(context, ICON_SIZE_DP)
        collapseIcon.apply {
            setImageResource(R.drawable.ic_bar_collapse)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = false
            isFocusable = false
        }
        addView(collapseIcon, LayoutParams(side, side, Gravity.CENTER))
        // 投影随内容区（缩进后的真实长条），而不是整个触摸热区
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                if (contentBottom > contentTop && width > 0) {
                    outlineRect.set(0, contentTop, width, contentBottom)
                    outline.setRoundRect(outlineRect, cornerPx())
                }
            }
        }
    }

    /** 样式入口（Service 订阅设置后调用）。 */
    fun applyStyle(
        colorHex: String,
        alpha: Float,
        gradient: Float,
        lengthRatioPct: Int,
        cornerDp: Int,
        texture: BarTexture,
    ) {
        this.colorHex = colorHex
        this.alphaValue = alpha
        this.gradientStrength = gradient
        this.lengthRatio = (lengthRatioPct / 100f).coerceIn(0.5f, 0.95f)
        this.cornerDp = cornerDp
        this.texture = texture
        computeZones()
        renderBar()
    }

    /** 当前基础透明度（按压动画据此计算相对值，勿让回弹把半透明条弹成不透明）。 */
    val barAlpha: Float get() = alphaValue

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeZones()
        renderBar()
    }

    /** 圈定可见长条范围：居中缩短。 */
    private fun computeZones() {
        val h = height
        if (h == 0) return
        val contentH = (h * lengthRatio).toInt()
        contentTop = (h - contentH) / 2
        contentBottom = contentTop + contentH
        requestLayout() // 图标摆位依赖内容区
        invalidate()
        invalidateOutline()
    }

    /** 玻璃感渐变黑：垂直受光->背光 + 1dp 高光描边；质感三预设由设置驱动（三期）。
     *  半透明必须走**视图整体 alpha**（不透明绘制）：逐像素 ARGB 半透明在模拟器合成路径
     *  上会出现内部竖向浅带（2026-09-01 实测消元定位：图标/描边/渐变/软件层均无关，
     *  唯透明度=1 时消失；换表面 alpha 后消失）。 */
    private fun renderBar() {
        val base = runCatching { Color.parseColor(colorHex) }.getOrDefault(Color.parseColor("#2E2E2E"))
        val isLight = Color.luminance(base) > 0.6f
        val g = gradientStrength.coerceIn(0f, 1f)
        val lit = ColorUtils.blendARGB(base, Color.WHITE, texture.litFactor * g)
        val shade = ColorUtils.blendARGB(base, Color.BLACK, texture.shadeFactor * g)

        barDrawable.let { d ->
            // 垂直渐变：对角渐变的横向色带在厚条中央可见（"竖线"bug），垂直方向左右均匀
            d.orientation = GradientDrawable.Orientation.TOP_BOTTOM
            if (g > 0.01f) d.colors = intArrayOf(lit, base, shade)
            else d.colors = intArrayOf(base, base)
            d.cornerRadius = cornerPx()
            d.setStroke(
                ScreenUtils.dpToPx(context, STROKE_DP),
                if (isLight) 0x26212121 else texture.strokeAlpha,
            )
        }
        alpha = alphaValue // 半透明走视图级（表面 alpha）
        collapseIcon.setColorFilter(if (isLight) Color.parseColor("#33333333") else Color.WHITE)
        elevation = ScreenUtils.dpToPx(context, 8).toFloat()
    }

    private fun cornerPx(): Float = minOf(
        ScreenUtils.dpToPx(context, cornerDp).toFloat(),
        (layoutParams?.width ?: 0).takeIf { it > 0 }?.div(2f) ?: Float.MAX_VALUE,
    )

    /** 图标手摆：居长条可见区中心（不用 LinearLayout 是因为内容区是动态计算的）。 */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutIcon(collapseIcon, (contentTop + contentBottom) / 2)
    }

    private fun layoutIcon(icon: ImageView, centerY: Int) {
        val side = ScreenUtils.dpToPx(context, ICON_SIZE_DP)
        val cx = width / 2
        icon.measure(
            View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
        )
        icon.layout(cx - side / 2, centerY - side / 2, cx + side / 2, centerY + side / 2)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (contentBottom <= contentTop || width == 0) return
        barDrawable.setBounds(0, contentTop, width, contentBottom)
        barDrawable.draw(canvas)
    }
}
