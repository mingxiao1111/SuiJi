package com.zhao.suiji.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhao.suiji.FloatNoteApp
import com.zhao.suiji.data.Note
import com.zhao.suiji.data.NoteRepository
import com.zhao.suiji.data.SettingsRepository
import com.zhao.suiji.manager.AutoSaveManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** 主界面共用的 ViewModel 工厂：统一从 FloatNoteApp 取 Repository。 */
val FloatNoteVMFactory: ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[APPLICATION_KEY] as FloatNoteApp
        NoteListViewModel(app.noteRepository)
    }
    initializer {
        val app = this[APPLICATION_KEY] as FloatNoteApp
        NoteEditViewModel(app.noteRepository, app.settingsRepository)
    }
}

/** 列表页：笔记实时刷新（置顶在前）+ 搜索过滤；回收站数据与操作也在此（同一数据域）。 */
class NoteListViewModel(private val repo: NoteRepository) : ViewModel() {

    private val query = kotlinx.coroutines.flow.MutableStateFlow("")

    /** 搜索词：空白 = 不过滤。标题与正文不区分大小写匹配。 */
    fun setQuery(q: String) {
        query.value = q
    }

    val notes: StateFlow<List<Note>> = kotlinx.coroutines.flow.combine(repo.observeAll(), query) { all, q ->
        if (q.isBlank()) all
        else all.filter { it.title.contains(q, ignoreCase = true) || it.content.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trashed: StateFlow<List<Note>> = repo.observeTrashed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 用户主删除路径：进回收站（30 天可恢复）。 */
    fun trashNote(id: Long) {
        viewModelScope.launch { repo.moveToTrash(id) }
    }

    fun togglePin(id: Long, pinned: Boolean) {
        viewModelScope.launch { repo.setPinned(id, pinned) }
    }

    fun restoreNote(id: Long) {
        viewModelScope.launch { repo.restoreNote(id) }
    }

    fun purgeNote(id: Long) {
        viewModelScope.launch { repo.purgeNote(id) }
    }

    fun emptyTrash() {
        viewModelScope.launch { repo.emptyTrash() }
    }
}

/** 编辑页草稿：标题 + 正文一起防抖保存。 */
data class NoteDraft(val title: String, val content: String)

sealed interface SaveStatus {
    data object Idle : SaveStatus
    data object Typing : SaveStatus
    data object Saving : SaveStatus
    data class Saved(val at: Long) : SaveStatus
}

/**
 * 编辑页：加载笔记 -> 输入防抖保存 -> 状态展示。
 * 字号跟随 font_size_sp 设置（编辑页与悬浮窗共用，计划 7.9）。
 */
class NoteEditViewModel(
    private val noteRepository: NoteRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    var title by mutableStateOf("")
        private set
    var content by mutableStateOf("")
        private set
    var saveStatus by mutableStateOf<SaveStatus>(SaveStatus.Idle)
        private set
    var loaded by mutableStateOf(false)
        private set

    /** 编辑页元信息行：当前笔记最后修改时间。 */
    var noteUpdatedAt by mutableStateOf(0L)
        private set

    val fontSizeSp: StateFlow<Int> = settingsRepository.fontSizeSp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 15)

    private var noteId = -1L
    private var isNew = false
    private var exiting = false

    private val autoSave = AutoSaveManager<NoteDraft>(viewModelScope) { draft ->
        doSave(draft)
    }

    /**
     * 同一笔记且已加载则跳过重载：旋转屏幕重建 Composable 时保留未保存的输入，
     * 不用数据库旧值覆盖。切换到别的笔记会正常重载。
     * 注意必须复位 exiting：上次退出（exit 置位）后重新进入同一笔记时，
     * 不复位会导致返回键再次调用 exit() 被防重入拦截，用户卡在编辑页退不出去。
     */
    fun load(id: Long, new: Boolean) {
        if (loaded && noteId == id) {
            exiting = false
            isNew = new
            return
        }
        autoSave.cancelPending() // 上一次编辑的防抖任务不能写进这篇笔记
        noteId = id
        isNew = new
        exiting = false
        loaded = false
        viewModelScope.launch {
            val note = noteRepository.getNote(id)
            title = note?.title.orEmpty()
            content = note?.content.orEmpty()
            noteUpdatedAt = note?.updatedAt ?: System.currentTimeMillis()
            saveStatus = SaveStatus.Idle
            loaded = true
        }
    }

    fun onTitleChange(value: String) {
        title = value
        onDraftChanged()
    }

    fun onContentChange(value: String) {
        content = value
        onDraftChanged()
    }

    private fun onDraftChanged() {
        saveStatus = SaveStatus.Typing
        autoSave.onContentChanged(NoteDraft(title, content))
    }

    private fun doSave(draft: NoteDraft) {
        if (noteId <= 0) return
        saveStatus = SaveStatus.Saving
        viewModelScope.launch {
            noteRepository.updateNote(noteId, draft.title, draft.content)
            saveStatus = SaveStatus.Saved(System.currentTimeMillis())
        }
    }

    /**
     * 返回列表：立即保存；如果是刚新建且一个字没输入，删除这条空笔记，
     * 避免"点开又退出"在列表里留下空记录。
     */
    fun exit(onDone: () -> Unit) {
        if (exiting) return
        exiting = true
        viewModelScope.launch {
            autoSave.cancelPending()
            if (isNew && title.isBlank() && content.isBlank()) {
                noteRepository.deleteNote(noteId)
            } else {
                noteRepository.updateNote(noteId, title, content)
            }
            onDone()
        }
    }

    /** 删除当前笔记（编辑页删除按钮）：v1.1 起进回收站，不再物理删除。 */
    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            autoSave.cancelPending()
            noteRepository.moveToTrash(noteId)
            onDone()
        }
    }

    override fun onCleared() {
        // 兜底：系统直接回收 Activity（没走 exit()）时也把内容落库。
        // viewModelScope 已取消，用 runBlocking 同步写入；单条笔记写入毫秒级，阻塞可忽略。
        if (!exiting && noteId > 0 && loaded && !(isNew && title.isBlank() && content.isBlank())) {
            runBlocking { noteRepository.updateNote(noteId, title, content) }
        }
    }
}
