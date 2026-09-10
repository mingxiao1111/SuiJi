package com.zhao.suiji.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.zhao.suiji.FloatNoteApp
import com.zhao.suiji.MainActivity
import com.zhao.suiji.R
import com.zhao.suiji.SettingsActivity
import com.zhao.suiji.ai.AiChatClient
import com.zhao.suiji.ai.AiConfig
import com.zhao.suiji.ai.ChatEvent
import com.zhao.suiji.ai.ChatMessage
import com.zhao.suiji.ai.RequestMessage
import com.zhao.suiji.data.SettingsRepository
import com.zhao.suiji.data.Side
import com.zhao.suiji.manager.AutoCollapseManager
import com.zhao.suiji.manager.AutoSaveManager
import com.zhao.suiji.manager.WindowManagerHelper
import com.zhao.suiji.text.NoteTextUtils
import com.zhao.suiji.util.ScreenUtils
import com.zhao.suiji.view.AiAskView
import com.zhao.suiji.view.AiOrbView
import com.zhao.suiji.view.AiPanelView
import com.zhao.suiji.view.FloatingBallView
import com.zhao.suiji.view.FloatingNoteWindowView
import com.zhao.suiji.view.ToolbarAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 悬浮笔记前台服务（v2.0 一期：三段长条手柄 + 按钮化收起，设计见 V2-设计说明.md）：
 * 协调 贴边竖条 <-> 悬浮窗(+独立工具栏) 的互斥状态机：
 * - 点竖条 -> 展开窗（竖条隐藏，工具栏显示），长条中心与竖条中心对齐
 * - 点长条下段箭头 / 超时 -> 收起为贴边竖条（拖动/甩动不再收起）
 * - 悬浮窗绑定最近编辑笔记，自动保存并与主界面实时同步（计划 7.8）
 */
class FloatingNoteService : Service() {

    private val app get() = applicationContext as FloatNoteApp
    private val settings: SettingsRepository get() = app.settingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wmHelper: WindowManagerHelper

    private var ballView: FloatingBallView? = null
    private var windowView: FloatingNoteWindowView? = null

    @Volatile private var hapticOn = true

    // 目标区/竖条渲染缓存（避免主线程读 DataStore）
    @Volatile private var stickColorHex = "#2E2E2E"
    @Volatile private var stickWidthDp = 24
    @Volatile private var stickHeightDp = 90
    @Volatile private var stickFadeAlpha = 0.30f // 形变动画末段的透明度衔接值
    @Volatile private var stickFixedPosition = false
    @Volatile private var barTextureVal = com.zhao.suiji.data.BarTexture.STANDARD

    /** 贴边侧别缓存（v2.0 二期镜像系统）：唯一真源，展开/收起/换边都读它。 */
    @Volatile private var side = Side.LEFT
    private var sideSynced = false

    private var currentNoteId = -1L
    private var syncJob: Job? = null
    private var autoSave: AutoSaveManager<String>? = null
    private lateinit var autoCollapse: AutoCollapseManager

    // 缓存的格式设置（工具栏动作即时使用）
    @Volatile private var olStyle = 0
    @Volatile private var ulStyle = 0

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startAsForeground()
        wmHelper = WindowManagerHelper(this)
        autoCollapse = AutoCollapseManager(scope) {
            // 无操作超时：收起为贴左竖条（M5.6：收起固定左边）
            if (windowView != null) collapseWindow()
        }
        observeBallAppearance()
        observeWindowSettings()
        observeAutoCollapse()
        observeSide()
        observeAiSettings()
        showStick()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    /** 横竖屏/分屏切换：窗口与竖条拉回可视区（M6 边界处理）。 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!ensureOverlayPermissionAlive()) return
        val screenW = wmHelper.screenWidth
        val screenH = wmHelper.screenHeight
        windowView?.onScreenChanged(screenW, screenH)
        ballView?.onScreenChanged(screenW, screenH)
        if (aiUiState != AiUiState.NONE) closeAiUi() // AI 窗口尺寸随屏算，旋转直接收起（T3 再做重建）
    }

    /**
     * 权限被系统撤销时的优雅退出（M6 容错）：不闪退，保存数据并停止服务；
     * 主界面 onResume 复查后会重新给出授权引导。
     */
    private fun ensureOverlayPermissionAlive(): Boolean {
        if (android.provider.Settings.canDrawOverlays(this)) return true
        scope.launch {
            saveWindowNow()
            windowView?.detach()
            windowView = null
            ballView?.detach()
            ballView = null
            aiOrb?.detach()
            aiOrb = null
            detachAiUi()
            aiUiState = AiUiState.NONE
            stopSelf()
        }
        return false
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        // 必须移除全部 Window 并清空引用，防止泄漏与重复悬浮球（计划 11.7）
        saveWindowNow()
        syncJob?.cancel()
        syncJob = null
        autoSave = null
        windowView?.detach()
        windowView = null
        ballView?.detach()
        ballView = null
        aiGenJob?.cancel()
        aiOrb?.detach()
        aiOrb = null
        detachAiUi()
        aiUiState = AiUiState.NONE
        super.onDestroy()
    }

