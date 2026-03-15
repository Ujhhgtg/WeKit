package moe.ouom.wekit.hooks.api.net.intf

interface WeRequestCallback {
    fun onSuccess(json: String, bytes: ByteArray?)
    fun onFail(errType: Int, errCode: Int, errMsg: String)
}