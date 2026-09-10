package com.zhao.suiji.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容流式对话客户端：POST {base}/chat/completions（stream=true）。
 * 事件经 [SseParser] 增量解析；取消 Flow 即取消 HTTP 请求（收起竖条不打断生成
 * 由调用方决定——不收集即后台继续，收集取消则断流）。
 */
class AiChatClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // stream=true 是默认值，必须显式序列化进请求体
    }

    fun streamReply(
        config: AiConfig,
        model: String,
        messages: List<RequestMessage>,
    ): Flow<ChatEvent> = callbackFlow {
        val base = normalizeBaseUrl(config.baseUrl)
        val request = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(
                json.encodeToString(ChatRequest(model, messages))
                    .toRequestBody("application/json".toMediaType()),
            )
            .build()
        val call = CLIENT.newCall(request)
        val parser = SseParser()

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(ChatEvent.HttpError(-1, e.message ?: "网络错误"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        trySend(ChatEvent.HttpError(resp.code, errSnippet(resp)))
                        close()
                        return
                    }
                    try {
                        val source = resp.body?.source()
                        if (source == null) {
                            close()
                            return
                        }
                        while (!source.exhausted()) {
                            // readUtf8Line 已兼容 \n / \r\n；补回换行让解析器按行结算
                            val line = source.readUtf8Line() ?: break
                            parser.feed("$line\n").forEach(::emit)
                        }
                        close() // 流自然结束（个别服务商不发 [DONE]，通道关闭即完成）
                    } catch (e: Exception) {
                        trySend(ChatEvent.HttpError(-2, e.message ?: "连接中断"))
                        close()
                    }
                }
            }

            private fun emit(payload: String) {
                if (payload == DONE_MARKER) {
                    trySend(ChatEvent.Done)
                    return
                }
                val delta = runCatching {
                    json.decodeFromString(StreamChunk.serializer(), payload)
                        .choices.firstOrNull()?.delta
                }.getOrNull()
                val text = delta?.content
                if (!text.isNullOrEmpty()) trySend(ChatEvent.Delta(text))
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun errSnippet(resp: Response): String {
        val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
        val snippet = body.replace("\n", " ").take(120)
        return if (snippet.isEmpty()) "HTTP ${resp.code}" else "HTTP ${resp.code} $snippet"
    }

    private companion object {
        const val DONE_MARKER = "[DONE]"

        /** 读超时要够长：思考型模型可能长时间不吐首 token。 */
        val CLIENT = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }
}
