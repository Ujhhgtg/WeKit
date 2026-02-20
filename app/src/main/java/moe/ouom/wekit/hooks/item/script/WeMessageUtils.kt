@file:Suppress("unused")

package moe.ouom.wekit.hooks.item.script

import moe.ouom.wekit.hooks.sdk.api.WeMessageApi
import moe.ouom.wekit.util.log.WeLogger

object WeMessageUtils {
    private const val TAG = "WeMessageUtils"
    private val instance: WeMessageApi? by lazy {
        try {
            WeMessageApi.INSTANCE
        } catch (e: Exception) {
            WeLogger.e(TAG, "初始化 WeMessageApi 失败: ${e.message}")
            null
        }
    }

    /**
     * 发送文本消息
     * @param toUser 目标用户ID
     * @param text 消息内容
     * @return 是否发送成功
     */
    fun sendText(toUser: String, text: String): Boolean {
        return try {
            instance?.sendText(toUser, text) ?: false
        } catch (e: Exception) {
            WeLogger.e(TAG, "发送文本消息失败: ${e.message}")
            false
        }
    }

    /**
     * 发送图片消息
     * @param toUser 目标用户ID
     * @param imgPath 图片路径
     * @return 是否发送成功
     */
    fun sendImage(toUser: String, imgPath: String): Boolean {
        return try {
            instance?.sendImage(toUser, imgPath) ?: false
        } catch (e: Exception) {
            WeLogger.e(TAG, "发送图片消息失败: ${e.message}")
            false
        }
    }

    /**
     * 发送文件消息
     * @param talker 目标用户ID
     * @param filePath 文件路径
     * @param title 文件标题
     * @param appid 应用ID（可选）
     * @return 是否发送成功
     */
    fun sendFile(talker: String, filePath: String, title: String, appid: String? = null): Boolean {
        return try {
            instance?.sendFile(talker, filePath, title, appid) ?: false
        } catch (e: Exception) {
            WeLogger.e(TAG, "发送文件消息失败: ${e.message}")
            false
        }
    }

    /**
     * 发送语音消息
     * @param toUser 目标用户ID
     * @param path 语音文件路径
     * @param durationMs 语音时长（毫秒）
     * @return 是否发送成功
     */
    fun sendVoice(toUser: String, path: String, durationMs: Int): Boolean {
        return try {
            instance?.sendVoice(toUser, path, durationMs) ?: false
        } catch (e: Exception) {
            WeLogger.e(TAG, "发送语音消息失败: ${e.message}")
            false
        }
    }

    /**
     * 发送XML应用消息
     * @param toUser 目标用户ID
     * @param xmlContent XML内容
     * @return 是否发送成功
     */
    fun sendXmlAppMsg(toUser: String, xmlContent: String): Boolean {
        return try {
            instance?.sendXmlAppMsg(toUser, xmlContent) ?: false
        } catch (e: Exception) {
            WeLogger.e(TAG, "发送XML应用消息失败: ${e.message}")
            false
        }
    }
}