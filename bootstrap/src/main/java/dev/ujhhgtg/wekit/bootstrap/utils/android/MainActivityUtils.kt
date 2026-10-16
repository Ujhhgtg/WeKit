package dev.ujhhgtg.wekit.bootstrap.utils.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast

val androidUserId: Int get() = Process.myUid() / 100_000

fun showToast(context: Context, content: String) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(context, content, Toast.LENGTH_SHORT).show() }
}
