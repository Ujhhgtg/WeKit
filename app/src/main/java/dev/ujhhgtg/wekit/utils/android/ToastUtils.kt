@file:Suppress("NOTHING_TO_INLINE")

package dev.ujhhgtg.wekit.utils.android

import android.content.Context
import android.widget.Toast
import dev.ujhhgtg.wekit.utils.HostInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

inline fun showToast(context: Context, content: String) {
    runOnUiThread {
        Toast.makeText(context, content, Toast.LENGTH_SHORT).show()
    }
}

inline fun showToast(content: String) = showToast(HostInfo.application, content)

suspend inline fun showToastSuspend(context: Context, content: String) = withContext(Dispatchers.Main) {
    showToast(context, content)
}

suspend inline fun showToastSuspend(content: String) = withContext(Dispatchers.Main) {
    showToast(content)
}
