package com.zhao.suiji.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 敏感值存储（AI API Key）：EncryptedSharedPreferences，密钥由 Android Keystore 托管。
 * 极少数机型 Keystore 初始化失败时降级为明文并打日志——宁可提示风险，不让设置页崩溃。
 * 值本身永远来自用户输入，本文件不持有任何凭据。
 */
class SecretStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "float_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            Log.w("SecretStore", "Keystore 不可用，降级为明文存储", it)
            context.getSharedPreferences("float_secrets_plain", Context.MODE_PRIVATE)
        }
    }

    fun getApiKey(): String = prefs.getString("pref_ai_entry", "") ?: ""

    fun setApiKey(key: String) {
        prefs.edit().putString("pref_ai_entry", key.trim()).apply()
    }
}
