package com.zhao.suiji.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 应用主题（v1.0.1：简洁黑白色系，用户拍板弃用蓝色）。
 * 主色 = 近黑/近白中性色，主界面与悬浮窗同一色彩语言；
 * 页面底色浅灰 + 白色卡片承托列表立体感（灰底白卡风格）。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B1C1F),          // 近黑：按钮/FAB/滑条/开关主色
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E9EB), // 浅灰容器（选中态底）
    onPrimaryContainer = Color(0xFF2A2A2A),
    secondary = Color(0xFF5F6470),        // 次级也是中性灰（防 M3 默认紫漏出）
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E9EB),
    onSecondaryContainer = Color(0xFF2A2A2A),
    tertiary = Color(0xFF5F6470),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9E9EB),
    onTertiaryContainer = Color(0xFF2A2A2A),
    background = Color(0xFFF4F4F5),
    onBackground = Color(0xFF1B1C1F),
    surface = Color(0xFFF4F4F5),          // 页面浅灰底，白卡片浮于其上
    onSurface = Color(0xFF1B1C1F),
    surfaceVariant = Color(0xFFE9E9EB),   // 滑条未选中轨道等（默认值是淡紫，必须覆盖）
    onSurfaceVariant = Color(0xFF5F6470),
    surfaceContainer = Color(0xFFFAFAFB), // 抽屉卡片底
    outline = Color(0xFFD4D4D8),          // 发丝级描边（卡片边框）
    outlineVariant = Color(0xFFE4E4E7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF4F4F5),          // 近白
    onPrimary = Color(0xFF1B1B1B),
    primaryContainer = Color(0xFF2C2C30),
    onPrimaryContainer = Color(0xFFE8E8EA),
    secondary = Color(0xFFA8A8AD),
    onSecondary = Color(0xFF1B1B1B),
    secondaryContainer = Color(0xFF2C2C30),
    onSecondaryContainer = Color(0xFFE8E8EA),
    tertiary = Color(0xFFA8A8AD),
    onTertiary = Color(0xFF1B1B1B),
    tertiaryContainer = Color(0xFF2C2C30),
    onTertiaryContainer = Color(0xFFE8E8EA),
    background = Color(0xFF131316),
    onBackground = Color(0xFFE6E6E8),
    surface = Color(0xFF131316),
    onSurface = Color(0xFFE6E6E8),
    surfaceVariant = Color(0xFF2C2C30),
    onSurfaceVariant = Color(0xFFA8A8AD),
    surfaceContainer = Color(0xFF1C1C20),
    outline = Color(0xFF2C2C30),
    outlineVariant = Color(0xFF262629),
)

@Composable
fun FloatNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
