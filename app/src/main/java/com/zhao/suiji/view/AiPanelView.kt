package com.zhao.suiji.view

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zhao.suiji.R
import com.zhao.suiji.ai.ChatMessage
import kotlin.math.min

/**
 * AI 展开面板（三态之三）：发送后承接对话——用户灰气泡右对齐、AI 纯文本流式左对齐，
 * 底部停靠输入栏可继续追问，右上 ✕ 收起。消息视图按 id 缓存，流式只 setText 最后一条。
 */
class AiPanelView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenHeightPx = 0
    private var anchored = false // 锚定态（a5：面板中心 = 悬浮窗卡片中心，原位展开不跳变）
    private var baseY = 0 // 锚定态静止 y（键盘抬升基准）
    private val handler = Handler(Looper.getMainLooper())
    private val night = AiStyle.isNight(context)

    private val chatList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val chatScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(
            chatList,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }
    private val inputField: EditText
    private val sendBtn: FrameLayout
    private val bubbles = HashMap<Long, LinearLayout>()
    private var sendEnabled = true
    private var maxBubbleWidthPx = 0

    var onSend: ((String) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onRetry: ((Long) -> Unit)? = null
    var onNewSession: (() -> Unit)? = null

    /** 回答气泡下"存为笔记"（T4）：回传消息 id，由 Service 落库后调 [markNoteSaved]。 */
    var onSaveNote: ((Long) -> Unit)? = null
    private val saveActions = HashMap<Long, TextView>()
    private var typingAnim: android.animation.ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AiStyle.roundBg(AiStyle.surface(night), 24f, density, AiStyle.stroke(night))
            setPadding(dp(12), dp(8), dp(12), dp(12))
            elevation = dp(10).toFloat()
        }

        // 头部右上：＋ 新建会话（左）与 ✕ 关闭（右）
        val header = FrameLayout(context)
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(
            FrameLayout(context).apply {
                background = AiStyle.roundBg(if (night) 0x1FEBEBF5 else 0x14787880, 999f, density)
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_ai_plus)
                    setColorFilter(AiStyle.textSecondary(night))
                    layoutParams = LayoutParams(dp(11), dp(11), Gravity.CENTER)
                })
                setOnClickListener { onNewSession?.invoke() }
            },
            LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(8) },
        )
        actions.addView(
            FrameLayout(context).apply {
                background = AiStyle.roundBg(if (night) 0x1FEBEBF5 else 0x14787880, 999f, density)
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_ai_close)
                    setColorFilter(AiStyle.textSecondary(night))
                    layoutParams = LayoutParams(dp(11), dp(11), Gravity.CENTER)
                })
                setOnClickListener { onClose?.invoke() }
            },
            LinearLayout.LayoutParams(dp(26), dp(26)),
        )
        header.addView(
            actions,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL),
        )
        card.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)))

        // 消息区
        card.addView(
            chatScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
        )

        // 底部输入栏（无 chips，面板态是追问）
        inputField = EditText(context).apply {
            background = null
            hint = "继续追问…"
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
        sendBtn = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(8) }
            background = AiStyle.roundBg(AiStyle.accent(night), 999f, density)
            elevation = dp(2).toFloat()
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_ai_send)
                if (night) setColorFilter(AiStyle.accent(false))
                layoutParams = LayoutParams(dp(14), dp(14), Gravity.CENTER)
            })
            setOnClickListener { fireSend() }
        }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AiStyle.roundBg(
                if (night) 0xFF232326.toInt() else 0xFFF2F2F7.toInt(), 21f, density,
            )
            setPadding(dp(12), dp(7), dp(7), dp(7))
        }
        pill.addView(inputField)
        pill.addView(sendBtn)
        card.addView(
            pill,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) },
        )

        addView(
            card,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    /** [growFromInput]=true：从输入框"原位绽放"成面板（a5 锚定态支点在中心；
     *  旧底部居中布局保留支点在底边，向上生长）。 */
    fun attach(
        wm: WindowManager,
        screenW: Int,
        screenH: Int,
        growFromInput: Boolean = false,
        anchor: Rect? = null,
    ) {
        windowManager = wm
        screenWidth = screenW
        screenHeight = screenH
        screenHeightPx = screenH
        val wPx = min((screenW * 0.88f).toInt(), dp(MAX_WIDTH_DP))
        val hPx = (screenH * 0.55f).toInt().coerceIn(dp(MIN_HEIGHT_DP), dp(MAX_HEIGHT_DP))
        maxBubbleWidthPx = (wPx * 0.78f).toInt()
        val lp = WindowManager.LayoutParams(
            wPx, hPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        if (anchor != null) {
            anchored = true
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = (anchor.centerX() - wPx / 2)
                .coerceIn(dp(EDGE_DP), (screenW - wPx - dp(EDGE_DP)).coerceAtLeast(dp(EDGE_DP)))
            baseY = (anchor.centerY() - hPx / 2)
                .coerceIn(dp(EDGE_DP), (screenH - hPx - dp(EDGE_DP)).coerceAtLeast(dp(EDGE_DP)))
            lp.y = baseY
        } else {
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.y = -dp(BASE_BOTTOM_MARGIN_DP) // BOTTOM 锚定：正值往屏外推，留边用负值
        }
        params = lp
        if (growFromInput) {
            pivotX = wPx / 2f
            pivotY = if (anchored) hPx / 2f else hPx.toFloat()
            alpha = 0f
            scaleY = 0.3f
            if (anchored) scaleX = 0.55f
            runCatching { wm.addView(this, lp) }
            val anim = animate().alpha(1f).scaleY(1f)
            if (anchored) anim.scaleX(1f)
            anim.setDuration(240L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            alpha = 0f
            scaleX = 0.96f
            runCatching { wm.addView(this, lp) }
            animate().alpha(1f).scaleX(1f).setDuration(180L).start()
        }
        handler.postDelayed(imeCheck, 400L)
    }

    fun detach() {
        handler.removeCallbacksAndMessages(null)
        typingAnim?.cancel()
        typingAnim = null
        runCatching {
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        }
        runCatching { windowManager?.removeView(this) }
        windowManager = null
        params = null
    }

    fun setSendEnabled(enabled: Boolean) {
        sendEnabled = enabled
        sendBtn.alpha = if (enabled) 1f else 0.35f
    }

    /** 重开会话时全量重绘。 */
    fun renderAll(messages: List<ChatMessage>) {
        typingAnim?.cancel()
        typingAnim = null
        bubbles.clear()
        saveActions.clear()
        chatList.removeAllViews()
        messages.forEach { appendMessage(it) }
        scrollToBottom()
    }

    /** 追加一条（本地新增用）。 */
    fun addMessage(msg: ChatMessage) {
        appendMessage(msg)
        scrollToBottom()
    }

    /** 流式/状态变化时按 id 局部刷新。 */
    fun updateMessage(msg: ChatMessage) {
        val container = bubbles[msg.id]
        if (container == null) {
            appendMessage(msg)
        } else {
            val index = chatList.indexOfChild(container)
            if (index >= 0) chatList.removeViewAt(index)
            bubbles.remove(msg.id)
            appendMessage(msg)
        }
        scrollToBottom()
    }

    // ---- 消息渲染 ----

    private fun appendMessage(msg: ChatMessage) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (msg.role == ChatMessage.Role.USER) Gravity.END else Gravity.START
        }
        when {
            msg.role == ChatMessage.Role.USER -> container.addView(userBubble(msg.text))
            msg.state == ChatMessage.State.FAILED -> container.addView(errorBubble(msg.id, msg.text))
            msg.text.isBlank() -> container.addView(typingDots()) // 等待首 token：三点动画
            else -> {
                container.addView(aiText(msg.text))
                if (msg.state == ChatMessage.State.DONE) container.addView(saveAction(msg.id))
            }
        }
        bubbles[msg.id] = container
        chatList.addView(
            container,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) },
        )
    }

    private fun userBubble(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(AiStyle.textPrimary(night))
        background = AiStyle.roundBg(AiStyle.userBubble(night), 18f, density)
        setPadding(dp(13), dp(9), dp(13), dp(9))
        maxWidth = maxBubbleWidthPx
        ellipsize = null
    }

    private fun aiText(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(AiStyle.textPrimary(night))
        setLineSpacing(dp(3).toFloat(), 1f)
        maxWidth = maxBubbleWidthPx + dp(30)
        setTextIsSelectable(true)
    }

    /** 完整回答下的轻量动作（T4）：存为笔记（独立成篇，不劫持悬浮窗绑定）。 */
    private fun saveAction(id: Long): TextView = TextView(context).apply {
        text = "存为笔记"
        textSize = 12f
        setTextColor(AiStyle.textSecondary(night))
        setPadding(dp(2), dp(4), dp(2), 0)
        setOnClickListener { onSaveNote?.invoke(id) }
        saveActions[id] = this
    }

    /** Service 落库成功后回执：按钮变"已存笔记"防重复。 */
    fun markNoteSaved(id: Long) {
        saveActions[id]?.apply {
            text = "已存笔记"
            alpha = 0.55f
            isClickable = false
        }
    }

    /** 等待动画：三个呼吸点（主流 AI 软件语言，a4 用户反馈替代旧版黑色光标条）。 */
    private fun typingDots(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val dotColor = AiStyle.textHint(night)
        val dots = (0..2).map { index ->
            android.view.View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(dotColor)
                }
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                    marginEnd = dp(4)
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                }
            }.also { addView(it) }
        }
        typingAnim?.cancel()
        typingAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                dots.forEachIndexed { i, dot ->
                    val phase = ((t * 3f + i) % 3f) / 3f
                    dot.alpha = 0.25f + 0.75f * (if (phase < 0.5f) phase * 2 else (1f - phase) * 2)
                }
            }
            start()
        }
    }

    private fun errorBubble(id: Long, detail: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = AiStyle.roundBg(
            if (night) 0x1AFF8080 else 0x12D93A2F, 14f, density,
        )
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(TextView(context).apply {
            text = detail.ifBlank { "请求失败" }
            textSize = 12.5f
            setTextColor(AiStyle.error(night))
            maxWidth = maxBubbleWidthPx
        })
        addView(TextView(context).apply {
            text = "重试"
            textSize = 13f
            setTextColor(AiStyle.error(night))
            paint.isFakeBoldText = true
            setOnClickListener { onRetry?.invoke(id) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) }
        })
    }

    private fun scrollToBottom() {
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun fireSend() {
        val text = inputField.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() && sendEnabled) {
            inputField.setText("")
            onSend?.invoke(text)
        }
    }

    // ---- 键盘避让（同 AiAskView 简化版轮询）----

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
            // 锚定态：静止位 baseY，键盘顶起时整面板贴键盘上沿
            val h = p.height.coerceAtLeast(1)
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
        private const val BASE_BOTTOM_MARGIN_DP = 34 // 与输入框一致，避让系统手势区
        private const val EDGE_DP = 12 // 锚定态屏幕安全边距
        private const val MAX_WIDTH_DP = 480
        private const val MIN_HEIGHT_DP = 320
        private const val MAX_HEIGHT_DP = 620
    }
}
