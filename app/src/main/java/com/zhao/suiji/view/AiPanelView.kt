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
    private var screenHeight = 0
    private var screenHeightPx = 0
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

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AiStyle.roundBg(AiStyle.surface(night), 24f, density, AiStyle.stroke(night))
            setPadding(dp(12), dp(8), dp(12), dp(12))
            elevation = dp(10).toFloat()
        }

        // 头部：右上 ✕
        val header = FrameLayout(context)
        header.addView(
            FrameLayout(context).apply {
                background = AiStyle.roundBg(if (night) 0x1FEBEBF5 else 0x14787880, 999f, density)
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_ai_close)
                    setColorFilter(AiStyle.textSecondary(night))
                    layoutParams = LayoutParams(dp(11), dp(11), Gravity.CENTER)
                })
                setOnClickListener { onClose?.invoke() }
            },
            LayoutParams(dp(26), dp(26), Gravity.END or Gravity.CENTER_VERTICAL),
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

    fun attach(wm: WindowManager, screenW: Int, screenH: Int) {
        windowManager = wm
        screenHeight = screenH
        screenHeightPx = screenH
        val wPx = min((screenW * 0.88f).toInt(), dp(MAX_WIDTH_DP))
        val hPx = (screenH * 0.55f).toInt().coerceIn(dp(MIN_HEIGHT_DP), dp(MAX_HEIGHT_DP))
        maxBubbleWidthPx = (wPx * 0.78f).toInt()
        params = WindowManager.LayoutParams(
            wPx, hPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = -dp(BASE_BOTTOM_MARGIN_DP) // BOTTOM 锚定：正值往屏外推，留边用负值
        }
        alpha = 0f
        scaleX = 0.96f
        runCatching { wm.addView(this, params) }
        animate().alpha(1f).scaleX(1f).setDuration(180L).start()
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
        sendBtn.alpha = if (enabled) 1f else 0.35f
    }

    /** 重开会话时全量重绘。 */
    fun renderAll(messages: List<ChatMessage>) {
        bubbles.clear()
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
            else -> container.addView(aiText(msg.text, msg.state == ChatMessage.State.STREAMING))
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

    private fun aiText(text: String, streaming: Boolean): TextView = TextView(context).apply {
        this.text = if (streaming) "$text$CARET" else text  // 流式尾部光标
        textSize = 14f
        setTextColor(AiStyle.textPrimary(night))
        setLineSpacing(dp(3).toFloat(), 1f)
        maxWidth = maxBubbleWidthPx + dp(30)
        setTextIsSelectable(true)
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
        val imeHeight = (screenHeight - rect.bottom).coerceAtLeast(0)
        val targetY = -(dp(BASE_BOTTOM_MARGIN_DP) + imeHeight) // BOTTOM 锚定：负值上移
        if (p.y != targetY) {
            p.y = targetY
            updateLayout()
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
        private const val MAX_WIDTH_DP = 480
        private const val MIN_HEIGHT_DP = 320
        private const val MAX_HEIGHT_DP = 620
        private const val CARET = "▏" // 流式尾光标
    }
}
