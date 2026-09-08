package com.zhao.suiji.view

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.children
import com.zhao.suiji.R
import com.zhao.suiji.data.SettingsRepository
import com.zhao.suiji.data.Side
import com.zhao.suiji.text.NoteTextUtils
import com.zhao.suiji.util.ScreenUtils
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 悬浮笔记窗（v8 / v2.0-a3：一体长条手柄 + 按钮化收起，设计见 V2-设计说明.md）。
 *
 * 交互：
 * - 左侧一体长条手柄（FloatingBarView）：轻点任意处=收起（换位键在工具栏，a3 起不在长条上）、
 *   按住拖动=移窗。
 *   **收起只走轻点与超时自动——拖动/甩动不再收起**（v1.2 手机端误触根因整体删除）
 * - 工具栏并入窗口（上/下位置可设置），风格与卡片一致，按钮显隐/大小可设置
 * - 回车自动续点；空列表项回车删除前缀结束列表
 * - 键盘优先：未进入编辑态时第一次点正文只弹键盘（光标落末尾）；
 *   焦点标志异步生效，延迟重试显式弹出，保证一次点击即弹键盘
 * - 右下角手柄 + 双指捏合缩放（最小 100x80dp / 最大宽避开左右手势区）
 * - 尺寸口径统一：设置里存的 windowWidth/Height 与 onSizeSettled 均为**卡片**尺寸
 *   （v1.2 存总宽、展开时又当卡片宽加外挂区，反复收展会悄悄变宽，此次一并修正）
 */
class FloatingNoteWindowView(context: Context) : FrameLayout(context) {

    // ---- 回调 ----
    /** 轻点长条（v2.0-a3 一体式：唯一手动收起入口；换位键在工具栏）。 */
    var onCollapseRequest: (() -> Unit)? = null

    /** 长条按下轻振动（走 Service 统一的振动设置开关）。 */
    var onHapticTick: (() -> Unit)? = null

    var onContentChanged: ((String) -> Unit)? = null
    var onPositionSettled: ((xDp: Int, yDp: Int) -> Unit)? = null
    var onSizeSettled: ((wDp: Int, hDp: Int) -> Unit)? = null

    /** 工具栏按钮点击（M5.2：工具栏并入窗口）。 */
    var onToolbarAction: ((ToolbarAction) -> Unit)? = null

    /** 任意触摸交互（自动收起计时重置源之一，计划 7.6）。 */
    var onUserInteraction: (() -> Unit)? = null

    // ---- 窗口状态 ----
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var minW = 0
    private var minH = 0
    private var maxW = 0
    private var maxH = 0

    private var fixedPosition = false
    private var windowOpacity = 1.0f
    private var deleted = false

    // v2.0 长条手柄：厚度决定窗口几何（卡片 margin / 窗口总宽），样式细节在 FloatingBarView 内
    private var barThicknessDp = SettingsRepository.DEFAULT_BAR_THICKNESS_DP
    private var windowEdgeMarginPx = ScreenUtils.dpToPx(context, SettingsRepository.DEFAULT_WINDOW_EDGE_MARGIN_DP)

    /** 贴边侧别（v2.0 二期镜像系统）：决定长条/缩放手柄位置、卡片边距、工具栏顺序。 */
    private var side = Side.LEFT

    // IME 避让
    private var imeShift = 0
    private var baseYBeforeShift = 0

    private var lastInputAt = 0L
    val isTyping: Boolean
        get() = editor.hasFocus() && System.currentTimeMillis() - lastInputAt < TYPING_WINDOW_MS

    // ---- 视图 ----
    private val cardBody: FrameLayout
    private val editor: EditText
    private val toolbarTopContainer: LinearLayout
    private val toolbarBottomContainer: LinearLayout
    private val toolbarTop: LinearLayout
    private val toolbarBottom: LinearLayout
    private val dividerTop: View
    private val dividerBottom: View
    private val collapseBar: FloatingBarView
    private val resizeHandle: ImageView

    private val imm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val hitRect = Rect()

    // ---- 工具栏状态（M5.2：并入窗口）----
    private var toolbarActions: List<ToolbarAction> = emptyList() // 当前实际渲染顺序（含右侧镜像）
    private var toolbarActionsOriginal: List<ToolbarAction> = emptyList() // 原始顺序（换边重排用）
    private var toolbarPosition = 0 // 0=上 1=下
    private var toolbarScale = 1f
    private var toolbarOpacity = 1f

    /** fit-to-width 生效后的实际渲染倍率（v2.0-a5：按钮超宽时整体缩小到恰好放下，不滚动）。 */
    private var toolbarEffScale = 1f

    init {
        inflate(context, R.layout.view_floating_note_window, this)
        cardBody = findViewById(R.id.cardBody)
        editor = findViewById(R.id.noteEditor)
        toolbarTopContainer = findViewById(R.id.toolbarTopContainer)
        toolbarBottomContainer = findViewById(R.id.toolbarBottomContainer)
        toolbarTop = findViewById(R.id.toolbarTop)
        toolbarBottom = findViewById(R.id.toolbarBottom)
        dividerTop = findViewById(R.id.dividerTop)
        dividerBottom = findViewById(R.id.dividerBottom)
        collapseBar = findViewById(R.id.barView)
        resizeHandle = findViewById(R.id.resizeHandle)

        setupEditor()
        setupBar()
        setupResizeHandle()
        renderTheme()
    }

    // ---- 窗口挂载 ----

    /** 长条厚度（px）：窗口宽度 = 卡片宽 + 厚度，长条零间隙贴卡。 */
    private fun barWidthPx(): Int = ScreenUtils.dpToPx(context, barThicknessDp)

