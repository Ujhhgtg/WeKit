package moe.ouom.wekit.hooks.api.net

import moe.ouom.wekit.hooks.api.net.intf.WeRequestCallback

class WeRequestDsl : WeRequestCallback {

    private var successHandler: ((String, ByteArray?) -> Unit)? = null
    private var failHandler: ((Int, Int, String) -> Unit)? = null

    fun onSuccess(handler: (json: String, bytes: ByteArray?) -> Unit) {
        this.successHandler = handler
    }

    fun onFail(handler: (errType: Int, errCode: Int, errMsg: String) -> Unit) {
        this.failHandler = handler
    }

    override fun onSuccess(json: String, bytes: ByteArray?) {
        successHandler?.invoke(json, bytes)
    }

    override fun onFail(errType: Int, errCode: Int, errMsg: String) {
        failHandler?.invoke(errType, errCode, errMsg)
    }
}