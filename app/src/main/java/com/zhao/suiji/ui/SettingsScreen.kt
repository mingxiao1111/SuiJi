package com.zhao.suiji.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhao.suiji.ai.AiConfig
import com.zhao.suiji.ai.AiConnectionTester
import com.zhao.suiji.ai.TestResult
import com.zhao.suiji.data.NoteRepository
import com.zhao.suiji.data.SecretStore
import com.zhao.suiji.data.SettingsRepository
import com.zhao.suiji.data.ToolbarButton
import com.zhao.suiji.view.ToolbarAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 悬浮体六色预设（v1.0.1 黑白系，弃用彩色）。 */
private val BALL_COLORS = listOf(
    "#111111", "#2E2E2E", "#6E6E73", "#A8A8AD", "#E8E8EA", "#FFFFFF",
)
private val TOOLBAR_POSITIONS = listOf("窗口上方", "窗口下方")
private val SIDE_OPTIONS = listOf("贴左边缘", "贴右边缘")
private val TEXTURE_OPTIONS = com.zhao.suiji.data.BarTexture.entries.map { it.label }
private val COLLAPSE_DELAYS = listOf(
    5000L to "5 秒", 10000L to "10 秒", 30000L to "30 秒", 60000L to "1 分钟", 0L to "永不",
)
private val OL_STYLES = listOf("1. ", "1、", "① ")
private val UL_STYLES = listOf("• ", "- ", "· ")

