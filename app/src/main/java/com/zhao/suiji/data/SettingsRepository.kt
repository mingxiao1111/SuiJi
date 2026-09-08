package com.zhao.suiji.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "float_settings")

/** 工具栏按钮 id 常量（M4 的 ToolbarAction 枚举以此映射）。 */
object ToolbarButton {
    const val NEW_NOTE = "new_note"
    const val SWAP_SIDE = "swap_side"
    const val ORDERED_LIST = "ol"
    const val UNORDERED_LIST = "ul"
    const val TODO = "todo"
    const val INDENT = "indent"
    const val OUTDENT = "outdent"
    const val TIMESTAMP = "time"
    const val FONT_MINUS = "a_minus"
    const val FONT_PLUS = "a_plus"
    const val HOME = "home"

    /** 显示顺序即此列表顺序（计划 4.2）。 */
    val ALL = listOf(
        NEW_NOTE, SWAP_SIDE, ORDERED_LIST, UNORDERED_LIST, TODO,
        INDENT, OUTDENT, TIMESTAMP, FONT_MINUS, FONT_PLUS,
        HOME,
    )

    /** v2.0-a5 默认 7 个（用户拍板）：占满工具栏不滚动；其余项设置页可自行勾选。 */
    val DEFAULT_VISIBLE = setOf(
        NEW_NOTE, SWAP_SIDE, ORDERED_LIST, UNORDERED_LIST, TODO, TIMESTAMP, HOME,
    )
}

/** 悬浮体贴边侧别（v2.0 二期镜像系统）：左侧为默认历史行为。 */
enum class Side {
    LEFT, RIGHT;

    val isRight: Boolean get() = this == RIGHT

    /** 关于屏幕中线镜像翻转后的侧别。 */
    fun opposite(): Side = if (this == LEFT) RIGHT else LEFT

    companion object {
        fun fromValue(v: Int): Side = if (v == 1) RIGHT else LEFT
    }
}

/** 长条/竖条质感预设（v2.0 三期：玻璃质感变体，真机挑选后固化为出厂默认）。 */
enum class BarTexture(val label: String) {
    STANDARD("标准"), GLOSSY("高光"), SOFT("柔光");

    /** 受光/背光混合强度与高光描边浓度（渲染统一入口用）。 */
    val litFactor: Float get() = when (this) { GLOSSY -> 0.45f; SOFT -> 0.20f; else -> 0.30f }
    val shadeFactor: Float get() = when (this) { GLOSSY -> 0.25f; SOFT -> 0.12f; else -> 0.22f }
    val strokeAlpha: Int get() = when (this) { GLOSSY -> 0x73FFFFFF; SOFT -> 0x33FFFFFF; else -> 0x4DFFFFFF }

    companion object {
        fun fromValue(v: Int): BarTexture = entries.getOrElse(v) { STANDARD }
    }
}

/** 全部设置的一次性快照，悬浮服务启动 / 设置页展示用。 */
data class FloatSettings(
    // 悬浮球
    val ballX: Int,
    val ballY: Int,
    val ballSizeDp: Int,
    val ballStyle: Int,
    val ballColor: String,
    val ballAlpha: Float,
    val fadeOnEdge: Boolean,
    val fadeAlpha: Float,
    val stickWidthDp: Int, // 贴边竖条宽度（粗细）
    val stickHeightDp: Int, // 贴边竖条高度（大小）
    // 悬浮窗
    val windowWidth: Int,
    val windowHeight: Int,
    val windowX: Int,
    val windowHeightOffset: Int, // 见 WINDOW_Y 注释
    val windowOpacity: Float,
    val fixedPosition: Boolean,
    val autoCollapse: Boolean,
    val autoCollapseDelayMs: Long,
    // 输入与格式
    val fontSizeSp: Int,
    val toolbarButtons: Set<String>,
    val toolbarPosition: Int, // 0=窗口上方 1=下方
    val toolbarScale: Float,
    val toolbarOpacity: Float,
    val olStyle: Int,
    val ulStyle: Int,
    val showResizeHandle: Boolean,
    val hapticFeedback: Boolean, // 拖入收起目标区/收起完成的振动反馈
    // 悬浮窗长条手柄（v2.0：替代球形把手；颜色/透明度/渐变沿用原把手 key）
    val handleColor: String,
    val handleAlpha: Float,
    val handleGradient: Float, // 渐变强度 0=纯色 1=全渐变（玻璃感渐变黑）
    val barThicknessDp: Int, // 长条厚度（用户拍板默认 27）
    val barLengthRatioPct: Int, // 长条可见高度占窗高 %（视觉上比窗小一圈）
    val barCornerDp: Int, // 长条圆角（v2.0 三期定稿默认 13dp；渲染时 clamp 到半厚防胶囊）
    val barTexture: Int, // 质感预设（BarTexture：0标准 1高光 2柔光）
    // 窗口行为
    val windowEdgeMarginDp: Int, // 窗口距屏幕左右边缘的最小边距
    val side: Int, // 贴边侧别：0=左 1=右（v2.0 二期，映射 Side）
    // 其他
    val lastNoteId: Long,
)

