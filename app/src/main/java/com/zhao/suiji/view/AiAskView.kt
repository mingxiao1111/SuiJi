package com.zhao.suiji.view

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.zhao.suiji.R
import kotlin.math.abs
import kotlin.math.min

/**
 * AI 极简输入框（三态之二）：胶囊输入栏 + 单排建议气泡。
 * [attach] 传 [anchor]（悬浮窗卡片矩形）时原位锚定——输入框出现在悬浮窗的位置，过渡不跳变（a5）；
 * 不传则退回底部居中。发送走 [onSend]；建议气泡走 [onChip]（id 常量见 companion）；
 * ＋ 菜单承接"插入当前笔记"（T4，图片 T5 再加）。
 * 键盘避让：轮询 visibleDisplayFrame 把窗口抬到键盘上方（悬浮窗成熟做法的简化版）。
 */
class AiAskView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var anchored = false // 锚定态（跟随悬浮窗位置）
    private var baseY = 0 // 锚定态静止 y（键盘抬升基准）
    private val handler = Handler(Looper.getMainLooper())
    private val night = AiStyle.isNight(context)

    val inputField: EditText
    private var sendEnabled = true
    private var noteChipsEnabled = false
    private var noteAttachAvailable = false
    private var chipOrganize: TextView? = null
    private var chipSummarize: TextView? = null
    private var menuRow: LinearLayout? = null
    private var attachRow: TextView? = null
    private var imageRow: LinearLayout? = null
    private var imageThumb: ImageView? = null
    private var clipPanel: LinearLayout? = null
    private var clipList: LinearLayout? = null
    private var clipEmpty: TextView? = null
    private var clipHistoryItems: List<String> = emptyList()

    var onSend: ((String) -> Unit)? = null
    var onChip: ((String) -> Unit)? = null
    var onOutside: (() -> Unit)? = null
    var onAttachNote: (() -> Unit)? = null
    var onDetachNote: (() -> Unit)? = null
    var onPickImage: (() -> Unit)? = null
    var onRemoveImage: (() -> Unit)? = null

    /** 剪贴板采集（a7-1）：读到的内容交给持有方入列，返回最新历史用于渲染。 */
    var onClipCaptured: ((String) -> List<String>)? = null

    /** 把手拖动结束：窗口中心交给持有方更新锚点（位置接力）。 */
    var onMoved: ((Int, Int) -> Unit)? = null

    /** 思考开关点击（T6）：翻转由持有方持久化。 */
    var onToggleThink: (() -> Unit)? = null
    private var thinkBtn: FrameLayout? = null

    // 把手拖动状态（复用悬浮窗长条手柄的交互模式）
    private var gripDownRawX = 0f
    private var gripDownRawY = 0f
    private var gripDownX = 0
    private var gripDownY = 0
    private var draggingAsk = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 胶囊输入栏
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AiStyle.roundBg(AiStyle.surface(night), 25f, density, AiStyle.stroke(night))
            setPadding(dp(14), dp(8), dp(8), dp(8))
            elevation = dp(6).toFloat()
        }
        inputField = EditText(context).apply {
            background = null
            hint = "输入你的问题…"
            setHintTextColor(AiStyle.textHint(night))
            setTextColor(AiStyle.textPrimary(night))
            textSize = 15f
            maxLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
        pill.addView(gripHandle())
        pill.addView(plusButton())
        pill.addView(inputField)
        pill.addView(thinkButton())
        pill.addView(sendButton {})

        // 剪贴板选择面板（a7-1：GONE，点剪贴板按钮展开，自采历史）
        clipList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        clipEmpty = TextView(context).apply {
            text = "剪贴板为空。在别的应用复制文字后，回到 AI 输入框会自动记录。"
            textSize = 12f
            setTextColor(AiStyle.textHint(night))
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        clipPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = GONE
            background = AiStyle.roundBg(AiStyle.surface(night), 16f, density, AiStyle.stroke(night))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(6).toFloat()
            addView(clipList!!)
            addView(clipEmpty!!)
        }
        content.addView(
            clipPanel!!,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) },
        )

        // ＋菜单（GONE，点＋展开）：插入当前笔记（T4）/ 插入图片（T5）
        menuRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = GONE
            addView(menuChip("插入当前笔记") { if (noteAttachAvailable) onAttachNote?.invoke() })
            addView(
                menuChip("插入图片") { onPickImage?.invoke() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(7) },
            )
        }
        content.addView(
            menuRow!!,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) },
        )

        // 已附当前笔记指示（可点 ✕ 移除）
        attachRow = TextView(context).apply {
            text = "已附当前笔记  ✕"
            textSize = 12f
            setTextColor(AiStyle.textSecondary(night))
            background = AiStyle.roundBg(if (night) 0x1FEBEBF5 else 0x14787880, 999f, density)
            setPadding(dp(11), dp(6), dp(11), dp(6))
            visibility = GONE
            setOnClickListener { onDetachNote?.invoke() }
        }
        content.addView(
            attachRow!!,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) },
        )

        // 已附图片指示（缩略图 + ✕ 移除，T5）
        imageRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
            addView(
                ImageView(context).apply {
                    imageThumb = this
                    layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
                    background = AiStyle.roundBg(AiStyle.stroke(night), 10f, density)
                    clipToOutline = true
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                },
            )
            addView(
                TextView(context).apply {
                    text = "✕"
                    textSize = 14f
                    setTextColor(AiStyle.textSecondary(night))
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener { onRemoveImage?.invoke() }
                },
            )
        }
        content.addView(
            imageRow!!,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) },
        )
        content.addView(
            pill,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        // 单排建议气泡（横向可滑：锚定窄卡时不满排）
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            CHIP_ORGANIZE to "整理这篇笔记",
            CHIP_EXPLAIN to "解释",
            CHIP_TRANSLATE to "翻译",
            CHIP_SUMMARIZE to "总结",
        ).forEach { (id, label) ->
            val chip = TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(AiStyle.textPrimary(night))
                background = AiStyle.roundBg(AiStyle.surface(night), 999f, density, AiStyle.stroke(night))
                setPadding(dp(12), dp(7), dp(12), dp(7))
                elevation = dp(3).toFloat()
                setOnClickListener {
                    val needsNote = id == CHIP_ORGANIZE || id == CHIP_SUMMARIZE
                    if (!needsNote || noteChipsEnabled) onChip?.invoke(id)
                }
            }
            when (id) {
                CHIP_ORGANIZE -> chipOrganize = chip
                CHIP_SUMMARIZE -> chipSummarize = chip
            }
            chips.addView(
                chip,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(7) },
            )
        }
        val chipsScroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                chips,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        // 建议气泡行：左滑区 + 最右常驻粘贴板按钮（a6-2，快速访问剪贴板）
        val chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipsRow.addView(
            chipsScroll,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
        )
        chipsRow.addView(
            clipboardButton(),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(7) },
        )
        content.addView(
            chipsRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) },
        )
        addView(
            content,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
        )
    }

    fun attach(wm: WindowManager, screenW: Int, screenH: Int, anchor: Rect? = null) {
        windowManager = wm
        screenWidth = screenW
        screenHeight = screenH
        val wPx = if (anchor != null) {
            anchor.width().coerceIn(dp(MIN_WIDTH_DP), (screenW * 0.88f).toInt())
        } else {
            (screenW * 0.88f).toInt()
        }
        val lp = WindowManager.LayoutParams(
            wPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        )
        if (anchor != null) {
            // 原位锚定（a5）：输入框落在悬浮窗卡片的位置，中心对齐
            anchored = true
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = (anchor.centerX() - wPx / 2)
                .coerceIn(dp(EDGE_DP), (screenW - wPx - dp(EDGE_DP)).coerceAtLeast(dp(EDGE_DP)))
            baseY = (anchor.centerY() - dp(45)).coerceIn(dp(EDGE_DP), (screenH - dp(140)).coerceAtLeast(dp(EDGE_DP)))
            lp.y = baseY
        } else {
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // BOTTOM 锚定时 y 正值往屏幕外推，留边必须用负值（真机踩坑：+24dp 整窗沉底只露 6px）
            lp.y = -dp(BASE_BOTTOM_MARGIN_DP)
        }
        params = lp
        alpha = 0f
        translationY = dp(20).toFloat()
        runCatching { wm.addView(this, lp) }
        if (anchored) {
            // 布局后精确定心（WRAP_CONTENT 高度此刻才可知）
            post {
                val h = height.coerceAtLeast(1)
                baseY = (anchor!!.centerY() - h / 2)
                    .coerceIn(dp(EDGE_DP), (screenHeight - h - dp(EDGE_DP)).coerceAtLeast(dp(EDGE_DP)))
                params?.y = baseY
                updateLayout()
            }
        }
        animate().alpha(1f).translationY(0f).setDuration(200L).start()
        inputField.requestFocus()
        handler.postDelayed({ showIme() }, 150L)
        // 持焦后采集当前剪贴板（a7-1 自采历史的时机之一）
        handler.postDelayed({ if (windowManager != null) readClipboardNow() }, 350L)
        handler.postDelayed(imeCheck, 400L)
    }

    fun detach() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        }
        runCatching { windowManager?.removeView(this) }
        windowManager = null
        params = null
    }

    fun setSendEnabled(enabled: Boolean) {
        sendEnabled = enabled
        (getChildAt(0) as? LinearLayout)?.let { /* 预留：生成中态由面板承载 */ }
    }

    /** 整理这篇/总结依赖笔记内容：无笔记置灰（T3）。附笔记菜单可用性同源。 */
    fun setNoteChipsEnabled(enabled: Boolean) {
        noteChipsEnabled = enabled
        noteAttachAvailable = enabled
        val alpha = if (enabled) 1f else 0.4f
        chipOrganize?.alpha = alpha
        chipSummarize?.alpha = alpha
    }

    /** ＋ 菜单"插入当前笔记"生效后显示指示行（✕ 移除）。 */
    fun setNoteAttached(on: Boolean) {
        attachRow?.visibility = if (on) VISIBLE else GONE
        if (on) menuRow?.visibility = GONE
    }

    /** ＋ 菜单"插入图片"选图完成（T5）：显示缩略图 chip；null = 移除。 */
    fun setImageAttached(path: String?) {
        if (path == null) {
            imageRow?.visibility = GONE
            return
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(path)
        if (bmp == null) {
            imageRow?.visibility = GONE
            return
        }
        imageThumb?.setImageBitmap(bmp)
        imageRow?.visibility = VISIBLE
        menuRow?.visibility = GONE
    }

    /** ＋ 菜单按钮样式（与建议气泡同语言，略小）。 */
    private fun menuChip(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 12f
        setTextColor(AiStyle.textPrimary(night))
        background = AiStyle.roundBg(AiStyle.surface(night), 999f, density, AiStyle.stroke(night))
        setPadding(dp(12), dp(7), dp(12), dp(7))
        elevation = dp(3).toFloat()
        setOnClickListener { onClick() }
    }

    private fun toggleMenu() {
        clipPanel?.visibility = GONE
        menuRow?.let { it.visibility = if (it.visibility == VISIBLE) GONE else VISIBLE }
    }

    /** 点输入框外区域收起（FLAG_WATCH_OUTSIDE_TOUCH 的 ACTION_OUTSIDE）。 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) onOutside?.invoke()
        return super.onTouchEvent(event)
    }

    /** ＋ 附件菜单入口（T4：插入当前笔记；T5 图片）。 */
    private fun plusButton(): FrameLayout = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) }
        background = AiStyle.roundBg(if (night) 0x1FEBEBF5 else 0x14787880, 999f, density)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_ai_plus)
            setColorFilter(AiStyle.textSecondary(night))
            layoutParams = LayoutParams(dp(12), dp(12), Gravity.CENTER)
        })
        setOnClickListener { toggleMenu() }
    }

    /** 剪贴板速贴（a7-1）：chip 行最右，与建议气泡同尺寸；点开选择面板选一条填入。 */
    private fun clipboardButton(): FrameLayout = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        background = AiStyle.roundBg(AiStyle.surface(night), 999f, density, AiStyle.stroke(night))
        setPadding(dp(12), dp(7), dp(12), dp(7))
        elevation = dp(3).toFloat()
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_ai_clipboard)
            setColorFilter(AiStyle.textSecondary(night))
            layoutParams = LayoutParams(dp(15), dp(15), Gravity.CENTER)
        })
        setOnClickListener { toggleClipPanel() }
    }

    private fun toggleClipPanel() {
        val panel = clipPanel ?: return
        if (panel.visibility == VISIBLE) {
            panel.visibility = GONE
            return
        }
        clipHistoryItems = readClipboardNow() // 打开时实时采集一次
        renderClipList()
        menuRow?.visibility = GONE
        panel.visibility = VISIBLE
    }

    /** 读当前剪贴板（悬浮窗持焦=合法读取方），交给持有方入列并取回最新历史。 */
    private fun readClipboardNow(): List<String> {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            ?: return clipHistoryItems
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            onClipCaptured?.let { clipHistoryItems = it(text) }
        }
        return clipHistoryItems
    }

    private fun renderClipList() {
        val list = clipList ?: return
        list.removeAllViews()
        clipEmpty?.visibility = if (clipHistoryItems.isEmpty()) VISIBLE else GONE
        clipHistoryItems.take(6).forEach { text ->
            list.addView(
                TextView(context).apply {
                    this.text = text
                    textSize = 13.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(AiStyle.textPrimary(night))
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    setOnClickListener {
                        inputField.setText(text)
                        inputField.setSelection(text.length)
                        inputField.requestFocus()
                        clipPanel?.visibility = GONE
                    }
                },
            )
        }
    }

    /** Service 喂数历史（面板开着时同步刷新）。 */
    fun setClipHistory(items: List<String>) {
        clipHistoryItems = items
        if (clipPanel?.visibility == VISIBLE) renderClipList()
    }

    /** 胶囊左端把手（a7-2）：复用悬浮窗长条手柄的按住即拖交互，移动整个输入框。 */
    private fun gripHandle(): FrameLayout = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.MATCH_PARENT)
            .apply { marginEnd = dp(6) }
        addView(
            android.view.View(context).apply {
                background = AiStyle.roundBg(if (night) 0x33EBEBF5 else 0x29787880, 999f, density)
                layoutParams = LayoutParams(dp(5), dp(22), Gravity.CENTER)
            },
        )
        setOnTouchListener { _, event -> handleGripDrag(event) }
    }

    private fun handleGripDrag(event: MotionEvent): Boolean {
        val p = params ?: return false
        if (!anchored) return false // 底部居中兜底形态不提供拖动
        val slop = 10 * density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gripDownRawX = event.rawX
                gripDownRawY = event.rawY
                gripDownX = p.x
                gripDownY = p.y
                draggingAsk = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!draggingAsk &&
                    (abs(event.rawX - gripDownRawX) > slop || abs(event.rawY - gripDownRawY) > slop)
                ) {
                    draggingAsk = true
                    hideIme()
                    menuRow?.visibility = GONE
                    clipPanel?.visibility = GONE
                }
                if (draggingAsk) {
                    p.x = (gripDownX + (event.rawX - gripDownRawX).toInt())
                        .coerceIn(dp(EDGE_DP), (screenWidth - p.width - dp(EDGE_DP)).coerceAtLeast(dp(EDGE_DP)))
                    p.y = (gripDownY + (event.rawY - gripDownRawY).toInt())
                        .coerceIn(dp(EDGE_DP), (screenHeight - p.height - dp(EDGE_DP)).coerceAtLeast(dp(EDGE_DP)))
                    baseY = p.y // 键盘避让基准同步，否则 350ms 轮询会弹回原位
                    updateLayout()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingAsk && event.actionMasked == MotionEvent.ACTION_UP) {
                    onMoved?.invoke(p.x + p.width / 2, p.y + p.height / 2)
                }
                draggingAsk = false
                return true
            }
        }
        return false
    }

    private fun hideIme() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    /** 思考开关（T6）：输入框发送旁。开=强调色实底白图标，关=灰图标。未配思考模型由持有方隐藏。 */
    private fun thinkButton(): FrameLayout = FrameLayout(context).apply {
        thinkBtn = this
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(8) }
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_ai_think)
                layoutParams = LayoutParams(dp(14), dp(14), Gravity.CENTER)
            },
        )
        setOnClickListener { onToggleThink?.invoke() }
    }

    /** [show] 未配思考模型隐藏；[on] 开启高亮。 */
    fun setThinkToggle(show: Boolean, on: Boolean) {
        thinkBtn?.visibility = if (show) VISIBLE else GONE
        thinkBtn?.background = if (on) AiStyle.roundBg(AiStyle.accent(night), 999f, density) else null
        (thinkBtn?.getChildAt(0) as? ImageView)?.setColorFilter(
            if (on) android.graphics.Color.WHITE else AiStyle.textSecondary(night),
        )
    }

    private fun sendButton(onClick: () -> Unit): FrameLayout = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(8) }
        background = AiStyle.roundBg(AiStyle.accent(night), 999f, density)
        elevation = dp(2).toFloat()
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_ai_send)
            if (night) setColorFilter(AiStyle.accent(false)) // 夜间：浅底深箭头
            layoutParams = LayoutParams(dp(14), dp(14), Gravity.CENTER)
        })
        setOnClickListener {
            val text = inputField.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && sendEnabled) {
                inputField.setText("")
                onSend?.invoke(text)
            }
        }
    }

    private fun showIme() {
        context.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---- 键盘避让：轮询 visibleDisplayFrame，窗口抬到键盘上方 ----

    private val imeCheck = object : Runnable {
        override fun run() {
            repositionForKeyboard()
            if (windowManager != null) handler.postDelayed(this, 350L)
        }
    }

    private fun repositionForKeyboard() {
        val p = params ?: return
        val rect = Rect()
        getWindowVisibleDisplayFrame(rect)
        if (anchored) {
            // 锚定态：静止位是 baseY，键盘顶起时贴键盘上沿
            val h = height.coerceAtLeast(1)
            val targetY = min(baseY, rect.bottom - h - dp(EDGE_DP))
            if (p.y != targetY) {
                p.y = targetY
                updateLayout()
            }
        } else {
            val imeHeight = (screenHeight - rect.bottom).coerceAtLeast(0)
            val targetY = -(dp(BASE_BOTTOM_MARGIN_DP) + imeHeight) // BOTTOM 锚定：负值上移
            if (p.y != targetY) {
                p.y = targetY
                updateLayout()
            }
        }
    }

    private fun updateLayout() {
        params?.let { p ->
            windowManager?.let { wm -> runCatching { wm.updateViewLayout(this, p) } }
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    companion object {
        private const val BASE_BOTTOM_MARGIN_DP = 34 // 视觉终检：24dp 时建议气泡贴底缘，避让系统手势区再加 10dp
        private const val MIN_WIDTH_DP = 300 // 锚定态最小宽：小于此建议气泡滚动也难用
        private const val EDGE_DP = 12 // 锚定态屏幕安全边距
        const val CHIP_ORGANIZE = "organize"
        const val CHIP_EXPLAIN = "explain"
        const val CHIP_TRANSLATE = "translate"
        const val CHIP_SUMMARIZE = "summarize"
    }
}
