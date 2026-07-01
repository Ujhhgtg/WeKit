package dev.ujhhgtg.wekit.features.items.chat.ai

import android.content.Context
import android.widget.Toast
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AiReplyHelper {
    private val TAG = nameOf(AiReplyHelper::class)
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Xposed 环境可能证书链不全，信任所有证书
        .sslSocketFactory(createTrustAllSslSocketFactory(), createTrustAllTrustManager())
        .hostnameVerifier { _, _ -> true }
        .build()

    private fun createTrustAllSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val trustAll = createTrustAllTrustManager()
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAll), java.security.SecureRandom())
        return sslContext.socketFactory
    }

    private fun createTrustAllTrustManager(): javax.net.ssl.X509TrustManager {
        return object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
    }

    var apiUrl by WePrefs.prefOption("ai_reply_api_url", "https://api.openai.com/v1/chat/completions")
    var apiKey by WePrefs.prefOption("ai_reply_api_key", "")
    var model by WePrefs.prefOption("ai_reply_model", "gpt-4o-mini")
    var systemPrompt by WePrefs.prefOption(
        "ai_reply_system_prompt",
        "你是一个得力的微信聊天助手。请根据聊天上下文，用自然、简洁、口语化的语气回复。回复应当简短（不超过200字），贴合当前对话语境。"
    )
    var temperature by WePrefs.prefOption("ai_reply_temperature", 0.7f)
    var maxTokens by WePrefs.prefOption("ai_reply_max_tokens", 1000)
    var contextCount by WePrefs.prefOption("ai_reply_context_count", 10)

    fun isConfigured(): Boolean = apiUrl.isNotBlank() && apiKey.isNotBlank()

    fun replyToCurrentConversation(context: Context) {
        if (!isConfigured()) {
            showToast(context, "请先配置 AI API（聊天工具栏 > AI 配置）")
            return
        }
        val convId = WeCurrentConversationApi.value
        if (convId.isBlank()) {
            showToast(context, "请先进入一个聊天再点击 AI 回复")
            return
        }
        showToast(context, "AI 思考中…")
        MainScope().launch(Dispatchers.IO + SupervisorJob()) {
            try {
                val messages = WeDatabaseApi.getMessages(convId, 1, contextCount)
                if (messages.isEmpty()) {
                    withContext(Dispatchers.Main) { showToast(context, "没有聊天记录") }
                    return@launch
                }
                val reply = callAiApi(buildMessages(messages))
                if (reply != null) {
                    WeMessageApi.sendText(convId, reply)
                    withContext(Dispatchers.Main) { WeLogger.i(TAG, "AI reply sent") }
                } else {
                    withContext(Dispatchers.Main) { showToast(context, "AI 回复失败，请检查 API 配置和网络") }
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "AI reply failed", e)
                withContext(Dispatchers.Main) { showToast(context, "AI 出错: ${e.message?.take(50) ?: "未知"}") }
            }
        }
    }

    private fun buildMessages(messages: List<WeMessage>): List<Pair<String, String>> {
        val result = mutableListOf("system" to systemPrompt)
        // messages are newest-first, reverse to chronological
        for (msg in messages.reversed()) {
            val role = if (msg.isSend == 1) "assistant" else "user"
            val text = msg.content.ifBlank { "[非文本消息]" }
            result.add(role to text)
        }
        return result
    }

    private fun callAiApi(messages: List<Pair<String, String>>): String? {
        val bodyJson = JSONObject().apply {
            put("model", model)
            put("temperature", temperature.toDouble())
            put("max_tokens", maxTokens)
            put("messages", JSONArray(messages.map { (role, content) ->
                JSONObject().apply { put("role", role); put("content", content) }
            }))
        }
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) {
                WeLogger.e(TAG, "API error: ${response.code} ${body?.take(200)}")
                return null
            }
            val json = JSONObject(body ?: return null)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                choices.getJSONObject(0)
                    .optJSONObject("message")
                    ?.optString("content", null)
                    ?: choices.getJSONObject(0).optString("text", null)
            } else null
        } catch (e: Exception) {
            WeLogger.e(TAG, "API call failed", e)
            null
        }
    }

    private fun showToast(context: Context, msg: String) {
        try { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }
}