/**
 * 设置仓库（DataStore Preferences，计划 5.2 全部 key）。
 * 约定：所有位置 / 尺寸存 dp、字号存 sp，展示层经 ScreenUtils 转 px（计划 11.12）。
 */
class SettingsRepository(private val context: Context) {

    private val store get() = context.dataStore

    // ---- 悬浮球 ----

    // -1 是哨兵值：从未拖动过，消费者按"屏幕右边缘垂直居中"计算初始位置
    val ballX: Flow<Int> = store.data.map { it[BALL_X] ?: -1 }
    val ballY: Flow<Int> = store.data.map { it[BALL_Y] ?: -1 }
    val ballSizeDp: Flow<Int> = store.data.map { it[BALL_SIZE_DP] ?: DEFAULT_BALL_SIZE_DP }
    val ballStyle: Flow<Int> = store.data.map { it[BALL_STYLE] ?: 0 }
    val ballColor: Flow<String> = store.data.map { migrateBallColor(it[BALL_COLOR]) }
    val ballAlpha: Flow<Float> = store.data.map { it[BALL_ALPHA] ?: 1.0f }
    val fadeOnEdge: Flow<Boolean> = store.data.map { it[FADE_ON_EDGE] ?: true }
    val fadeAlpha: Flow<Float> = store.data.map { it[FADE_ALPHA] ?: DEFAULT_FADE_ALPHA }
    val stickWidthDp: Flow<Int> = store.data.map { it[STICK_WIDTH_DP] ?: DEFAULT_STICK_WIDTH_DP }
    val stickHeightDp: Flow<Int> = store.data.map { it[STICK_HEIGHT_DP] ?: DEFAULT_STICK_HEIGHT_DP }

    // ---- 悬浮窗 ----

    val windowWidth: Flow<Int> = store.data.map { it[WINDOW_WIDTH] ?: DEFAULT_WINDOW_WIDTH }
    val windowHeight: Flow<Int> = store.data.map { it[WINDOW_HEIGHT] ?: DEFAULT_WINDOW_HEIGHT }
    val windowX: Flow<Int> = store.data.map { it[WINDOW_X] ?: -1 }
    val windowHeightOffset: Flow<Int> = store.data.map { it[WINDOW_Y] ?: -1 }
    val windowOpacity: Flow<Float> = store.data.map { it[WINDOW_OPACITY] ?: 1.0f }
    val fixedPosition: Flow<Boolean> = store.data.map { it[FIXED_POSITION] ?: false }
    val autoCollapse: Flow<Boolean> = store.data.map { it[AUTO_COLLAPSE] ?: false }
    val autoCollapseDelayMs: Flow<Long> = store.data.map { it[AUTO_COLLAPSE_DELAY_MS] ?: DEFAULT_COLLAPSE_DELAY_MS }

    // ---- 输入与格式 ----

    val fontSizeSp: Flow<Int> = store.data.map { it[FONT_SIZE_SP] ?: DEFAULT_FONT_SIZE_SP }
    val toolbarButtons: Flow<Set<String>> = store.data.map { it[TOOLBAR_BUTTONS] ?: ToolbarButton.DEFAULT_VISIBLE }
    val olStyle: Flow<Int> = store.data.map { it[OL_STYLE] ?: 0 }
    val ulStyle: Flow<Int> = store.data.map { it[UL_STYLE] ?: 0 }

    // ---- 工具栏（并入窗口，M5.2）----

