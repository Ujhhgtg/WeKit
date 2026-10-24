@file:SuppressLint("DiscouragedPrivateApi", "PrivateApi")

package dev.ujhhgtg.wekit.utils.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityThread
import android.os.IBinder
import android.util.ArrayMap
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.utils.WeLogger

private val mActivitiesField =
    ActivityThread::class.java.getDeclaredField("mActivities").makeAccessible()
private val pausedField =
    ActivityThread.ActivityClientRecord::class.java.getDeclaredField("paused").makeAccessible()
private val activityField =
    ActivityThread.ActivityClientRecord::class.java.getDeclaredField("activity").makeAccessible()

fun getTopMostActivity(allowPaused: Boolean = false): Activity? = runCatching {
    val currentActivityThread = ActivityThread.currentActivityThread()

    @Suppress("UNCHECKED_CAST")
    val activities =
        mActivitiesField.get(currentActivityThread)
            as ArrayMap<IBinder, ActivityThread.ActivityClientRecord>

    activities.values
        .filter { record -> allowPaused || pausedField.get(record) == false }
        .mapNotNull { record -> activityField.get(record) as? Activity }
        .lastOrNull()
}
    .getOrElse {
        WeLogger.e("getTopMostActivity", "failed to get top-most activity", it)
        null
    }

val Activity.currentWxId: String?
    get() {
        return intent.getStringExtra("Contact_User")
            ?: intent.getStringExtra("RoomInfo_Id")
            ?: intent.getStringExtra("room_name")
            ?: intent.getStringExtra("Contact_ChatRoomId")
            ?: intent.getStringExtra("Chat_User")
    }