    private var lastBarWidthPx = 0

    /** 长条厚度变化后同步长条宽、卡片边距与窗口总宽（卡片宽保持不变）。 */
    private fun relayoutForBar() {
        collapseBar.layoutParams?.width = barWidthPx()
        applyMirrorLayout()
        params?.let { p ->
            val prev = if (lastBarWidthPx > 0) lastBarWidthPx else barWidthPx()
            val cardW = p.width - prev
            lastBarWidthPx = barWidthPx()
            p.width = cardW + lastBarWidthPx
            p.x = clampX(p.x)
            updateLayout()
        }
        lastBarWidthPx = barWidthPx()
    }

    /**
     * 按侧别镜像内部布局（v2.0 二期）：长条贴窗左缘 ↔ 右缘、缩放手柄右下 ↔ 左下、
     * 卡片边距随长条侧。展开时由 Service 调 [setSide]，换边动画走 [swapToSide]。
     */
    private fun applyMirrorLayout() {
        (collapseBar.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = if (side.isRight) Gravity.END else Gravity.START
            collapseBar.layoutParams = lp
        }
        (cardBody.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.marginStart = if (side.isRight) 0 else barWidthPx()
            lp.marginEnd = if (side.isRight) barWidthPx() else 0
            cardBody.layoutParams = lp
        }
        (resizeHandle.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = if (side.isRight) Gravity.BOTTOM or Gravity.START else Gravity.BOTTOM or Gravity.END
            lp.marginStart = if (side.isRight) ScreenUtils.dpToPx(context, 8) else 0
            lp.marginEnd = if (side.isRight) 0 else ScreenUtils.dpToPx(context, 8)
            resizeHandle.layoutParams = lp
        }
    }

    /** 应用侧别（无动画，展开接线用）：镜像布局 + 工具栏按侧重排。 */
    fun setSide(side: Side) {
        this.side = side
        applyMirrorLayout()
        applyToolbar(toolbarActionsOriginal, toolbarPosition, toolbarScale, toolbarOpacity)
    }

