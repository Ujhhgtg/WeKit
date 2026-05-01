@file:SuppressLint("DiscouragedPrivateApi", "PrivateApi")

package dev.ujhhgtg.wekit.utils.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityThread
import android.os.IBinder
import android.util.ArrayMap
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.utils.WeLogger

private val mActivitiesField = ActivityThread::class.java.getDeclaredField("mActivities")
    .also { it.isAccessible = true }
private val pausedField = ActivityThread.ActivityClientRecord::class.java.getDeclaredField("paused")
    .also { it.isAccessible = true }
private val activityField = ActivityThread.ActivityClientRecord::class.java.getDeclaredField("activity")
    .also { it.isAccessible = true }

fun getTopMostActivity(allowPaused: Boolean = false): Activity? = runCatching {
    val currentActivityThread = ActivityThread.currentActivityThread()

    @Suppress("UNCHECKED_CAST")
    val activities = mActivitiesField.get(currentActivityThread) as ArrayMap<IBinder, ActivityThread.ActivityClientRecord>

    activities.values
        .filter { record -> allowPaused || pausedField.get(record) == false }
        .mapNotNull { record -> activityField.get(record) as? Activity }
        .lastOrNull()
}.getOrElse {
    WeLogger.e(nameOf(::getTopMostActivity), "failed to get topmost activity", it)
    null
}