/** 设置页（计划 F7 / M5）：四组设置全部改动即时生效，重启后保持。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: SettingsRepository,
    noteRepo: NoteRepository,
    secretStore: SecretStore,
    versionName: String,
    focusAi: Boolean = false,
) {
    val scope = rememberCoroutineScope()

    val ballColor by repo.ballColor.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_BALL_COLOR)
    val fadeAlpha by repo.fadeAlpha.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_FADE_ALPHA)
    val stickWidthDp by repo.stickWidthDp.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_STICK_WIDTH_DP)
    val stickHeightDp by repo.stickHeightDp.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_STICK_HEIGHT_DP)

    val windowOpacity by repo.windowOpacity.collectAsStateWithLifecycle(initialValue = 1f)
    val showResizeHandle by repo.showResizeHandle.collectAsStateWithLifecycle(initialValue = true)
    val hapticFeedback by repo.hapticFeedback.collectAsStateWithLifecycle(initialValue = true)
    val handleColor by repo.handleColor.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_HANDLE_COLOR)
    val handleAlpha by repo.handleAlpha.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_HANDLE_ALPHA)
    val handleGradient by repo.handleGradient.collectAsStateWithLifecycle(initialValue = 1f)
    val barThicknessDp by repo.barThicknessDp.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_BAR_THICKNESS_DP)
    val barLengthRatioPct by repo.barLengthRatioPct.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_BAR_LENGTH_RATIO_PCT)
    val barCornerDp by repo.barCornerDp.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_BAR_CORNER_DP)
    val barTexture by repo.barTexture.collectAsStateWithLifecycle(initialValue = 0)
    val windowEdgeMarginDp by repo.windowEdgeMarginDp.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_WINDOW_EDGE_MARGIN_DP)
    val side by repo.side.collectAsStateWithLifecycle(initialValue = 0)
    val fixedPosition by repo.fixedPosition.collectAsStateWithLifecycle(initialValue = false)
    val autoCollapse by repo.autoCollapse.collectAsStateWithLifecycle(initialValue = false)
    val autoCollapseDelayMs by repo.autoCollapseDelayMs.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_COLLAPSE_DELAY_MS)
    val bootAutoStart by repo.bootAutoStart.collectAsStateWithLifecycle(initialValue = false)

    val fontSizeSp by repo.fontSizeSp.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_FONT_SIZE_SP)
    val toolbarButtons by repo.toolbarButtons.collectAsStateWithLifecycle(initialValue = ToolbarButton.DEFAULT_VISIBLE)
    val olStyle by repo.olStyle.collectAsStateWithLifecycle(initialValue = 0)
    val ulStyle by repo.ulStyle.collectAsStateWithLifecycle(initialValue = 0)
    val toolbarScale by repo.toolbarScale.collectAsStateWithLifecycle(initialValue = 1f)
    val toolbarOpacity by repo.toolbarOpacity.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_TOOLBAR_OPACITY)
    val toolbarPosition by repo.toolbarPosition.collectAsStateWithLifecycle(initialValue = 0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup("贴边竖条") {
                ColorRow(title = "颜色", current = ballColor) {
                    scope.launch { repo.setBallColor(it) }
                }
                SliderRow(
                    title = "透明度",
                    value = fadeAlpha,
                    min = 0.05f, max = 0.9f, steps = 16, // 5% 一档
                    display = "%.0f%%".format(fadeAlpha * 100),
                ) { scope.launch { repo.setFadeAlpha(it) } }
                SliderRow(
                    title = "粗细（宽度）",
                    value = stickWidthDp.toFloat(),
                    min = 6f, max = 40f, steps = 33, // 1dp 一档
                    display = "$stickWidthDp dp",
                ) { scope.launch { repo.setStickWidthDp(it.toInt()) } }
                SliderRow(
                    title = "大小（高度）",
                    value = stickHeightDp.toFloat(),
                    min = 20f, max = 120f, steps = 99,
                    display = "$stickHeightDp dp",
                ) { scope.launch { repo.setStickHeightDp(it.toInt()) } }
            }

            SettingsGroup("悬浮窗长条手柄") {
                ColorRow(title = "颜色", current = handleColor) {
                    scope.launch { repo.setHandleColor(it) }
                }
                SliderRow(
                    title = "不透明度",
                    value = handleAlpha,
                    min = 0.2f, max = 1f, steps = 15, // 5% 一档
                    display = "%.0f%%".format(handleAlpha * 100),
                ) { scope.launch { repo.setHandleAlpha(it) } }
                SliderRow(
                    title = "渐变强度（0=纯色）",
                    value = handleGradient,
                    min = 0f, max = 1f, steps = 19,
                    display = "%.0f%%".format(handleGradient * 100),
                ) { scope.launch { repo.setHandleGradient(it) } }
                SliderRow(
                    title = "厚度",
                    value = barThicknessDp.toFloat(),
                    min = 12f, max = 28f, steps = 15, // 1dp 一档
                    display = "$barThicknessDp dp",
                ) { scope.launch { repo.setBarThicknessDp(it.toInt()) } }
                SliderRow(
                    title = "长度（占窗高）",
                    value = barLengthRatioPct.toFloat(),
                    min = 50f, max = 95f, steps = 8, // 5% 一档
                    display = "$barLengthRatioPct%",
                ) { scope.launch { repo.setBarLengthRatioPct(it.toInt()) } }
                SliderRow(
                    title = "圆角",
                    value = barCornerDp.toFloat(),
                    min = 0f, max = 16f, steps = 15, // 1dp 一档（渲染时自动防超过半厚）
                    display = "$barCornerDp dp",
                ) { scope.launch { repo.setBarCornerDp(it.toInt()) } }
                ChoiceRow(
                    title = "质感（长条与竖条同步）",
                    current = TEXTURE_OPTIONS.getOrElse(barTexture) { "标准" },
                    options = TEXTURE_OPTIONS,
                ) { idx ->
                    scope.launch {
                        repo.setBarTexture(com.zhao.suiji.data.BarTexture.entries[idx])
                    }
                }
                Text(
                    "长条：轻点收起 · 按住拖动 · 工具栏 ⇄ 键立即左右换边",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            SettingsGroup("收起行为") {
                SwitchRow("振动反馈（长条按压/收起完成）", hapticFeedback) {
                    scope.launch { repo.setHapticFeedback(it) }
                }
            }

            SettingsGroup("悬浮窗") {
                SwitchRow("开机自启悬浮窗", bootAutoStart) {
                    scope.launch { repo.setBootAutoStart(it) }
                }
                ChoiceRow(
                    title = "贴边方向",
                    current = SIDE_OPTIONS.getOrElse(side) { "贴左边缘" },
                    options = SIDE_OPTIONS,
                ) { idx ->
                    scope.launch { repo.setSide(com.zhao.suiji.data.Side.fromValue(idx)) }
                }
                SliderRow(
                    title = "背景不透明度",
                    value = windowOpacity,
                    min = 0.3f, max = 1f, steps = 13, // 5% 一档
                    display = "%.0f%%".format(windowOpacity * 100),
                ) { scope.launch { repo.setWindowOpacity(it) } }
                SwitchRow("显示缩放手柄", showResizeHandle) {
                    scope.launch { repo.setShowResizeHandle(it) }
                }
                SliderRow(
                    title = "距屏幕边缘边距",
                    value = windowEdgeMarginDp.toFloat(),
                    min = 0f, max = 24f, steps = 23,
                    display = "$windowEdgeMarginDp dp",
                ) { scope.launch { repo.setWindowEdgeMarginDp(it.toInt()) } }
                SwitchRow("固定位置（不可拖动）", fixedPosition) {
                    scope.launch { repo.setFixedPosition(it) }
                }
                SwitchRow("无操作自动收起", autoCollapse) {
                    scope.launch { repo.setAutoCollapse(it) }
                }
                if (autoCollapse) {
                    ChoiceRow(
                        title = "自动收起时长",
                        current = COLLAPSE_DELAYS.firstOrNull { it.first == autoCollapseDelayMs }?.second ?: "30 秒",
                        options = COLLAPSE_DELAYS.map { it.second },
                    ) { idx ->
                        scope.launch { repo.setAutoCollapseDelayMs(COLLAPSE_DELAYS[idx].first) }
                    }
                }
            }

            SettingsGroup("输入与格式") {
                SliderRow(
                    title = "正文字号",
                    value = fontSizeSp.toFloat(),
                    min = 10f, max = 28f, steps = 17, // 1sp 一档
                    display = "$fontSizeSp sp",
                ) { scope.launch { repo.setFontSizeSp(it.toInt()) } }
                ChoiceRow(
                    title = "有序分点样式",
                    current = OL_STYLES.getOrElse(olStyle) { "1. " },
                    options = OL_STYLES,
                ) { scope.launch { repo.setOlStyle(it) } }
                ChoiceRow(
                    title = "无序分点样式",
                    current = UL_STYLES.getOrElse(ulStyle) { "• " },
                    options = UL_STYLES,
                ) { scope.launch { repo.setUlStyle(it) } }
                Text(
                    "工具栏",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ChoiceRow(
                    title = "工具栏位置",
                    current = TOOLBAR_POSITIONS.getOrElse(toolbarPosition) { "窗口上方" },
                    options = TOOLBAR_POSITIONS,
                ) { idx ->
                    scope.launch { repo.setToolbarPosition(idx) }
                }
                SliderRow(
                    title = "工具栏大小",
                    value = toolbarScale,
                    min = 0.5f, max = 2f, steps = 29, // 5% 一档
                    display = "%.0f%%".format(toolbarScale * 100),
                ) { scope.launch { repo.setToolbarScale(it) } }
                SliderRow(
                    title = "工具栏透明度",
                    value = toolbarOpacity,
                    min = 0.2f, max = 1f, steps = 15,
                    display = "%.0f%%".format(toolbarOpacity * 100),
                ) { scope.launch { repo.setToolbarOpacity(it) } }
                ToolbarButton.ALL.forEach { id ->
                    val action = ToolbarAction.fromId(id)
                    val checked = id in toolbarButtons
                    SwitchRow(
                        title = action?.label ?: id,
                        checked = checked,
                    ) { on ->
                        scope.launch {
                            repo.setToolbarButtons(
                                if (on) toolbarButtons + id else toolbarButtons - id,
                            )
                        }
                    }
                }
            }

            SettingsGroup("AI 助手", expandedInitially = focusAi) {
                AiSection(repo = repo, secretStore = secretStore, scope = scope)
            }

            SettingsGroup("备份", collapsible = false) {
                BackupSection(noteRepo = noteRepo, scope = scope)
            }

            SettingsGroup("重置", collapsible = false) {
                ActionRow("重置悬浮窗位置与大小") {
                    scope.launch {
                        repo.setWindowPosition(-1, -1)
                        repo.setWindowSize(SettingsRepository.DEFAULT_WINDOW_WIDTH, SettingsRepository.DEFAULT_WINDOW_HEIGHT)
                    }
                }
                ActionRow("竖条回到默认高度") {
                    scope.launch { repo.setBallPosition(0, -1) }
                }
            }

            SettingsGroup("关于", collapsible = false) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("浮记", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "v$versionName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "本地离线笔记 · 所有设置即时生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---- 通用组件 ----

/**
 * 设置分区（v1.0.1 抽屉化）：独立圆角卡片 + 可折叠内容，默认收起，
 * 页面呈清爽的分组索引；[collapsible]=false 用于"重置/关于"等常显组。
 */