    val toolbarScale: Flow<Float> = store.data.map { it[TOOLBAR_SCALE] ?: 1.0f }
    val toolbarOpacity: Flow<Float> = store.data.map { it[TOOLBAR_OPACITY] ?: DEFAULT_TOOLBAR_OPACITY }
    val toolbarPosition: Flow<Int> = store.data.map { it[TOOLBAR_POSITION] ?: 0 }
    val showResizeHandle: Flow<Boolean> = store.data.map { it[SHOW_RESIZE_HANDLE] ?: true }
    val hapticFeedback: Flow<Boolean> = store.data.map { it[HAPTIC_FEEDBACK] ?: true }

    // ---- 悬浮窗长条手柄（v2.0：颜色/透明度/渐变沿用原把手 key，尺寸换新 key）----

    val handleColor: Flow<String> = store.data.map { it[HANDLE_COLOR] ?: DEFAULT_HANDLE_COLOR }
    val handleAlpha: Flow<Float> = store.data.map { it[HANDLE_ALPHA] ?: DEFAULT_HANDLE_ALPHA }
    val handleGradient: Flow<Float> = store.data.map { it[HANDLE_GRADIENT] ?: 1.0f }
    val barThicknessDp: Flow<Int> = store.data.map { it[BAR_THICKNESS_DP] ?: DEFAULT_BAR_THICKNESS_DP }
    val barLengthRatioPct: Flow<Int> = store.data.map { it[BAR_LENGTH_RATIO_PCT] ?: DEFAULT_BAR_LENGTH_RATIO_PCT }
    val barCornerDp: Flow<Int> = store.data.map { it[BAR_CORNER_DP] ?: DEFAULT_BAR_CORNER_DP }
    val barTexture: Flow<Int> = store.data.map { it[BAR_TEXTURE] ?: DEFAULT_BAR_TEXTURE }

    // ---- 窗口行为 ----

    val windowEdgeMarginDp: Flow<Int> = store.data.map { it[WINDOW_EDGE_MARGIN_DP] ?: DEFAULT_WINDOW_EDGE_MARGIN_DP }

    /** 贴边侧别（v2.0 二期）：设置页选项与工具栏换位键等效。 */
    val side: Flow<Int> = store.data.map { it[SIDE] ?: 0 }

    // ---- 启动行为（v1.2）----

    /** 开机自启悬浮窗：BootCompletedReceiver 据此决定是否拉起服务。 */
    val bootAutoStart: Flow<Boolean> = store.data.map { it[BOOT_AUTO_START] ?: false }

    // ---- 其他 ----

    val lastNoteId: Flow<Long> = store.data.map { it[LAST_NOTE_ID] ?: 0L }

