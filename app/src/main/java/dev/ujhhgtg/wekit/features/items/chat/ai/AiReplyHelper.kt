package dev.ujhhgtg.wekit.features.items.chat.ai

import android.content.Context
import android.widget.Toast
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi.WeMessage
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
        .build()

    // ── Preferences ──
    var apiUrl by WePrefs.prefOption(
        "ai_reply_api_url",
        "https://api.openai.com/v1/chat/completions"
    )
    var apiKey by WePrefs.prefOption("ai_reply_api_key", "")
    var model by WePrefs.prefOption("ai_reply_model", "gpt-4o-mini")
    var systemPrompt by WePrefs.prefOption(
        "ai_reply_system_prompt",
        "你是一个得力的微信聊天助手。请根据聊天上下文，用自然、简洁、口语化的语气回复。回复应当简短（不超过200字），贴合当前对话语境。"
    )
    var temperature by WePrefs.prefOption("ai_reply_temperature", 0.7f)
    var maxTokens by WePrefs.prefOption("ai_reply_max_tokens", 1000)
    var contextCount by WePrefs.prefOption("ai_reply_context_count", 10)

    /**
     * 检查是否已配置 API
     */
    fun isConfigured(): Boolean {
        return apiUrl.isNotBlank() && apiKey.isNotBlank()
    }

    /**
     * 获取当前聊天上下文并调用 AI API
     */
    fun replyToCurrentConversation(context: Context) {
        if (!isConfigured()) {
            showToast(context, "请先配置 AI API（聊天工具栏设置 > AI 回复配置）")
            return
        }

        val convId = WeCurrentConversationApi.value
        if (convId.isBlank()) {
            showToast(context, "未检测到当前聊天")
            return
        }

        showToast(context, "AI 思考中…")

        MainScope().launch(Dispatchers.IO + SupervisorJob()) {
            try {
                val messages = WeDatabaseApi.getMessages(convId, 1, contextCount)
                if (messages.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showToast(context, "没有可用的聊天记录")
                    }
                    return@launch
                }

                val reply = callAiApi(buildMessages(messages))
                if (reply != null) {
                    val sent = WeMessageApi.sendText(convId, reply)
                    withContext(Dispatchers.Main) {
                        if (sent) {
                            WeLogger.i(TAG, "AI reply sent to $convId")
                        } else {
                            showToast(context, "AI 回复发送失败")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showToast(context, "AI 回复获取失败，请检查 API 配置")
                    }
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "AI reply failed", e)
                withContext(Dispatchers.Main) {
                    showToast(context, "AI 回复出错: ${e.message?.take(50) ?: "未知错误"}")
                }
            }
        }
    }

    /**
     * 将微信消息转换为 OpenAI 格式的 messages
     */
    private fun buildMessages(messages: List<WeMessage>): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        // System prompt first
        result.add("system" to systemPrompt)

        // Add conversation history (from oldest to newest)
        for (msg in messages.reversed()) {
            val role = if (msg.isSend == 1) "assistant" else "user"
            val content = msg.content.ifBlank { "[非文本消息]" }
            result.add(role to content)
        }

        return result
    }

    /**
     * 调用 OpenAI 兼容的 Chat Completions API
     */
    private fun callAiApi(messages: List<Pair<String, String>>): String? {
        val bodyJson = JSONObject().apply {
            put("model", model)
            put("temperature", temperature.toDouble())
            put("max_tokens", maxTokens)
            put("messages", JSONArray(messages.map { (role, content) ->
                JSONObject().apply {
                    put("role", role)
                    put("content", content)
                }
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
                WeLogger.e(TAG, "API error: ${response.code} $body")
                return null
            }
            val json = JSONObject(body ?: return null)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val choice = choices.getJSONObject(0)
                choice.optJSONObject("message")?.optString("content", null)
                    ?: choice.optString("text", null)
            } else {
                null
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "API call failed", e)
            null
        }
    }

    private fun showToast(context: Context, msg: String) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
}
