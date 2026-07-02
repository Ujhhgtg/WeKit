package dev.ujhhgtg.wekit.features.api.net

import dev.ujhhgtg.wekit.features.api.net.abc.WeRequestCallback

class WeRequestDsl : WeRequestCallback {

    private var successHandler: ((ByteArray?) -> Unit)? = null
    private var failHandler: ((Int, Int, String) -> Unit)? = null

    fun onSuccess(handler: (bytes: ByteArray?) -> Unit) {
        successHandler = handler
    }

    fun onFailure(handler: (errType: Int, errCode: Int, errMsg: String) -> Unit) {
        failHandler = handler
    }

    override fun onSuccess(bytes: ByteArray?) {
        successHandler?.invoke(bytes)
    }

    override fun onFailure(errType: Int, errCode: Int, errMsg: String) {
        failHandler?.invoke(errType, errCode, errMsg)
    }
}
