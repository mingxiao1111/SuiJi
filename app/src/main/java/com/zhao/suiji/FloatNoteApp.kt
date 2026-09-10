package com.zhao.suiji

import android.app.Application
import com.zhao.suiji.data.NoteDatabase
import com.zhao.suiji.data.NoteRepository
import com.zhao.suiji.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App 级单例容器：主界面与悬浮窗 Service 共用同一套 Repository，
 * 这是"改一处两边实时同步"的前提（计划 7.8）。
 */
class FloatNoteApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: NoteDatabase by lazy { NoteDatabase.getInstance(this) }

    val noteRepository: NoteRepository by lazy { NoteRepository(database.noteDao()) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    /** 敏感值（AI API Key）加密存储，与普通设置分离。 */
    val secretStore: com.zhao.suiji.data.SecretStore by lazy { com.zhao.suiji.data.SecretStore(this) }

    override fun onCreate() {
        super.onCreate()
        // 回收站过期清除（30 天保留期）放启动时机，失败静默——下次启动还会再清
        appScope.launch { runCatching { noteRepository.purgeExpiredTrash() } }
    }
}