@Composable
private fun SettingsGroup(
    title: String,
    collapsible: Boolean = true,
    expandedInitially: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(!collapsible || expandedInitially) }
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (collapsible) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.rotate(chevron),
                    )
                }
            }
            if (expanded) {
                Column(Modifier.padding(bottom = 8.dp)) { content() }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    min: Float,
    max: Float,
    steps: Int,
    display: String,
    onChange: (Float) -> Unit,
) {
    // v2.0-a3 用户拍板：滑条不显示刻度点（太丑）——M3 Slider 的刻度随 steps>0 出现，
    // 这里 steps 传 0 改为在回调里量化取档，离散步进保留、视觉干净，跨档时轻振动反馈
    val haptic = LocalHapticFeedback.current
    val step = if (steps > 0) (max - min) / (steps + 1) else 0f
    val snap: (Float) -> Float = { v ->
        if (step > 0f) (min + ((v - min) / step).let { kotlin.math.round(it) } * step) else v
    }
    var lastSnapped by remember(title) { mutableStateOf(snap(value)) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(display, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = snap(value).coerceIn(min, max),
            onValueChange = { v ->
                val snapped = snap(v)
                if (snapped != lastSnapped) {
                    lastSnapped = snapped
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                onChange(snapped)
            },
            valueRange = min..max,
            steps = 0, // 刻度点隐藏，步进由上面的 snap 保证
        )
    }
}

@Composable
private fun ChoiceRow(title: String, current: String, options: List<String>, onSelect: (Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { show = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(current, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(title) },
            text = {
                Column {
                    options.forEachIndexed { idx, option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    show = false
                                    onSelect(idx)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == current, onClick = null)
                            Text(option, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { show = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ColorRow(title: String, current: String, onSelect: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { show = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    runCatching { Color(android.graphics.Color.parseColor(current)) }
                        .getOrDefault(Color.Gray),
                    CircleShape,
                ),
        )
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(title) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BALL_COLORS.forEach { hex ->
                        val c = runCatching { Color(android.graphics.Color.parseColor(hex)) }
                            .getOrDefault(Color.Gray)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(c, CircleShape)
                                .clickable {
                                    show = false
                                    onSelect(hex)
                                },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { show = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ActionRow(title: String, onClick: () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

/**
 * AI 助手配置（v2.1）：OpenAI 兼容接口 + Key（加密存 SecretStore）+ 三个模型。
 * Key 不走 DataStore Flow，本地状态直读直写；其余项与其他设置一样即时保存。
 */
@Composable
private fun AiSection(
    repo: SettingsRepository,
    secretStore: SecretStore,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val aiBaseUrl by repo.aiBaseUrl.collectAsStateWithLifecycle(initialValue = "")
    val aiChatModel by repo.aiChatModel.collectAsStateWithLifecycle(initialValue = "")
    val aiThinkModel by repo.aiThinkModel.collectAsStateWithLifecycle(initialValue = "")
    val aiVisionModel by repo.aiVisionModel.collectAsStateWithLifecycle(initialValue = "")
    var apiKey by remember { mutableStateOf(secretStore.getApiKey()) }
    var testing by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }
    val aiEnabled by repo.aiAssistantEnabled.collectAsStateWithLifecycle(initialValue = true)
    val aiOrbAlpha by repo.aiOrbAlpha.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_AI_ORB_ALPHA)

    SwitchRow("悬浮 AI 按钮（左下角）", aiEnabled) {
        scope.launch { repo.setAiAssistantEnabled(it) }
    }
    SliderRow(
        title = "按钮不透明度",
        value = aiOrbAlpha,
        min = 0.15f, max = 0.9f, steps = 14, // 5% 一档
        display = "%.0f%%".format(aiOrbAlpha * 100),
    ) { scope.launch { repo.setAiOrbAlpha(it) } }

    TextRow("接口地址（OpenAI 兼容）", aiBaseUrl, "https://api.deepseek.com") {
        scope.launch { repo.setAiBaseUrl(it) }
    }
    TextRow("API Key（加密存本机）", apiKey, "sk-…", password = true) { v ->
        apiKey = v
        secretStore.setApiKey(v)
    }
    TextRow("普通模型（必填）", aiChatModel, "deepseek-chat") {
        scope.launch { repo.setAiChatModel(it) }
    }
    TextRow("思考模型（选填）", aiThinkModel, "悬浮窗思考开关开启时改用") {
        scope.launch { repo.setAiThinkModel(it) }
    }
    TextRow("视觉模型（选填）", aiVisionModel, "发送图片时改用") {
        scope.launch { repo.setAiVisionModel(it) }
    }
    ActionRow(if (testing) "测试连接中…" else "测试连接") {
        if (testing) return@ActionRow
        testing = true
        resultText = null
        scope.launch {
            val result = AiConnectionTester.test(
                AiConfig(aiBaseUrl, apiKey, aiChatModel, aiThinkModel, aiVisionModel),
            )
            var ok = false
            resultText = when (result) {
                is TestResult.Success -> {
                    ok = true
                    buildString {
                        append("连接成功（${result.latencyMs}ms）")
                        if (result.modelIds.isNotEmpty()) {
                            append(" · 服务商返回 ${result.modelIds.size} 个模型")
                            if (aiChatModel.isNotBlank() && aiChatModel !in result.modelIds) {
                                append("\n注意：「普通模型」不在返回列表中")
                            }
                        }
                    }
                }
                is TestResult.AuthError -> "Key 无效或无权限：${result.detail}"
                is TestResult.HttpError -> "接口返回错误：${result.detail}"
                is TestResult.NetworkError -> "网络错误：${result.detail}（检查地址与网络）"
            }
            resultOk = ok
            testing = false
        }
    }
    if (resultText != null) {
        Text(
            resultText!!,
            style = MaterialTheme.typography.bodySmall,
            color = if (resultOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    Text(
        "笔记数据仍只存本机；仅在你主动使用 AI 时访问以上接口。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun TextRow(
    title: String,
    value: String,
    placeholder: String,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        var visible by remember(title) { mutableStateOf(!password) }
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            placeholder = { Text(placeholder) },
            singleLine = true,
            visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (password) {
                {
                    TextButton(onClick = { visible = !visible }) {
                        Text(if (visible) "隐藏" else "显示")
                    }
                }
            } else null,
        )
    }
}

/**
 * 备份（v1.1）：SAF 文档选择器导出/导入，全部版本免存储权限。
 * 导出格式见 BackupFormat——人可读，也能被"从文件导入"原样导回。
 */
@Composable
private fun BackupSection(noteRepo: NoteRepository, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.bufferedWriter(Charsets.UTF_8).use { it.write(noteRepo.buildBackupText()) }
                    } != null
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) "已导出" else "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?.let { noteRepo.importBackupText(it) }
                }.getOrNull()
            }
            val message = when {
                result == null -> "不是有效的浮记备份文件"
                result.second > 0 -> "导入 ${result.first} 条，跳过重复 ${result.second} 条"
                else -> "导入 ${result.first} 条"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    ActionRow("导出全部笔记（文本）") {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
        exportLauncher.launch("浮记备份-$stamp.txt")
    }
    ActionRow("从文件导入备份") {
        importLauncher.launch(arrayOf("text/plain"))
    }
}
