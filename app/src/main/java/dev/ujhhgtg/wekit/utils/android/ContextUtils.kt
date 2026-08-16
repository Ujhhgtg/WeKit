package dev.ujhhgtg.wekit.utils.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Process

inline val Context.isDarkMode
    get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

inline val androidUserId: Int
    get() = Process.myUid() / 100_000

// it is the caller's responsibility to ensure the class is a service
inline fun <reified T : Any> Context.getSystemService(): T =
    getSystemService(T::class.java)!!

// kotlin doesnt support property:get tailrec, idk why
inline val Context.baseActivity get() = _baseActivity(this)

@Suppress("FunctionName")
tailrec fun _baseActivity(baseContext: Context?): Activity? = when (baseContext) {
    is Activity -> baseContext
    is ContextWrapper -> _baseActivity(baseContext.baseContext)
    else -> null
}
