package moe.ouom.wekit.utils

import android.content.Context
import android.widget.Toast
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.utils.logging.WeLogger

object ToastUtils {

    private val TAG = nameof(ToastUtils)

    fun showToast(ctx: Context?, msg: String?) {
        WeLogger.i(TAG, "showToast: $msg")
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun showToast(msg: String?) {
        WeLogger.i(TAG, "showToast: $msg")
        try {
            Toast.makeText(HostInfo.application, msg, Toast.LENGTH_SHORT).show()
        } catch (e: NullPointerException) {
            WeLogger.e(TAG, "failed to show toast: " + e.message)
        }
    }
}