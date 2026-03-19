package moe.ouom.wekit.hooks.api.core

import moe.ouom.wekit.utils.RuntimeConfig

object WeApi {

    /**
     * 获取当前登录的微信 ID
     */
    val selfWxId: String
        get() = RuntimeConfig.loggedInWxId

    /**
     * 获取自己的微信号
     */
    val selfCustomWxId: String
        get() {
            return WeMessageApi.getSelfCustomWxId()
        }
}
