package com.zhao.suiji.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 测试连接结果（设置页展示用）。 */
sealed class TestResult {
    data class Success(val modelIds: List<String>, val latencyMs: Long) : TestResult()
    data class AuthError(val detail: String) : TestResult()
    data class HttpError(val code: Int, val detail: String) : TestResult()
    data class NetworkError(val detail: String) : TestResult()
}

/**
 * 测试连接（设置页"AI 助手"组）：GET {base}/models 验证地址与 Key。
 * 兼容各家返回：能解析出 data[].id 就带出模型列表（用于校验模型名），解析不出不算失败。
 */
object AiConnectionTester {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun test(config: AiConfig): TestResult = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(config.baseUrl)
        if (base.isEmpty()) return@withContext TestResult.HttpError(0, "接口地址为空")
        val started = System.currentTimeMillis()
        val request = Request.Builder()
            .url("$base/models")
            .header("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val latency = System.currentTimeMillis() - started
                when {
                    resp.isSuccessful -> TestResult.Success(parseModelIds(body), latency)
                    resp.code == 401 || resp.code == 403 ->
                        TestResult.AuthError(errSnippet(body, resp.code))
                    else -> TestResult.HttpError(resp.code, errSnippet(body, resp.code))
                }
            }
        } catch (e: Exception) {
            TestResult.NetworkError(e.message ?: "网络错误")
        }
    }

    /** OpenAI 兼容的模型列表格式 {"data":[{"id":"..."}]}；其余格式容忍为空。 */
    private fun parseModelIds(body: String): List<String> = runCatching {
        val arr = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        arr.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
    }.getOrDefault(emptyList())

    private fun errSnippet(body: String, code: Int): String {
        val snippet = body.replace("\n", " ").take(120)
        return if (snippet.isEmpty()) "HTTP $code" else "HTTP $code $snippet"
    }
}
