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

/**
 * AI 极简输入框（三态之二）：胶囊输入栏 + 单排建议气泡，底部居中悬浮。
 * 发送走 [onSend]；建议气泡走 [onChip]（id 常量见 companion）。
 * 键盘避让：轮询 visibleDisplayFrame 把窗口抬到键盘上方（悬浮窗成熟做法的简化版）。
 */
class AiAskView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenHeight = 0
    private val handler = Handler(Looper.getMainLooper())
    private val night = AiStyle.isNight(context)

    val inputField: EditText
    private var sendEnabled = true
    private var noteChipsEnabled = false
    private var chipOrganize: TextView? = null
    private var chipSummarize: TextView? = null

    var onSend: ((String) -> Unit)? = null
    var onChip: ((String) -> Unit)? = null
    var onOutside: (() -> Unit)? = null

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
        pill.addView(inputField)
        pill.addView(sendButton {})
        content.addView(
            pill,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        // 单排建议气泡
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(
            CHIP_ORGANIZE to "整理这篇",
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
        content.addView(
            chips,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) },
        )
        addView(
            content,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
        )
    }

    fun attach(wm: WindowManager, screenW: Int, screenH: Int) {
        windowManager = wm
        screenHeight = screenH
        params = WindowManager.LayoutParams(
            (screenW * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // BOTTOM 锚定时 y 正值往屏幕外推，留边必须用负值（真机踩坑：+24dp 整窗沉底只露 6px）
            y = -dp(BASE_BOTTOM_MARGIN_DP)
        }
        alpha = 0f
        translationY = dp(20).toFloat()
        runCatching { wm.addView(this, params) }
        animate().alpha(1f).translationY(0f).setDuration(200L).start()
        inputField.requestFocus()
        handler.postDelayed({ showIme() }, 150L)
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

    /** 整理这篇/总结依赖笔记内容：无笔记置灰（T3）。 */
    fun setNoteChipsEnabled(enabled: Boolean) {
        noteChipsEnabled = enabled
        val alpha = if (enabled) 1f else 0.4f
        chipOrganize?.alpha = alpha
        chipSummarize?.alpha = alpha
    }

    /** 点输入框外区域收起（FLAG_WATCH_OUTSIDE_TOUCH 的 ACTION_OUTSIDE）。 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) onOutside?.invoke()
        return super.onTouchEvent(event)
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
        private const val BASE_BOTTOM_MARGIN_DP = 34 // 视觉终检：24dp 时建议气泡贴底缘，避让系统手势区再加 10dp
        const val CHIP_ORGANIZE = "organize"
        const val CHIP_EXPLAIN = "explain"
        const val CHIP_TRANSLATE = "translate"
        const val CHIP_SUMMARIZE = "summarize"
    }
}