    /**
     * 换边翻转（工具栏⇄键）：整窗滑到关于屏幕中线对称的位置，同时内部布局镜像。
     * 键盘保持（不动焦点）；动画结束持久化新位置并重算 IME 避让（V2-设计说明 §2.3）。
     */
    fun swapToSide(newSide: Side) {
        val p = params ?: run { side = newSide; return }
        side = newSide
        applyMirrorLayout()
        applyToolbar(toolbarActionsOriginal, toolbarPosition, toolbarScale, toolbarOpacity)
        val startX = p.x
        val endX = screenWidth - p.width - startX // 关于屏幕中线的镜像位置
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWAP_ANIM_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                p.x = (startX + (endX - startX) * t).toInt()
                updateLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    p.x = clampX(endX)
                    updateLayout()
                    onPositionSettled?.invoke(pxToDp(p.x), pxToDp(p.y))
                    if (editor.hasFocus()) postDelayed({ shiftUpForKeyboard() }, IME_SETTLE_DELAY_MS)
                }
            })
            start()
        }
    }

    /**
     * [wPx]/[hPx] 是**卡片**的期望尺寸；窗口宽度额外加上左侧长条厚度，
     * 长条完全在窗口内可触摸且不遮挡正文。
     */
    fun attach(wm: WindowManager, screenW: Int, screenH: Int, xPx: Int, yPx: Int, wPx: Int, hPx: Int) {
        windowManager = wm
        screenWidth = screenW
        screenHeight = screenH
        minW = ScreenUtils.dpToPx(context, MIN_WIDTH_DP)
        minH = ScreenUtils.dpToPx(context, MIN_HEIGHT_DP)
        maxW = maxCardWidthPx()
        maxH = (screenH * MAX_HEIGHT_RATIO).toInt()

        val barW = barWidthPx().also { lastBarWidthPx = it }
        params = WindowManager.LayoutParams(
            wPx.coerceIn(minW, maxW) + barW,
            hPx.coerceIn(minH, maxH),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = clampX(xPx)
            y = clampY(yPx)
        }
        wm.addView(this, params)
    }

    fun detach() {
        stopImePolling() // 收起/销毁兜底停轮询（编辑中直接点长条收起不走 endEdit）
        runCatching { windowManager?.removeView(this) }
        windowManager = null
        params = null
    }

    /** 横竖屏/分屏切换：更新屏幕尺寸并把窗口拉回可视区（M6 边界处理）。 */
    fun onScreenChanged(screenW: Int, screenH: Int) {
        screenWidth = screenW
        screenHeight = screenH
        maxW = maxCardWidthPx()
        maxH = (screenH * MAX_HEIGHT_RATIO).toInt()
        params?.let { p ->
            p.width = p.width.coerceAtMost(maxW + barWidthPx())
            p.height = p.height.coerceAtMost(maxH)
            p.x = clampX(p.x)
            p.y = clampY(p.y)
            updateLayout()
        }
    }

    /** 窗口尺寸变化（缩放/屏变）→ 工具栏按新宽度重算 fit（去抖，拖动缩放不逐帧重建）。 */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (toolbarActionsOriginal.isNotEmpty()) {
            removeCallbacks(refitToolbar)
            postDelayed(refitToolbar, 120)
        }
    }

    private val refitToolbar = Runnable {
        applyToolbar(toolbarActionsOriginal, toolbarPosition, toolbarScale, toolbarOpacity)
    }

    private fun updateLayout() {
        params?.let { p ->
            windowManager?.let { wm -> runCatching { wm.updateViewLayout(this, p) } }
        }
    }

    val windowX: Int get() = params?.x ?: 0
    val windowY: Int get() = params?.y ?: 0
    val windowBottom: Int get() = params?.let { it.y + it.height } ?: 0

    /** 收起落位用：长条与窗同高，竖条中心 = 长条中心（V2-设计说明 §2.2）。 */
    val windowCenterY: Int get() = params?.let { it.y + it.height / 2 } ?: 0

    // ---- 内容 ----

    fun setNoteContent(content: String) {
        if (editor.text.toString() != content) editor.setText(content)
    }

    fun getNoteContent(): String = editor.text.toString()

    /** 当前笔记被删除：切空白提示态不崩溃（计划 4.5）。 */
    fun showDeletedState() {
        deleted = true
        editor.setText("")
        editor.hint = "笔记已被删除"
        editor.isEnabled = false
    }

    /** 重新绑定笔记（工具栏新建 / 轮播切换）时恢复可编辑。 */
    fun restoreEditable() {
        if (!deleted) return
        deleted = false
        editor.isEnabled = true
        editor.hint = "输入内容…"
    }

    /** 工具栏文本操作入口：无焦点时按当前 selection 直接插入，不弹键盘（计划 4.2）。 */
    fun applyTextAction(compute: (text: String, selection: Int) -> NoteTextUtils.TextEdit?) {
        if (deleted) return
        val sel = if (editor.hasFocus()) editor.selectionStart else editor.selectionEnd
        val edit = compute(editor.text.toString(), sel.coerceAtLeast(0)) ?: return
        val text = editor.editableText
        text.replace(edit.start, edit.end, edit.insert)
        editor.setSelection(edit.newSelection.coerceIn(0, text.length))
    }

    // ---- 外观 ----

    fun applyFontSize(sp: Int) {
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
    }

    fun applyWindowOpacity(opacity: Float) {
        windowOpacity = opacity
        renderTheme()
    }

    /** 长条完整样式（v2.0-a6：颜色/透明度/渐变沿用原把手值，厚度/长度/圆角/质感是新设置）。 */
    fun applyBarStyle(
        colorHex: String,
        alpha: Float,
        gradient: Float,
        thicknessDp: Int,
        lengthRatioPct: Int,
        cornerDp: Int,
        texture: com.zhao.suiji.data.BarTexture,
    ) {
        barThicknessDp = thicknessDp
        // 无条件重排：attach 时字段默认值可能已等于设置值（thicknessChanged=false），
        // 但 XML 里的初始 width/marginStart 还是写死的旧值，跳过会留下 18dp 旧布局（a3 实测踩坑）
        relayoutForBar()
        collapseBar.applyStyle(colorHex, alpha, gradient, lengthRatioPct, cornerDp, texture)
    }

    /** 窗口距屏幕左右边缘的最小边距（M6 万物可调）。 */
    fun applyWindowEdgeMargin(marginDp: Int) {
        windowEdgeMarginPx = ScreenUtils.dpToPx(context, marginDp)
        params?.let { p ->
            p.x = clampX(p.x)
            updateLayout()
        }
    }

    fun setShowResizeHandle(show: Boolean) {
        resizeHandle.visibility = if (show) VISIBLE else GONE
    }

    fun setFixedPosition(fixed: Boolean) {
        fixedPosition = fixed
    }

    /**
     * 工具栏（M5.2：并入窗口）：渲染到上/下位置，全关按钮则整行隐藏。
     * 由 Service 订阅设置后调用；主题变化时内部重建刷新配色。
     * [opacity]：工具栏整体透明度（设置页可调）。
     */
    fun applyToolbar(actions: List<ToolbarAction>, position: Int, scale: Float, opacity: Float) {
        // 右侧模式下按钮排列整体镜像（V2-设计说明 §2.3），原始顺序另存供换边重排
        val ordered = if (side.isRight) actions.reversed() else actions
        // fit-to-width（v2.0-a5 用户拍板）：按钮总宽超过卡片可用宽时整体缩小到恰好放下，
        // 窗口大小变化时按钮跟着放大缩小（onSizeChanged 去抖后重排），永不出现半个按钮
        val avail = cardBody.width
        val buttonW = ScreenUtils.dpToPx(context, 42) * scale // 图标 22dp + 左右内边距各 10dp
        val fitScale = if (avail > 0 && ordered.isNotEmpty() && buttonW > 0) {
            (avail / (ordered.size * buttonW)).coerceAtMost(1f)
        } else {
            1f
        }
        val effScale = scale * fitScale

        val posChanged = position != toolbarPosition
        val scaleChanged = abs(scale - toolbarScale) > 0.01f
        val fitChanged = abs(effScale - toolbarEffScale) > 0.01f
        val opacityChanged = abs(opacity - toolbarOpacity) > 0.01f
        val actionsChanged = ordered.map { it.id } != toolbarActions.map { it.id }
        toolbarPosition = position
        toolbarScale = scale
        toolbarEffScale = effScale
        toolbarOpacity = opacity
        toolbarActions = ordered
        toolbarActionsOriginal = actions

        val showTop = position == TOOLBAR_TOP && ordered.isNotEmpty()
        val showBottom = position == TOOLBAR_BOTTOM && ordered.isNotEmpty()
        toolbarTopContainer.visibility = if (showTop) VISIBLE else GONE
        toolbarBottomContainer.visibility = if (showBottom) VISIBLE else GONE
        toolbarTopContainer.alpha = opacity
        toolbarBottomContainer.alpha = opacity
        if (!showTop && !showBottom) return

        if (posChanged || scaleChanged || fitChanged || opacityChanged || actionsChanged) {
            val target = if (showTop) toolbarTop else toolbarBottom
            val other = if (showTop) toolbarBottom else toolbarTop
            other.removeAllViews()
            target.removeAllViews()
            ordered.forEach { action -> target.addView(buildToolbarButton(action)) }
        }
        // 按钮配色跟随主题
        val active = if (showTop) toolbarTop else toolbarBottom
        active.children.forEach { child -> tintToolbarChild(child) }
    }

    private fun buildToolbarButton(action: ToolbarAction): View {
        val hPad = (ScreenUtils.dpToPx(context, 10) * toolbarEffScale).toInt()
        val vPad = (ScreenUtils.dpToPx(context, 7) * toolbarEffScale).toInt()
        val button: View = if (action.iconRes != null) {
            ImageView(context).apply {
                // 布局尺寸 = 图标 + 内边距，否则 padding 会把内容区挤到几 dp（图标特别小的根因）
                val iconSize = (ScreenUtils.dpToPx(context, 22) * toolbarEffScale).toInt()
                layoutParams = MarginLayoutParams(iconSize + hPad * 2, iconSize + vPad * 2)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageResource(action.iconRes)
            }
        } else {
            TextView(context).apply {
                text = action.symbol
                textSize = 14f * toolbarEffScale
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }.apply {
            setPadding(hPad, vPad, hPad, vPad)
            setOnClickListener { onToolbarAction?.invoke(action) }
            setOnLongClickListener {
                Toast.makeText(context, action.label, Toast.LENGTH_SHORT).show()
                true
            }
        }
        tintToolbarChild(button)
        return button
    }

    /** 按钮图标/文字颜色跟随主题（浅色窗深色按钮、深色窗白色按钮）。 */
    private fun tintToolbarChild(child: View) {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val tint = if (night) Color.WHITE else Color.parseColor("#3C3C3E")
        when (child) {
            is ImageView -> child.setColorFilter(tint)
            is TextView -> child.setTextColor(tint)
        }
    }

    private fun renderTheme() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val (bg, mainText, subText, divider) = if (night) {
            listOf(Color.parseColor("#2A2A2E"), Color.WHITE, 0xB3FFFFFF.toInt(), 0x33FFFFFF.toInt())
        } else {
            listOf(Color.parseColor("#FFFFFF"), Color.parseColor("#212121"), Color.parseColor("#8A8A8A"), 0x1F212121)
        }
        val alpha = (255 * windowOpacity).toInt().coerceIn(0, 255)
        cardBody.background = GradientDrawable().apply {
            cornerRadius = ScreenUtils.dpToPx(context, 16).toFloat()
            setColor(Color.argb(alpha, Color.red(bg), Color.green(bg), Color.blue(bg)))
            setStroke(1, 0x2EFFFFFF)
        }
        // 立体感的核心：真实投影让卡片"浮"在屏幕上（M5.9）。
        // 缩放手柄必须抬到比卡片更高的 Z 层，否则会被卡片投影盖住（M6 修复消失 bug）
        cardBody.elevation = ScreenUtils.dpToPx(context, 6).toFloat()
        resizeHandle.elevation = ScreenUtils.dpToPx(context, 8).toFloat()
        editor.setTextColor(mainText)
        editor.setHintTextColor(subText)
        resizeHandle.setColorFilter(subText)
        dividerTop.setBackgroundColor(divider)
        dividerBottom.setBackgroundColor(divider)
        toolbarTop.children.forEach { tintToolbarChild(it) }
        toolbarBottom.children.forEach { tintToolbarChild(it) }
    }

    // ---- 事件入口：键盘优先 / 双指缩放 ----
    // 长按正文 = 系统默认文字选择菜单（M5.6：轮播切换已按用户要求移除）

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            onUserInteraction?.invoke()
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN ->
                if (ev.pointerCount == 2) {
                    pinching = true
                    pinchStartDist = spacing(ev)
                    // 缩放基准取卡片尺寸（窗口总宽含长条厚度，口径与 onSizeSettled 一致）
                    params?.let { pinchStartW = it.width - barWidthPx(); pinchStartH = it.height }
                    return true
                }

            MotionEvent.ACTION_MOVE ->
                if (pinching && ev.pointerCount >= 2) {
                    val scale = spacing(ev) / pinchStartDist.coerceAtLeast(1f)
                    resizeTo((pinchStartW * scale).toInt(), (pinchStartH * scale).toInt())
                    return true
                }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (pinching) {
                    pinching = false
                    params?.let { pp ->
                        onSizeSettled?.invoke(pxToDp(pp.width - barWidthPx()), pxToDp(pp.height))
                    }
                    return true
                }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 键盘优先（M5.3 绿线框修复）：未进入编辑态时点正文，拦截在 onInterceptTouchEvent
     * 并消费在 onTouchEvent——不再劫持 dispatchTouchEvent，避免 IME 误判悬浮窗为
     * TalkBack 焦点目标而绘制绿色边界框。
     */
    private var keyboardFirst = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 键盘优先只针对正文；工具栏/缩放手柄是即时按钮，第一击必须直达
                // （v2.0-a4 修：此前工具栏未排除，未聚焦时第一击只弹键盘不触发按钮）
                keyboardFirst = !deleted && !editor.hasFocus() &&
                    !inChild(collapseBar, ev.x, ev.y) && !inChild(resizeHandle, ev.x, ev.y) &&
                    !inChild(toolbarTopContainer, ev.x, ev.y) &&
                    !inChild(toolbarBottomContainer, ev.x, ev.y)
                return keyboardFirst
            }

            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (keyboardFirst) return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (keyboardFirst) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginEdit()
                    editor.setSelection(editor.text.length)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> keyboardFirst = false
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    /**
     * 回车自动续点（M4 用户反馈）：上一行有 分点/待办 前缀 ->
     * 新行自动带下一个前缀；上一行是空列表项（只有前缀）-> 删掉它结束列表。
     * 用户删掉前缀后上一行不再有前缀，自然恢复正常输入。
     */
    private fun autoContinueList(text: Editable, newlineAt: Int) {
        if (newlineAt > text.length) return
        val lineStart = if (newlineAt == 0) 0 else text.lastIndexOf('\n', newlineAt - 1) + 1
        if (lineStart > newlineAt) return
        val prevLine = text.substring(lineStart, newlineAt)

        val olMatch = Regex("^(\\d+)([.、])").find(prevLine)
        val circledNum = prevLine.firstOrNull()?.code
            ?.takeIf { it in 0x2460..0x2473 }?.minus(0x2460)?.plus(1)
        val ulSymbol = listOf("• ", "- ", "· ").firstOrNull { prevLine.startsWith(it.trimEnd()) }
        val todoSymbol = when {
            prevLine.startsWith(NoteTextUtils.TODO_DONE) -> NoteTextUtils.TODO_DONE
            prevLine.startsWith(NoteTextUtils.TODO_TODO) -> NoteTextUtils.TODO_TODO
            else -> null
        }

        fun endList() = text.delete(lineStart, newlineAt + 1)
        fun continueWith(prefix: String) {
            text.insert(newlineAt + 1, prefix)
            editor.setSelection(newlineAt + 1 + prefix.length)
        }

        when {
            olMatch != null -> {
                val num = olMatch.groupValues[1].toInt()
                val sep = olMatch.groupValues[2]
                if (prevLine.length <= olMatch.value.length + 1) {
                    endList()
                } else {
                    continueWith("${num + 1}$sep ")
                }
            }

            circledNum != null -> {
                val symbol = String(Character.toChars(0x2460 + circledNum - 1))
                if (prevLine == symbol || prevLine == "$symbol ") {
                    endList()
                } else {
                    continueWith(NoteTextUtils.orderedPrefix(circledNum + 1, 2))
                }
            }

            ulSymbol != null -> {
                if (prevLine.length <= ulSymbol.trimEnd().length + 1) {
                    endList()
                } else {
                    continueWith(ulSymbol)
                }
            }

            todoSymbol != null -> {
                if (prevLine.length <= todoSymbol.length + 1) {
                    endList()
                } else {
                    continueWith("${NoteTextUtils.TODO_TODO} ")
                }
            }
        }
    }

    // ---- 焦点策略（计划 7.3 + M5.2 键盘一次弹出）----

    private fun setupEditor() {
        // 长按 = 自定义文本工具菜单（系统选择菜单在 overlay 窗口上不显示，平台限制）
        editor.setOnLongClickListener {
            showTextTools()
            true
        }
        // 选中文字（双击/拖选）时系统的选择工具栏同样不显示——接管 ActionMode，
        // 一进选中就弹自制菜单；返回 true 保留选区（空菜单的系统工具栏在 overlay 上本就渲染不出来）
        editor.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
                showTextTools()
                return true
            }

            override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = false
            override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?) = true
            override fun onDestroyActionMode(mode: android.view.ActionMode?) = Unit
        }
        editor.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) endEdit() }
        editor.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                endEdit()
                true
            } else {
                false
            }
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                lastInputAt = System.currentTimeMillis()
                onContentChanged?.invoke(s?.toString().orEmpty())
                if (count == 1 && s?.getOrNull(start) == '\n' && !deleted) {
                    val snapshot = start
                    post { autoContinueList(editor.editableText, snapshot) }
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    /**
     * 点击正文：移除不获焦标志 -> 延迟 120ms 重新 requestFocus 并**显式**弹出键盘。
     * 窗口 focusable 标志由 WindowManager 异步生效，立即弹会被系统忽略（M5.2 修复"点两次"）。
     */
    /**
     * 自定义文本工具菜单（M6）：系统选择菜单（复制/全选/粘贴）在 overlay 窗口上
     * 无法显示（平台限制），长按时弹出自己的 PopupMenu 替代。
     */
    private fun showTextTools() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val pm = android.widget.PopupMenu(context, editor)
        val selStart = editor.selectionStart
        val selEnd = editor.selectionEnd
        val hasSelection = selEnd > selStart
        if (!hasSelection) pm.menu.add("全选")
        if (hasSelection) pm.menu.add("剪切")
        if (hasSelection) pm.menu.add("复制")
        if (cm.hasPrimaryClip()) pm.menu.add("粘贴")
        if (pm.menu.size() == 0) {
            Toast.makeText(context, "无可用操作", Toast.LENGTH_SHORT).show()
            return
        }
        pm.setOnMenuItemClickListener { item ->
            when (item.title) {
                "全选" -> editor.selectAll()
                "复制" -> {
                    val sel = editor.text.substring(selStart, selEnd)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("note", sel))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }

                "剪切" -> {
                    val sel = editor.text.substring(selStart, selEnd)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("note", sel))
                    editor.text.delete(selStart, selEnd)
                    Toast.makeText(context, "已剪切", Toast.LENGTH_SHORT).show()
                }

                "粘贴" -> {
                    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context) ?: return@setOnMenuItemClickListener true
                    if (hasSelection) {
                        editor.text.replace(selStart, selEnd, text)
                        editor.setSelection(selStart + text.length)
                    } else {
                        editor.text.insert(selStart, text)
                        editor.setSelection(selStart + text.length)
                    }
                }
            }
            true
        }
        pm.show()
    }

    private fun beginEdit() {
        if (deleted) return
        val p = params ?: return
        if (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) return
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        updateLayout()
        editor.requestFocus()
        imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        // 窗口 focusable 标志异步生效，延迟重试一次显式弹出（不用 requestFocusFromTouch，
        // 部分 ROM 会给触摸焦点目标画焦点指示框——绿线框防御第二轮）
        postDelayed({
            if (!editor.hasFocus()) editor.requestFocus()
            imm.showSoftInput(editor, 0)
        }, KEYBOARD_RETRY_DELAY_MS)
        // 真机键盘弹出时机/动画时长差异大，单次检查会错过（v1.2 真机键盘上浮失效的疑似根因），
        // 追加两次重算；recheckImeShift 同时处理"键盘已消失但窗口还停在上浮位"的回落
        // （输入法自己吃掉返回键等路径不走 endEdit，v2.0-a6 修）
        postDelayed({ recheckImeShift() }, IME_SETTLE_DELAY_MS)
        postDelayed({ if (editor.hasFocus()) recheckImeShift() }, IME_RETRY2_DELAY_MS)
        postDelayed({ if (editor.hasFocus()) recheckImeShift() }, IME_RETRY3_DELAY_MS)
        startImePolling() // 焦点期轮询兜底：慢 ROM/键盘切换场景全程跟随（a7.2）
    }

    /**
     * 重算 IME 避让（a7.5）：**键盘显示状态**（imeShowing，与窗口位置无关）门控——
     * 显示中→按需让位（shiftUp 内部只在窗底仍被挡时动作，已让开则静止）；
     * 不显示→回落。回落通道被"键盘确实收了"锁死，键盘弹着期间窗只可能单调抬到位
     * 后静止，结构上无震荡。
     */
    private fun recheckImeShift() {
        if (imeShowing()) {
            shiftUpForKeyboard()
        } else if (imeShift != 0) {
            restoreImeShift()
        }
    }

    /**
     * 键盘是否处于显示状态（a7.5 防震荡核心）。**必须与窗口位置无关**：
     * insets 高度语义是"键盘与本窗的交集"——窗抬到位后交集归零，若用它判"键盘没了"
     * 会回落，回落后又重叠再上浮，每 350ms 上下反复跳（a7.4 真机"一直闪"的根因）。
     * isVisible 是键盘窗口的全局显示状态，让位不影响它；低版本回退差值信号。
     */
    private fun imeShowing(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            rootWindowInsets?.let {
                if (it.isVisible(android.view.WindowInsets.Type.ime())) return true
            }
        }
        return imeVisibleHeight() > 0
    }

    /**
     * 键盘当前可见高度（px），不可见返回 0。双信号（a7.2）：
     * ①API 30+ insets 直读（对 overlay 是与窗的交集高度，窗与键盘无重叠时为 0）；
     * ②回退可视区域差值（部分国产 ROM 对 overlay 恒返回全屏，差值恒 0）。
     */
    private fun imeVisibleHeight(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val byInsets = rootWindowInsets
                ?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
            if (byInsets > 0) return byInsets
        }
        val rect = Rect()
        getWindowVisibleDisplayFrame(rect)
        val byFrame = screenHeight - rect.bottom
        return if (byFrame > screenHeight * 0.15) byFrame else 0
    }

    private fun endEdit() {
        val p = params ?: return
        stopImePolling()
        restoreImeShift()
        imm.hideSoftInputFromWindow(editor.windowToken, 0)
        editor.clearFocus()
        if (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            updateLayout()
        }
    }

    /** overlay 窗口不参与 IME insets 协商，手动避让（计划 7.3）。
     *  交集驱动增量让位（a7.5）：只关心"窗底此刻被键盘挡住多少"，被挡就让开
     *  （含 8dp 间隙），没被挡就**一动不动**。键盘弹出动画的中间态、键盘变高
     *  （候选栏展开）都会在后续轮询中以增量方式补齐，自然收敛且无震荡。 */
    private fun shiftUpForKeyboard() {
        val p = params ?: return
        if (!imeShowing()) return
        val intrusion: Int = if (Build.VERSION.SDK_INT >= 30) {
            // insets 的 ime 高度=键盘与本窗的交集=窗底被挡深度（窗让开后为 0，正好是"无需再动"）
            rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
        } else {
            val h = imeVisibleHeight()
            if (h > 0) (p.y + p.height - (screenHeight - h)).coerceAtLeast(0) else 0
        }
        val gap = ScreenUtils.dpToPx(context, IME_CLEAR_GAP_DP)
        if (intrusion > gap) {
            val move = intrusion - gap
            if (imeShift == 0) baseYBeforeShift = p.y
            imeShift += move
            val ny = clampY(p.y - move)
            if (ny != p.y) {
                p.y = ny
                updateLayout()
            }
        }
    }

    private fun restoreImeShift() {
        val p = params ?: return
        if (imeShift != 0) {
            imeShift = 0
            p.y = clampY(baseYBeforeShift)
            updateLayout()
        }
    }

    // 焦点期低频轮询（a7.2）：键盘弹出时机/显隐变化无法靠事件感知（overlay 无 insets 回调），
    // 固定三次重试对慢 ROM 仍可能错过；编辑期间每 350ms 重算一次即可全程跟随，
    // recheckImeShift 幂等，无键盘时零成本。endEdit/detach 停止。
    private var imePollRunnable: Runnable? = null

    private fun startImePolling() {
        stopImePolling()
        val r: Runnable = object : Runnable {
            override fun run() {
                if (deleted || !editor.hasFocus()) return
                recheckImeShift()
                postDelayed(this, IME_POLL_MS)
            }
        }
        imePollRunnable = r
        postDelayed(r, IME_POLL_MS)
    }

    private fun stopImePolling() {
        imePollRunnable?.let { removeCallbacks(it) }
        imePollRunnable = null
    }

    // ---- 三段长条：三段皆可拖动移窗 / 上段换位 / 下段收起（v2.0）----

    private var barDownRawX = 0f
    private var barDownRawY = 0f
    private var barDownWinX = 0
    private var barDownWinY = 0
    private var draggingWindow = false

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBar() {
        collapseBar.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            if (morphing) return@setOnTouchListener true // 形变中吞掉触摸，避免半态被拖动
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    barDownRawX = event.rawX
                    barDownRawY = event.rawY
                    barDownWinX = p.x
                    barDownWinY = p.y
                    draggingWindow = false
                    // 按压反馈（沿用旧把手果冻感）：厚度压扁、轻微加深，松手过冲回弹；
                    // 半透明在视图 alpha 上，按压/回弹都要以基础透明度为基准取相对值
                    collapseBar.animate().scaleX(0.94f).scaleY(0.99f).alpha(0.85f * collapseBar.barAlpha)
                        .setDuration(90)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                    onHapticTick?.invoke()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!draggingWindow && !fixedPosition &&
                        (abs(event.rawX - barDownRawX) > DRAG_SLOP_PX ||
                            abs(event.rawY - barDownRawY) > DRAG_SLOP_PX)
                    ) {
                        draggingWindow = true
                        if (editor.hasFocus()) endEdit()
                    }
                    if (draggingWindow && !fixedPosition) {
                        p.x = clampX(barDownWinX + (event.rawX - barDownRawX).toInt())
                        p.y = clampY(barDownWinY + (event.rawY - barDownRawY).toInt())
                        updateLayout()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (draggingWindow) {
                        draggingWindow = false
                        // 拖动结束窗还活着：过冲回弹（Overshoot），"果冻感"的关键
                        collapseBar.animate().scaleX(1f).scaleY(1f).alpha(collapseBar.barAlpha)
                            .setDuration(240)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.6f))
                            .start()
                        // 拖动只移窗、永不收起（v2.0 拍板：手势歧义源头整体删除）
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            onPositionSettled?.invoke(pxToDp(p.x), pxToDp(p.y))
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        // 轻点收起：窗即将整体淡出，长条**不做回弹**——回弹的 Overshoot
                        // 过冲（放大到 ~1.06）会与整窗淡出并行 ~120ms，肉眼即"收起闪一下
                        // 弹出"（a7.2 真机反复确认的闪帧根因，与竖条挂载无关）。
                        // 直接取消子视图动画并静默复位变换。
                        collapseBar.animate().cancel()
                        collapseBar.scaleX = 1f
                        collapseBar.scaleY = 1f
                        collapseBar.alpha = collapseBar.barAlpha
                        onCollapseRequest?.invoke()
                    } else {
                        // 非拖动的 CANCEL（如被系统打断）：窗还在，正常回弹
                        collapseBar.animate().scaleX(1f).scaleY(1f).alpha(collapseBar.barAlpha)
                            .setDuration(240)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.6f))
                            .start()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 收起动画（v2.0-a7 极简化）：整窗朝所在侧轻移 + 淡出。单一表面、纯 alpha/translation、
     * 无子视图动画、无缩放、**与竖条永无时空重叠**（a6~a6.2 三版形变的重影皆源于
     * 两个半透明体在不同位置同时可见：缩放重采样、滑动交叠都中招，同位不重叠才是根治）。
     * 结束回调里由 Service 拆窗并在原位同步挂竖条淡入。约 180ms。
     */
    fun fadeCollapse(onEnd: () -> Unit) {
        val dir = if (side.isRight) 1f else -1f
        morphing = true
        animate().alpha(0f).translationX(dir * 40f)
            .setDuration(COLLAPSE_ANIM_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator()) // 离场加速，走得干脆
            .withEndAction {
                // a7.3 修"收起后整块窗+长条闪现一帧"（真机 100% 复现）：alpha 淡出只是
                // 给合成打折，窗的绘制底稿始终是完整亮度的卡片+长条；拆窗瞬间部分
                // 合成器会按默认状态把底稿再投一帧。拆窗前先标记不绘制（底稿清空），
                // 等两帧确认空底稿已进合成管线再交给 onEnd 拆窗——重播也只能播到空画面。
                // 不复位 alpha/位移：视图随即被 detach，复位反而可能闪一帧全显。
                visibility = INVISIBLE
                postDelayed({
                    morphing = false
                    onEnd()
                }, EMPTY_FRAME_WAIT_MS)
            }
            .start()
    }

    /**
     * 展开动画（收起反向，极简）：整窗从所在侧轻移进场 + 淡入。竖条此刻已拆——
     * 长条淡入的位置就是竖条刚在的位置，同位衔接天然像"竖条长成窗口"，且不会出现两条影子。
     */
    fun fadeInExpand() {
        val dir = if (side.isRight) 1f else -1f
        morphing = true
        alpha = 0f
        translationX = dir * 40f
        animate().alpha(1f).translationX(0f)
            .setDuration(EXPAND_ANIM_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator()) // 入场减速，浮现更顺（a7.1 丝滑）
            .withEndAction { morphing = false }
            .start()
    }

    /** 形变进行中标志：期间忽略触摸，避免半态被拖动。 */
    var morphing = false
        private set

    // ---- 右下角手柄缩放 ----

    private var pinching = false
    private var pinchStartDist = 0f
    private var pinchStartW = 0
    private var pinchStartH = 0

    private fun spacing(ev: MotionEvent): Float {
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private var handleResizeDownX = 0f
    private var handleResizeDownY = 0f
    private var handleStartW = 0
    private var handleStartH = 0

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResizeHandle() {
        resizeHandle.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handleResizeDownX = event.rawX
                    handleResizeDownY = event.rawY
                    handleStartW = p.width - barWidthPx() // 卡片口径
                    handleStartH = p.height
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // 右侧模式手柄在左下角，x 轴语义取反：向左拖=变宽（右缘被 resizeTo 钉住）
                    val dx = (event.rawX - handleResizeDownX).toInt()
                    val effDx = if (side.isRight) -dx else dx
                    resizeTo(
                        handleStartW + effDx,
                        handleStartH + (event.rawY - handleResizeDownY).toInt(),
                    )
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onSizeSettled?.invoke(pxToDp(p.width - barWidthPx()), pxToDp(p.height))
                    true
                }

                else -> false
            }
        }
    }

    private fun resizeTo(cardW: Int, cardH: Int) {
        val p = params ?: return
        val oldRight = p.x + p.width
        p.width = cardW.coerceIn(minW, maxW) + barWidthPx()
        p.height = cardH.coerceIn(minH, maxH)
        // 右侧模式以右缘为锚：缩放手柄在左下角，拖动时右缘钉住、往左收/放
        // （v2.0-a5 修：此前 x 不变，右缘跟着宽度一起动，与拖拽方向相反）
        p.x = if (side.isRight) clampX(oldRight - p.width) else clampX(p.x)
        p.y = clampY(p.y)
        updateLayout()
    }

    // ---- 坐标 ----

    private fun inChild(child: View, x: Float, y: Float): Boolean {
        child.getHitRect(hitRect)
        return hitRect.contains(x.toInt(), y.toInt())
    }

    /** 窗口主体至少留 24dp 在屏幕内即可（M4 反馈：不再过度限制）。 */
    /** 左右最小边距可设置（M6 万物可调），把手/卡片不会贴死或移出屏幕。 */
    /** 左右最小边距可设置（M6 万物可调）。v2.0-a6.1 起按侧钳制：**长条所在缘必须留在屏内**
     *  ——右模式钳右缘（x ≤ 屏宽-边距-窗宽，修"右侧长条可被拖出屏幕"），
     *  左模式钳左缘（右缘可探出屏幕，v1 语义不变）。 */
    private fun clampX(x: Int): Int {
        val w = params?.width ?: 0
        return if (side.isRight) {
            x.coerceIn(windowEdgeMarginPx, screenWidth - windowEdgeMarginPx - w)
        } else {
            x.coerceIn(windowEdgeMarginPx, screenWidth - windowEdgeMarginPx)
        }
    }

    private fun clampY(y: Int): Int =
        y.coerceIn(0, (screenHeight - 64).coerceAtLeast(0))

    /** 最大卡片宽：避开左右系统手势区（各 24dp），防止缩放手柄被返回手势覆盖（M5.2）。 */
    private fun maxCardWidthPx(): Int {
        val gestureSafe = screenWidth - ScreenUtils.dpToPx(context, GESTURE_MARGIN_DP * 2) -
            barWidthPx()
        return min((screenWidth * MAX_WIDTH_RATIO).toInt(), gestureSafe)
    }

    private fun pxToDp(px: Int): Int = ScreenUtils.pxToDp(context, px)

    companion object {
        // M5.2：支持更扁更小
        const val MIN_WIDTH_DP = 100
        const val MIN_HEIGHT_DP = 80
        const val MAX_HEIGHT_RATIO = 0.9
        const val MAX_WIDTH_RATIO = 0.9
        private const val GESTURE_MARGIN_DP = 12 // M6：从 24 缩短一半（用户反馈右边缘距离过大）
        private const val DRAG_SLOP_PX = 10
        private const val IME_SETTLE_DELAY_MS = 250L
        private const val IME_POLL_MS = 350L // 焦点期 IME 状态轮询间隔（a7.2）
        private const val IME_CLEAR_GAP_DP = 8 // 上浮后窗底与键盘顶的间隙（a7.4）
        private const val IME_RETRY2_DELAY_MS = 700L // v2.0：真机键盘弹出慢，多次重算避让
        private const val IME_RETRY3_DELAY_MS = 1400L
        private const val KEYBOARD_RETRY_DELAY_MS = 120L
        private const val TYPING_WINDOW_MS = 700L
        private const val SWAP_ANIM_MS = 250L // 换边翻转动画（v2.0 二期）
        private const val COLLAPSE_ANIM_MS = 180L // 收起整窗轻移淡出（v2.0-a7 极简）
        private const val EXPAND_ANIM_MS = 200L // 展开稍长配减速插值器，浮现更顺（a7.1）
        private const val EMPTY_FRAME_WAIT_MS = 32L // 拆窗前等空底稿合成两帧（a7.3 防残影闪现）
        const val TOOLBAR_TOP = 0
        const val TOOLBAR_BOTTOM = 1
    }
}