    // ---- 贴边竖条 ----

    /**
     * 贴边竖条（v2.0：唯一收起形态）。[yHintPx] 形变衔接路径（收起末尾）时**同步挂载**——
     * 全部用缓存参数、零 DataStore 读取（异步读会有数帧"两者都不在"的闪断，真机肉眼可见）；
     * 服务启动（yHintPx=null）仍读一次快照初始化侧别。
     */
    private fun showStick(yHintPx: Int? = null, slideIn: Boolean = false, fadeIn: Boolean = false) {
        if (!ensureOverlayPermissionAlive()) return
        // 防御：清理可能残留的旧竖条（快速收展竞态时可能留下双条，M6 反馈）
        ballView?.detach()
        ballView = null
        if (yHintPx != null) {
            // 收起位置即新记忆（M6 双向锚定），下次展开从这继续
            scope.launch { settings.setBallPosition(0, ScreenUtils.pxToDp(this@FloatingNoteService, yHintPx)) }
            attachStickSync(yHintPx, slideIn, fadeIn)
        } else {
            scope.launch {
                val s = settings.snapshot()
                if (!sideSynced) { // observeSide 首发射未到时兜底初始化（同一主线程，无竞态）
                    side = Side.fromValue(s.side)
                    sideSynced = true
                }
                // 顺手同步渲染缓存，确保首挂即用存储值而非代码默认
                stickColorHex = s.ballColor
                stickFadeAlpha = s.fadeAlpha
                stickFixedPosition = s.fixedPosition
                stickWidthDp = s.stickWidthDp
                stickHeightDp = s.stickHeightDp
                val screenH = wmHelper.screenHeight
                val stickH = ScreenUtils.dpToPx(this@FloatingNoteService, s.stickHeightDp)
                val yPx = if (s.ballY < 0) screenH * 2 / 5
                else ScreenUtils.dpToPx(this@FloatingNoteService, s.ballY)
                attachStickSync(yPx.coerceIn(0, (screenH - stickH).coerceAtLeast(0)), slideIn, fadeIn)
            }
        }
    }

    /** 用缓存参数同步挂竖条（形变衔接零延迟路径；参数已被各观察者刷新）。 */
    private fun attachStickSync(yPx: Int, slideIn: Boolean, fadeIn: Boolean = false) {
        val screenW = wmHelper.screenWidth
        val screenH = wmHelper.screenHeight
        val stick = FloatingBallView(this)
        stick.applyAppearance(
            stickColorHex, stickFadeAlpha, stickFixedPosition, stickWidthDp, stickHeightDp,
            barTextureVal,
        )
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        stick.attach(wm, screenW, screenH, yPx, slideIn, side, fadeIn)
        stick.onStickActivate = { expandWindow() }
        stick.onPositionSettled = { _, yDp ->
            scope.launch { settings.setBallPosition(0, yDp) }
        }
        stick.onHapticTick = { vibrateTick() }
        ballView = stick
    }

    private fun hideBall() {
        ballView?.detach()
        ballView = null
    }