    /** 一次性读取全部设置（服务启动 / 调试用）。 */
    suspend fun snapshot(): FloatSettings {
        val p = store.data.first()
        return FloatSettings(
            ballX = p[BALL_X] ?: -1,
            ballY = p[BALL_Y] ?: -1,
            ballSizeDp = p[BALL_SIZE_DP] ?: DEFAULT_BALL_SIZE_DP,
            ballStyle = p[BALL_STYLE] ?: 0,
            ballColor = migrateBallColor(p[BALL_COLOR]),
            ballAlpha = p[BALL_ALPHA] ?: 1.0f,
            fadeOnEdge = p[FADE_ON_EDGE] ?: true,
            fadeAlpha = p[FADE_ALPHA] ?: DEFAULT_FADE_ALPHA,
            stickWidthDp = p[STICK_WIDTH_DP] ?: DEFAULT_STICK_WIDTH_DP,
            stickHeightDp = p[STICK_HEIGHT_DP] ?: DEFAULT_STICK_HEIGHT_DP,
            windowWidth = p[WINDOW_WIDTH] ?: DEFAULT_WINDOW_WIDTH,
            windowHeight = p[WINDOW_HEIGHT] ?: DEFAULT_WINDOW_HEIGHT,
            windowX = p[WINDOW_X] ?: -1,
            windowHeightOffset = p[WINDOW_Y] ?: -1,
            windowOpacity = p[WINDOW_OPACITY] ?: 1.0f,
            fixedPosition = p[FIXED_POSITION] ?: false,
            autoCollapse = p[AUTO_COLLAPSE] ?: false,
            autoCollapseDelayMs = p[AUTO_COLLAPSE_DELAY_MS] ?: DEFAULT_COLLAPSE_DELAY_MS,
            fontSizeSp = p[FONT_SIZE_SP] ?: DEFAULT_FONT_SIZE_SP,
            toolbarButtons = p[TOOLBAR_BUTTONS] ?: ToolbarButton.DEFAULT_VISIBLE,
            toolbarPosition = p[TOOLBAR_POSITION] ?: 0,
            toolbarScale = p[TOOLBAR_SCALE] ?: 1.0f,
            toolbarOpacity = p[TOOLBAR_OPACITY] ?: DEFAULT_TOOLBAR_OPACITY,
            olStyle = p[OL_STYLE] ?: 0,
            ulStyle = p[UL_STYLE] ?: 0,
            showResizeHandle = p[SHOW_RESIZE_HANDLE] ?: true,
            hapticFeedback = p[HAPTIC_FEEDBACK] ?: true,
            handleColor = p[HANDLE_COLOR] ?: DEFAULT_HANDLE_COLOR,
            handleAlpha = p[HANDLE_ALPHA] ?: DEFAULT_HANDLE_ALPHA,
            handleGradient = p[HANDLE_GRADIENT] ?: 1.0f,
            barThicknessDp = p[BAR_THICKNESS_DP] ?: DEFAULT_BAR_THICKNESS_DP,
            barLengthRatioPct = p[BAR_LENGTH_RATIO_PCT] ?: DEFAULT_BAR_LENGTH_RATIO_PCT,
            barCornerDp = p[BAR_CORNER_DP] ?: DEFAULT_BAR_CORNER_DP,
            barTexture = p[BAR_TEXTURE] ?: DEFAULT_BAR_TEXTURE,
            windowEdgeMarginDp = p[WINDOW_EDGE_MARGIN_DP] ?: DEFAULT_WINDOW_EDGE_MARGIN_DP,
            side = p[SIDE] ?: 0,
            lastNoteId = p[LAST_NOTE_ID] ?: 0L,
        )
    }

    // ---- setters（均为 suspend，调用方自行切 IO 调度器）----

    suspend fun setBallPosition(xDp: Int, yDp: Int) = store.edit {
        it[BALL_X] = xDp
        it[BALL_Y] = yDp
    }

    suspend fun setBallSizeDp(sizeDp: Int) = store.edit { it[BALL_SIZE_DP] = sizeDp.coerceIn(36, 64) }

    suspend fun setBallStyle(style: Int) = store.edit { it[BALL_STYLE] = style }

    suspend fun setBallColor(colorHex: String) = store.edit { it[BALL_COLOR] = colorHex }

    suspend fun setBallAlpha(alpha: Float) = store.edit { it[BALL_ALPHA] = alpha.coerceIn(0.4f, 1.0f) }

    suspend fun setFadeOnEdge(enabled: Boolean) = store.edit { it[FADE_ON_EDGE] = enabled }

    suspend fun setFadeAlpha(alpha: Float) = store.edit { it[FADE_ALPHA] = alpha.coerceIn(0.05f, 0.9f) }

    suspend fun setStickWidthDp(widthDp: Int) = store.edit { it[STICK_WIDTH_DP] = widthDp.coerceIn(6, 40) }

    suspend fun setStickHeightDp(heightDp: Int) = store.edit { it[STICK_HEIGHT_DP] = heightDp.coerceIn(20, 120) }

    suspend fun setWindowSize(widthDp: Int, heightDp: Int) = store.edit {
        it[WINDOW_WIDTH] = widthDp
        it[WINDOW_HEIGHT] = heightDp
    }

    suspend fun setWindowPosition(xDp: Int, yDp: Int) = store.edit {
        it[WINDOW_X] = xDp
        it[WINDOW_Y] = yDp
    }

    suspend fun setWindowOpacity(opacity: Float) = store.edit { it[WINDOW_OPACITY] = opacity.coerceIn(0.3f, 1.0f) }

    suspend fun setFixedPosition(fixed: Boolean) = store.edit { it[FIXED_POSITION] = fixed }

    suspend fun setAutoCollapse(enabled: Boolean) = store.edit { it[AUTO_COLLAPSE] = enabled }

    suspend fun setAutoCollapseDelayMs(delayMs: Long) = store.edit { it[AUTO_COLLAPSE_DELAY_MS] = delayMs }

