package com.zhao.suiji

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.zhao.suiji.ui.SettingsScreen
import com.zhao.suiji.ui.theme.FloatNoteTheme

/** 设置页（计划 F7 / M5）。设置写入 DataStore 后由已订阅的 Service / 界面即时生效。 */
class SettingsActivity : ComponentActivity() {

    private val app get() = application as FloatNoteApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "1.0"
        setContent {
            FloatNoteTheme {
                SettingsScreen(
                    repo = app.settingsRepository,
                    noteRepo = app.noteRepository,
                    versionName = versionName,
                )
            }
        }
    }
}