    /** 订阅竖条外观设置，变更即时生效（v2.0 三期：质感与长条同源）。 */
    private fun observeBallAppearance() {
        scope.launch {
            combine(
                settings.ballColor, settings.fadeAlpha, settings.fixedPosition,
                settings.stickWidthDp, settings.stickHeightDp, settings.barTexture,
            ) { v: Array<*> -> // 6 个流超出带类型参数的重载上限，走数组版本
                stickColorHex = v[0] as String
                stickFadeAlpha = v[1] as Float
                stickFixedPosition = v[2] as Boolean
                stickWidthDp = v[3] as Int
                stickHeightDp = v[4] as Int
                barTextureVal = com.zhao.suiji.data.BarTexture.fromValue(v[5] as Int)
                ballView?.applyAppearance(
                    v[0] as String, v[1] as Float, v[2] as Boolean, v[3] as Int, v[4] as Int,
                    com.zhao.suiji.data.BarTexture.fromValue(v[5] as Int),
                )
            }.collect { }
        }
        // 首次同步缓存（collect 首发射也会更新，此处兜底服务刚启动的间隙）
        scope.launch {
            val s = settings.snapshot()
            stickColorHex = s.ballColor
            stickFadeAlpha = s.fadeAlpha
            stickWidthDp = s.stickWidthDp
            stickHeightDp = s.stickHeightDp
        }
    }

    // ---- 悬浮窗 ----

    /** 点球展开：绑定最近编辑笔记（无则新建），球隐藏、窗与工具栏显示（计划 4.5）。 */
    private var expanding = false // 展开进行中标志：快速双击竖条防双窗口（M6）

    private fun expandWindow() {
        if (!ensureOverlayPermissionAlive()) return
        if (windowView != null || expanding) return
        if (aiUiState != AiUiState.NONE) closeAiUi() // AI 开着时点竖条：先收 AI，笔记窗优先
        expanding = true
        scope.launch {
            val s = settings.snapshot()

            // 确定绑定的笔记：lastNoteId 有效 -> 最近一篇 -> 新建（计划 4.5）
            var note = if (s.lastNoteId > 0) app.noteRepository.getNote(s.lastNoteId) else null
            if (note == null) note = app.noteRepository.getLatestNote()
            if (note == null) {
                val newId = app.noteRepository.createNote()
                note = app.noteRepository.getNote(newId)
            }
            val boundNote = note ?: run {
                expanding = false
                return@launch
            }
            currentNoteId = boundNote.id
            settings.setLastNoteId(boundNote.id)

            // 展开锚定竖条（v2.0：长条中心 = 竖条中心）——先取竖条位置；
            // 竖条暂不拆：窗口从竖条形态起步并盖在其上后再拆，消灭空窗闪断（a6.2）
            val stickCenterYPx = ballView?.stickCenterY ?: -1
            android.util.Log.d(
                "V2Anchor", "expand: stickCenterY=$stickCenterYPx ballYpx=${ballView?.stickY}",
            )

            val screenW = wmHelper.screenWidth
            val screenH = wmHelper.screenHeight
            val wPx = ScreenUtils.dpToPx(this@FloatingNoteService, s.windowWidth)
            val hPx = ScreenUtils.dpToPx(this@FloatingNoteService, s.windowHeight)
            // 窗口贴所在侧展开（左：x=边距；右：x=屏宽-边距-总宽），纵向与竖条中心对齐
            val marginPx = ScreenUtils.dpToPx(this@FloatingNoteService, s.windowEdgeMarginDp)
            val totalWApprox = wPx + ScreenUtils.dpToPx(this@FloatingNoteService, s.barThicknessDp)
            val xPx = if (side.isRight) screenW - marginPx - totalWApprox else marginPx
            val yPx = when {
                stickCenterYPx >= 0 -> (stickCenterYPx - hPx / 2).coerceAtLeast(0)

                s.windowHeightOffset >= 0 -> ScreenUtils.dpToPx(this@FloatingNoteService, s.windowHeightOffset)
                else -> screenH / 6
            }
            android.util.Log.d("V2Anchor", "expand: hPx=$hPx yPx=$yPx stickHdp=$stickHeightDp")
            // 锚定后的位置即新记忆，模型统一：展开位置由竖条决定
            settings.setWindowPosition(
                ScreenUtils.pxToDp(this@FloatingNoteService, xPx),
                ScreenUtils.pxToDp(this@FloatingNoteService, yPx),
            )

            val win = FloatingNoteWindowView(this@FloatingNoteService)
            win.alpha = 0f // addView 前压 0（a7.1）：防新 Surface 首帧全显一帧，fadeInExpand 从 0 淡入
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            win.attach(wm, screenW, screenH, xPx, yPx, wPx, hPx)
            win.setSide(side) // 镜像内部布局（长条侧/缩放手柄/工具栏顺序，v2.0 二期）
            win.setNoteContent(note.content)
            win.applyFontSize(s.fontSizeSp)
            win.applyWindowOpacity(s.windowOpacity)
            win.setShowResizeHandle(s.showResizeHandle)
            win.setFixedPosition(s.fixedPosition)
            win.applyBarStyle(
                s.handleColor, s.handleAlpha, s.handleGradient,
                s.barThicknessDp, s.barLengthRatioPct, s.barCornerDp,
                com.zhao.suiji.data.BarTexture.fromValue(s.barTexture),
            )
            win.applyWindowEdgeMargin(s.windowEdgeMarginDp)
            win.onContentChanged = { content ->
                autoSave?.onContentChanged(content)
                autoCollapse.reset() // 打字也是交互：重置自动收起计时（计划 7.6 三源之二）
            }
            win.onUserInteraction = { autoCollapse.reset() } // 触摸（三源之一）
            win.onCollapseRequest = { collapseWindow() }
            win.onHapticTick = { vibrateTick() }
            win.onPositionSettled = { xDp, yDp ->
                scope.launch { settings.setWindowPosition(xDp, yDp) }
            }
            win.onSizeSettled = { wDp, hDp ->
                scope.launch { settings.setWindowSize(wDp, hDp) }
            }
            // 工具栏按钮点击（工具栏已并入窗口，M5.2）
            win.onToolbarAction = ::handleToolbarAction
            windowView = win

            // 主动渲染工具栏：observeWindowSettings 的 collect 只在设置变化时发射，
            // 窗刚创建时不会重新发射，必须手动应用一次（修复"竖条展开后工具栏不出现"）
            win.applyToolbar(
                ToolbarAction.visibleActions(s.toolbarButtons),
                s.toolbarPosition, s.toolbarScale, s.toolbarOpacity,
            )

            // 展开形变（v2.0 三期）：窗口从竖条几何长成目标尺寸；竖条是唯一展开入口，
            // stickCenterYPx 一定有效（无竖条时直接显示，不做形变）
            // 展开动画（a7 极简）：整窗从所在侧轻移淡入；竖条此刻即拆——
            // 长条淡入位=竖条原位，同位衔接像"竖条长成窗口"，两形态永不同屏
            win.fadeInExpand()
            hideBall()

            // 自动保存：与主界面共用同一套防抖（计划 7.5 / 7.8）
            autoSave = AutoSaveManager<String>(scope) { content ->
                scope.launch { app.noteRepository.updateContent(currentNoteId, content) }
            }

            observeNoteSync()
            autoCollapse.reset() // 窗展开后开始计时（三源重置保证打字/触摸/按钮都会续期）
            expanding = false
        }
    }

