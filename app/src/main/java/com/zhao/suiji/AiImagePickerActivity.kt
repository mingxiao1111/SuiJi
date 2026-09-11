package com.zhao.suiji

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * 选图中转（T5 图片附件）：overlay 的前台 Service 收不了 Activity 结果回调，
 * 由这个透明 Activity 代持——拉起系统 Photo Picker，选完立刻压缩（长边 ≤2048 JPEG）
 * 落 cache 文件，路径经 [AiImageBus] 回传 Service；用户取消回传 null。
 * 全程无界面感知（透明 + 不进最近任务）。
 */
class AiImagePickerActivity : ComponentActivity() {

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        AiImageBus.dispatch(uri?.let { compressToCache(it) })
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 进程被杀重建时不重拉相册，直接按取消回传
        if (savedInstanceState != null) {
            AiImageBus.dispatch(null)
            finish()
            return
        }
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** 压缩入 cache：原图可能几十 MB 的 RAW/超清图，base64 前必须压。 */
    private fun compressToCache(uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val longEdge = maxOf(bmp.width, bmp.height)
        val scaled = if (longEdge > MAX_EDGE) {
            val ratio = MAX_EDGE.toFloat() / longEdge
            Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
        } else {
            bmp
        }
        val dir = File(cacheDir, "ai_attach").apply { mkdirs() }
        val out = File(dir, "img_${System.currentTimeMillis()}.jpg")
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (scaled !== bmp) bmp.recycle()
        out.absolutePath
    }.getOrNull()

    private companion object {
        const val MAX_EDGE = 2048
    }
}

/** 进程内回传总线：Activity 与 Service 同进程，直接回调即可（无需广播/绑定）。 */
object AiImageBus {
    @Volatile
    var listener: ((String?) -> Unit)? = null

    fun dispatch(path: String?) {
        listener?.invoke(path)
    }
}