    suspend fun setFontSizeSp(sizeSp: Int) = store.edit { it[FONT_SIZE_SP] = sizeSp.coerceIn(10, 28) }

    suspend fun setToolbarButtons(buttons: Set<String>) = store.edit {
        // 只保留合法 id，避免脏数据
        it[TOOLBAR_BUTTONS] = buttons.filterTo(mutableSetOf()) { id -> id in ToolbarButton.ALL }
    }

    suspend fun setOlStyle(style: Int) = store.edit { it[OL_STYLE] = style.coerceIn(0, 2) }

    suspend fun setUlStyle(style: Int) = store.edit { it[UL_STYLE] = style.coerceIn(0, 2) }

    suspend fun setToolbarScale(scale: Float) = store.edit { it[TOOLBAR_SCALE] = scale.coerceIn(0.5f, 2.0f) }

    suspend fun setToolbarOpacity(opacity: Float) = store.edit { it[TOOLBAR_OPACITY] = opacity.coerceIn(0.2f, 1.0f) }

    suspend fun setToolbarPosition(position: Int) = store.edit { it[TOOLBAR_POSITION] = position.coerceIn(0, 1) }

    suspend fun setShowResizeHandle(show: Boolean) = store.edit { it[SHOW_RESIZE_HANDLE] = show }

    suspend fun setHapticFeedback(enabled: Boolean) = store.edit { it[HAPTIC_FEEDBACK] = enabled }

    suspend fun setHandleColor(colorHex: String) = store.edit { it[HANDLE_COLOR] = colorHex }

    suspend fun setHandleAlpha(alpha: Float) = store.edit { it[HANDLE_ALPHA] = alpha.coerceIn(0.2f, 1.0f) }

    suspend fun setHandleGradient(strength: Float) = store.edit { it[HANDLE_GRADIENT] = strength.coerceIn(0f, 1f) }

    suspend fun setBarThicknessDp(thicknessDp: Int) = store.edit { it[BAR_THICKNESS_DP] = thicknessDp.coerceIn(12, 28) }

    suspend fun setBarLengthRatioPct(ratioPct: Int) = store.edit { it[BAR_LENGTH_RATIO_PCT] = ratioPct.coerceIn(50, 95) }

    suspend fun setBarCornerDp(cornerDp: Int) = store.edit { it[BAR_CORNER_DP] = cornerDp.coerceIn(0, 16) }

    suspend fun setBarTexture(texture: BarTexture) = store.edit { it[BAR_TEXTURE] = texture.ordinal }

    suspend fun setWindowEdgeMarginDp(marginDp: Int) = store.edit { it[WINDOW_EDGE_MARGIN_DP] = marginDp.coerceIn(0, 24) }

    suspend fun setSide(side: Side) = store.edit { it[SIDE] = if (side.isRight) 1 else 0 }

    suspend fun setBootAutoStart(enabled: Boolean) = store.edit { it[BOOT_AUTO_START] = enabled }

    suspend fun setLastNoteId(noteId: Long) = store.edit { it[LAST_NOTE_ID] = noteId }