    /** 订阅当前笔记，主界面改动实时同步到窗；正在输入时不替换避免光标跳动（计划 4.5）。 */
    private fun observeNoteSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            app.noteRepository.observeById(currentNoteId).collect { note ->
                val win = windowView ?: return@collect
                if (note == null) {
                    win.showDeletedState()
                    settings.setLastNoteId(0)
                } else if (!win.isTyping) {
                    win.setNoteContent(note.content)
                }
            }
        }
    }

    /**
     * 收起（v2.0 三期形变动画）：保存 + 补存窗口位置 -> 卡片淡出、长条收缩成竖条
     * （宽高/位置/透明度全部衔接竖条末态，约 250ms）-> 换真竖条 -> 振动确认。
     */
    private fun collapseWindow() {
        val win = windowView ?: return
        // 长条与窗同高，竖条落位取长条中心；把中心高度换算回竖条顶部
        val stickH = ScreenUtils.dpToPx(this, stickHeightDp)
        android.util.Log.d("V2Anchor", "collapse: winY=${win.windowY} centerY=${win.windowCenterY} stickH=$stickH")
        val yHint = (win.windowCenterY - stickH / 2).coerceAtLeast(0)
        saveWindowNow()
        // 补存窗口位置：箭头收起路径没走 onPositionSettled，防止下次展开回到旧位置
        scope.launch {
            settings.setWindowPosition(
                ScreenUtils.pxToDp(this@FloatingNoteService, win.windowX),
                ScreenUtils.pxToDp(this@FloatingNoteService, win.windowY),
            )
        }
        syncJob?.cancel()
        syncJob = null
        autoSave = null
        autoCollapse.stop()
        windowView = null // 先断开引用，防动画期间重入
        win.fadeCollapse {
            // 窗口已完全淡出后：拆窗 + 竖条同步挂载（零读库）并在原位淡入——
            // 前后两个形态永不同屏，同位衔接，天然无重影（a7 极简方案）。
            // 淡入动画在 attach 内部（addView 前 alpha 已压 0），杜绝新 Surface
            // 首帧以终值透明度合成一帧的"闪现"（a7.1，用户真机反馈）
            win.detach()
            showStick(yHint, fadeIn = true)
            vibrateTick()
        }
    }

    /** 轻振动（长条按压 / 收起完成确认），可在设置关闭。 */
    private fun vibrateTick() {
        if (!hapticOn) return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(android.os.VibratorManager::class.java)).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.os.Vibrator::class.java)
            } ?: return
            if (Build.VERSION.SDK_INT >= 29) {
                vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
            } else {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(15, android.os.VibrationEffect.DEFAULT_AMPLITUDE),
                )
            }
        }
    }

    private fun saveWindowNow() {
        val win = windowView ?: return
        autoSave?.saveNow(win.getNoteContent())
    }

    /** 订阅窗相关设置（字号 / 不透明度 / 手柄 / 固定位置 / 格式 / 工具栏按钮）。 */
    private data class ToolbarConfig(
        val buttons: Set<String>,
        val position: Int,
        val scale: Float,
        val opacity: Float,
        val olStyle: Int,
        val ulStyle: Int,
    )

    private fun observeWindowSettings() {
        data class WindowLook(
            val fontSize: Int, val opacity: Float,
            val showHandle: Boolean, val fixed: Boolean,
        )

        scope.launch {
            combine(
                settings.fontSizeSp, settings.windowOpacity,
                settings.showResizeHandle, settings.fixedPosition,
            ) { fontSize, opacity, handle, fixed ->
                WindowLook(fontSize, opacity, handle, fixed)
            }.collect { l ->
                windowView?.applyFontSize(l.fontSize)
                windowView?.applyWindowOpacity(l.opacity)
                windowView?.setShowResizeHandle(l.showHandle)
                windowView?.setFixedPosition(l.fixed)
            }
        }
        scope.launch {
            settings.hapticFeedback.collect { hapticOn = it }
        }
        scope.launch {
            val cfgFlow = combine(
                settings.toolbarButtons, settings.toolbarPosition, settings.toolbarScale,
                settings.toolbarOpacity, settings.olStyle,
            ) { buttons, position, scale, opacity, ol ->
                ToolbarConfig(buttons, position, scale, opacity, ol, 0)
            }.combine(settings.ulStyle) { c, ul -> c.copy(ulStyle = ul) }

            cfgFlow.collect { cfg ->
                olStyle = cfg.olStyle
                ulStyle = cfg.ulStyle
                // 工具栏已并入窗口（M5.2）：显隐/位置/大小/透明度设置直接刷新窗口内的工具栏行
                windowView?.applyToolbar(
                    ToolbarAction.visibleActions(cfg.buttons),
                    cfg.position, cfg.scale, cfg.opacity,
                )
            }
        }
        // 长条样式（v2.0-a6 三期：颜色/透明度/渐变沿用原把手 key，厚度/长度/圆角/质感是新设置）
        scope.launch {
            combine(
                settings.handleColor, settings.handleAlpha, settings.handleGradient,
                settings.barThicknessDp, settings.barLengthRatioPct, settings.barCornerDp,
                settings.barTexture,
            ) { v: Array<*> -> // 超出带类型参数的重载上限，走数组版本
                windowView?.applyBarStyle(
                    v[0] as String, v[1] as Float, v[2] as Float,
                    v[3] as Int, v[4] as Int, v[5] as Int,
                    com.zhao.suiji.data.BarTexture.fromValue(v[6] as Int),
                )
            }.collect { }
        }
        // 窗口贴边边距
        scope.launch {
            settings.windowEdgeMarginDp.collect { margin -> windowView?.applyWindowEdgeMargin(margin) }
        }
    }

    /** 订阅自动收起设置（计划 7.6）：0 = 永不。 */
    private fun observeAutoCollapse() {
        scope.launch {
            combine(settings.autoCollapse, settings.autoCollapseDelayMs) { on, ms ->
                if (on) ms else 0L
            }.collect { autoCollapse.updateDelay(it) }
        }
    }

    // ---- 贴边侧别（v2.0 二期镜像系统）----

    /** 订阅设置侧别：设置页改「贴边方向」等效换边翻转；首发射只同步缓存不动作。 */
    private fun observeSide() {
        scope.launch {
            settings.side.collect { v ->
                val s = Side.fromValue(v)
                if (!sideSynced) {
                    side = s
                    sideSynced = true
                    return@collect
                }
                if (s != side) performSwap(s, persist = false)
            }
        }
    }

    /**
     * 换边执行（V2-设计说明 §2.3）：整窗翻到屏幕中线对称位（动画）、内部布局镜像、
     * 键盘保持；收起态则竖条换到对侧边缘滑入。persist=false 用于设置页触发（库里已是新值）。
     */
    private fun performSwap(newSide: Side, persist: Boolean) {
        android.util.Log.d("V2Swap", "performSwap newSide=$newSide cur=$side win=${windowView != null}")
        if (newSide == side) return
        side = newSide
        sideSynced = true
        if (persist) scope.launch { settings.setSide(newSide) }
        vibrateTick()
        val win = windowView
        if (win != null) {
            win.swapToSide(newSide)
        } else {
            val yHint = ballView?.stickY
            hideBall()
            showStick(yHint, slideIn = true)
        }
    }

    // ---- AI 助手（v2.1 三态：左下圆钮 → 极简输入框 → 展开面板，独立于笔记窗）----

    private enum class AiUiState { NONE, ASK, PANEL }

    private var aiOrb: AiOrbView? = null
    private var aiAsk: AiAskView? = null
    private var aiPanel: AiPanelView? = null
    private var aiUiState = AiUiState.NONE

    private val aiClient = AiChatClient()
    private val aiMessages = mutableListOf<ChatMessage>()
    private var aiGenerating = false
    private var aiSeq = 0L
    private var aiGenJob: Job? = null
    private var aiCfg: AiConfig? = null

    @Volatile private var aiEnabled = true
    @Volatile private var aiOrbAlphaVal = SettingsRepository.DEFAULT_AI_ORB_ALPHA

    /** 订阅 AI 开关与圆钮透明度：开关实时挂/摘圆钮。 */
    private fun observeAiSettings() {
        scope.launch {
            combine(settings.aiAssistantEnabled, settings.aiOrbAlpha) { e, a -> e to a }.collect { (e, a) ->
                aiEnabled = e
                aiOrbAlphaVal = a
                if (e) {
                    if (aiOrb == null) attachAiOrb() else aiOrb?.applyAlpha(a)
                } else {
                    aiOrb?.detach()
                    aiOrb = null
                    if (aiUiState != AiUiState.NONE) closeAiUi()
                }
            }
        }
    }

    private fun attachAiOrb() {
        if (!ensureOverlayPermissionAlive()) return
        aiOrb?.detach()
        aiOrb = AiOrbView(this).apply {
            applyAlpha(aiOrbAlphaVal)
            setGenerating(aiGenerating)
            onActivate = { onAiOrbTap() }
            attach(getSystemService(WINDOW_SERVICE) as WindowManager, wmHelper.screenWidth, wmHelper.screenHeight)
        }
    }

    private fun onAiOrbTap() {
        scope.launch {
            val cfg = loadAiConfig()
            if (!cfg.isConfigured) {
                // 未配置：直达设置页并展开 AI 组（PRD v2 首次配置）
                runCatching {
                    startActivity(
                        Intent(this@FloatingNoteService, SettingsActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("focus_ai", true),
                    )
                }
                return@launch
            }
            aiCfg = cfg
            if (windowView != null) collapseWindow() // 笔记窗自动收起为竖条（PRD v2）
            if (aiMessages.isEmpty()) showAiAsk() else showAiPanel()
        }
    }

    private suspend fun loadAiConfig(): AiConfig = AiConfig(
        baseUrl = settings.aiBaseUrl.first(),
        apiKey = app.secretStore.getApiKey(),
        chatModel = settings.aiChatModel.first(),
        thinkModel = settings.aiThinkModel.first(),
        visionModel = settings.aiVisionModel.first(),
    )

    private fun showAiAsk() {
        detachAiUi()
        aiOrb?.detach() // 圆钮与输入框/面板互斥：打开即隐，收起才回
        aiOrb = null
        aiUiState = AiUiState.ASK
        aiAsk = AiAskView(this).apply {
            onSend = { aiSubmit(it) }
            onChip = { aiChip(it) }
            onOutside = { closeAiUi() }
            attach(getSystemService(WINDOW_SERVICE) as WindowManager, wmHelper.screenWidth, wmHelper.screenHeight)
        }
    }

    private fun showAiPanel() {
        detachAiUi()
        aiOrb?.detach()
        aiOrb = null
        aiUiState = AiUiState.PANEL
        aiPanel = AiPanelView(this).apply {
            onSend = { aiSubmit(it) }
            onClose = { closeAiUi() }
            onRetry = { aiRetry(it) }
            attach(getSystemService(WINDOW_SERVICE) as WindowManager, wmHelper.screenWidth, wmHelper.screenHeight)
            renderAll(aiMessages.toList())
            setSendEnabled(!aiGenerating)
        }
    }

    private fun detachAiUi() {
        aiAsk?.detach()
        aiAsk = null
        aiPanel?.detach()
        aiPanel = null
    }

    /** 面板 ✕ / 点输入框外 / 开关关闭：回圆钮。生成不中断，圆钮呼吸点接力。 */
    private fun closeAiUi() {
        detachAiUi()
        aiUiState = AiUiState.NONE
        if (aiEnabled) attachAiOrb()
    }

    private fun aiSubmit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || aiGenerating) return
        aiMessages += ChatMessage(++aiSeq, ChatMessage.Role.USER, text)
        if (aiUiState != AiUiState.PANEL) showAiPanel() else aiPanel?.addMessage(aiMessages.last())
        startAiGeneration()
    }

    /** 建议指令：整理/总结携带当前笔记正文；解释/翻译作用于输入框草稿。 */
    private fun aiChip(id: String) {
        scope.launch {
            val draft = aiAsk?.inputField?.text?.toString()?.trim().orEmpty()
            when (id) {
                AiAskView.CHIP_ORGANIZE ->
                    aiSubmitWithNote("请把下面的笔记整理成结构清晰的笔记，给出标题、分点和待办清单：")
                AiAskView.CHIP_SUMMARIZE -> aiSubmitWithNote("请用几句话总结下面的笔记内容：")
                AiAskView.CHIP_EXPLAIN -> if (draft.isNotEmpty()) aiSubmit("请解释：$draft")
                AiAskView.CHIP_TRANSLATE ->
                    if (draft.isNotEmpty()) aiSubmit("请把下面的内容翻译成英文：\n$draft")
            }
        }
    }

    private suspend fun aiSubmitWithNote(instruction: String) {
        val note = app.noteRepository.getNote(currentNoteId)
        if (note == null || note.content.isBlank()) return
        aiSubmit("$instruction\n\n${note.content}")
    }

    private fun startAiGeneration() {
        val cfg = aiCfg ?: return
        if (aiGenerating) return
        val reply = ChatMessage(++aiSeq, ChatMessage.Role.ASSISTANT, "", ChatMessage.State.STREAMING)
        aiMessages += reply
        aiPanel?.addMessage(reply)
        aiGenerating = true
        aiPanel?.setSendEnabled(false)
        aiOrb?.setGenerating(true)
        val history = aiMessages
            .filter { it.state != ChatMessage.State.FAILED && it.text.isNotBlank() }
            .takeLast(20)
            .map { RequestMessage(if (it.role == ChatMessage.Role.USER) "user" else "assistant", it.text) }
        aiGenJob = scope.launch {
            val idx = aiMessages.indexOf(reply)
            val buf = StringBuilder()
            var received = false
            var errorMsg: String? = null
            try {
                aiClient.streamReply(cfg, cfg.chatModel, history).collect { ev ->
                    when (ev) {
                        is ChatEvent.Delta -> {
                            received = true
                            buf.append(ev.text)
                            aiMessages[idx] = reply.copy(text = buf.toString())
                            aiPanel?.updateMessage(aiMessages[idx])
                        }
                        is ChatEvent.Done -> Unit
                        is ChatEvent.HttpError -> errorMsg = ev.message
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                errorMsg = e.message ?: "连接中断"
            }
            val final = when {
                errorMsg != null -> reply.copy(state = ChatMessage.State.FAILED, text = errorMsg!!)
                received -> reply.copy(state = ChatMessage.State.DONE, text = buf.toString())
                else -> reply.copy(state = ChatMessage.State.FAILED, text = "连接中断，回答未完成")
            }
            if (idx in aiMessages.indices) aiMessages[idx] = final
            aiPanel?.updateMessage(final)
            aiGenerating = false
            aiPanel?.setSendEnabled(true)
            aiOrb?.setGenerating(false)
        }
    }

    /** 失败重试：移除失败占位，用同一上下文重新生成。 */
    private fun aiRetry(msgId: Long) {
        val idx = aiMessages.indexOfFirst { it.id == msgId }
        if (idx >= 0) {
            aiMessages.removeAt(idx)
            aiPanel?.renderAll(aiMessages.toList())
        }
        startAiGeneration()
    }

    private fun handleToolbarAction(action: ToolbarAction) {
        val win = windowView ?: return
        autoCollapse.reset() // 工具栏点击（三源之三）
        android.util.Log.d("V2Toolbar", "action=$action side=$side")
        when (action) {
            ToolbarAction.NEW_NOTE -> scope.launch {
                val newId = app.noteRepository.createNote()
                rebindNote(newId)
            }

            ToolbarAction.SWAP_SIDE -> performSwap(side.opposite(), persist = true)

            ToolbarAction.ORDERED_LIST ->
                win.applyTextAction { text, sel -> NoteTextUtils.insertOrderedPrefix(text, sel, olStyle) }

            ToolbarAction.UNORDERED_LIST ->
                win.applyTextAction { text, sel -> NoteTextUtils.insertUnorderedPrefix(text, sel, ulStyle) }

            ToolbarAction.TODO ->
                win.applyTextAction { text, sel -> NoteTextUtils.toggleTodo(text, sel) }

            ToolbarAction.INDENT ->
                win.applyTextAction { text, sel -> NoteTextUtils.indentLine(text, sel) }

            ToolbarAction.OUTDENT ->
                win.applyTextAction { text, sel -> NoteTextUtils.outdentLine(text, sel) }

            ToolbarAction.TIMESTAMP ->
                win.applyTextAction { text, sel ->
                    val ts = NoteTextUtils.timestamp()
                    NoteTextUtils.TextEdit(sel, sel, ts, sel + ts.length)
                }

            ToolbarAction.FONT_MINUS -> scope.launch { settings.setFontSizeSp(cachedFontSize() - 1) }
            ToolbarAction.FONT_PLUS -> scope.launch { settings.setFontSizeSp(cachedFontSize() + 1) }

            ToolbarAction.HOME -> openActivity(MainActivity::class.java)
        }
    }

    /** 从悬浮窗（非 Activity 上下文）启动界面，需要 NEW_TASK 标志。 */
    private fun openActivity(cls: Class<*>) {
        runCatching {
            val intent = Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    private suspend fun cachedFontSize(): Int = settings.fontSizeSp.first()

    /** 工具栏「新建」：保存当前笔记并绑定一篇新空笔记（先落库再切换，防丢字）。 */
    private suspend fun rebindNote(newId: Long) {
        saveWindowNow()
        syncJob?.cancel()
        autoSave?.cancelPending()
        currentNoteId = newId
        settings.setLastNoteId(newId)
        windowView?.restoreEditable()
        windowView?.setNoteContent("")
        autoSave = AutoSaveManager<String>(scope) { content ->
            scope.launch { app.noteRepository.updateContent(currentNoteId, content) }
        }
        observeNoteSync()
    }

    // ---- 前台通知 ----

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "随记服务", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("随记运行中")
            .setSmallIcon(R.drawable.ic_note)
            .setOngoing(true)
            .build()
        // Android 14+ 的 specialUse 类型必须显式传入（计划 11.3）
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** 同进程内供主界面读取的运行状态（拍板决策：单进程，无需跨进程通信）。 */
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "floating_note_service"
        private const val NOTIFICATION_ID = 1001
    }
}
