@file:Suppress("unused")

package moe.ouom.wekit.hooks.item.script

import moe.ouom.wekit.hooks.sdk.protocol.WeApi
import moe.ouom.wekit.util.log.WeLogger

object WeApiUtils {
    private const val TAG = "WeApiUtils"

    fun getSelfWxId(): String {
        return try {
            WeApi.getSelfWxId()
        } catch (e: Exception) {
            WeLogger.e(TAG, "获取当前微信id失败: ${e.message}")
            ""
        }
    }

    fun getSelfAlias(): String {
        return try {
            WeApi.getSelfAlias()
        } catch (e: Exception) {
            WeLogger.e(TAG, "获取当前微信号失败: ${e.message}")
            ""
        }
    }
}