    companion object {
        private val BALL_X = intPreferencesKey("ball_x")
        private val BALL_Y = intPreferencesKey("ball_y")
        private val BALL_SIZE_DP = intPreferencesKey("ball_size_dp")
        private val BALL_STYLE = intPreferencesKey("ball_style")
        private val BALL_COLOR = stringPreferencesKey("ball_color")
        private val BALL_ALPHA = floatPreferencesKey("ball_alpha")
        private val STICK_WIDTH_DP = intPreferencesKey("stick_width_dp")
        private val STICK_HEIGHT_DP = intPreferencesKey("stick_height_dp")
        private val FRADE_ON_EDGE_KEY = booleanPreferencesKey("fade_on_edge")
        private val FADE_ALPHA = floatPreferencesKey("fade_alpha")

        private val WINDOW_WIDTH = intPreferencesKey("window_width")
        private val WINDOW_HEIGHT = intPreferencesKey("window_height")
        private val WINDOW_X = intPreferencesKey("window_x")
        private val WINDOW_Y = intPreferencesKey("window_y")
        private val WINDOW_OPACITY = floatPreferencesKey("window_opacity")
        private val FIXED_POSITION = booleanPreferencesKey("fixed_position")
        private val AUTO_COLLAPSE = booleanPreferencesKey("auto_collapse")
        private val AUTO_COLLAPSE_DELAY_MS = longPreferencesKey("auto_collapse_delay_ms")

        private val FONT_SIZE_SP = intPreferencesKey("font_size_sp")
        private val TOOLBAR_BUTTONS = stringSetPreferencesKey("toolbar_buttons")
        private val TOOLBAR_POSITION = intPreferencesKey("toolbar_position")
        private val OL_STYLE = intPreferencesKey("ol_style")
        private val UL_STYLE = intPreferencesKey("ul_style")

        private val TOOLBAR_SCALE = floatPreferencesKey("toolbar_scale")
        private val TOOLBAR_OPACITY = floatPreferencesKey("toolbar_opacity")
        private val SHOW_RESIZE_HANDLE = booleanPreferencesKey("show_resize_handle")
        private val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")

        private val HANDLE_COLOR = stringPreferencesKey("handle_color")
        private val HANDLE_ALPHA = floatPreferencesKey("handle_alpha")
        private val HANDLE_GRADIENT = floatPreferencesKey("handle_gradient")
        private val BAR_THICKNESS_DP = intPreferencesKey("bar_thickness_dp")
        private val BAR_LENGTH_RATIO_PCT = intPreferencesKey("bar_length_ratio_pct")
        private val BAR_CORNER_DP = intPreferencesKey("bar_corner_dp")
        private val BAR_TEXTURE = intPreferencesKey("bar_texture")

        private val WINDOW_EDGE_MARGIN_DP = intPreferencesKey("window_edge_margin_dp")
        private val SIDE = intPreferencesKey("side")
        private val BOOT_AUTO_START = booleanPreferencesKey("boot_auto_start")

        private val LAST_NOTE_ID = longPreferencesKey("last_note_id")

        const val DEFAULT_BALL_SIZE_DP = 48
        const val DEFAULT_BALL_COLOR = "#2E2E2E" // v1.0.1 黑白系：旧默认蓝 #4A90D9 读取时自动迁移
        private const val LEGACY_BALL_COLOR = "#4A90D9"
        const val DEFAULT_FADE_ALPHA = 0.30f
        const val DEFAULT_STICK_WIDTH_DP = 20
        const val DEFAULT_STICK_HEIGHT_DP = 50
        const val DEFAULT_TOOLBAR_OPACITY = 0.6f
        const val DEFAULT_HANDLE_COLOR = "#2E2E2E" // 用户调优出厂值：黑
        const val DEFAULT_HANDLE_ALPHA = 0.3f // a7.6 拍板：与竖条一致（原 0.7）
        const val DEFAULT_BAR_THICKNESS_DP = 27 // 用户拍板（2026-09-01 a3；原 18）
        const val DEFAULT_BAR_LENGTH_RATIO_PCT = 75 // 用户反馈 88 仍偏长（a3 收短；原 88）
        const val DEFAULT_BAR_CORNER_DP = 13 // 圆角（2026-09-02 三期定稿，原 10）
        const val DEFAULT_BAR_TEXTURE = 2 // 柔光（a7.6 拍板出厂默认；0标准 1高光 2柔光）
        const val DEFAULT_WINDOW_EDGE_MARGIN_DP = 0 // a7.6 拍板：贴边（原 6）
        // a7.7 拍板：宽=尽量大（600 远超各机型钳制上限，attach 时 coerce 到
        // min(屏宽×0.9, 屏宽-48dp 手势区-27dp 长条) 即该机实际最大）；高=原 420 的一半
        const val DEFAULT_WINDOW_WIDTH = 600
        const val DEFAULT_WINDOW_HEIGHT = 210
        const val DEFAULT_COLLAPSE_DELAY_MS = 30_000L
        const val DEFAULT_FONT_SIZE_SP = 15

        // FRADE_ON_EDGE_KEY 名字打错了，但作为存储 key 一旦发布不可再改；此处保持一致
        private val FADE_ON_EDGE = FRADE_ON_EDGE_KEY

        /** 旧默认蓝（v1.0 出厂值）视为未设置，迁移到黑白系新默认；用户自定义色不受影响。 */
        private fun migrateBallColor(stored: String?): String =
            when (stored) {
                null, LEGACY_BALL_COLOR -> DEFAULT_BALL_COLOR
                else -> stored
            }
    }
}
