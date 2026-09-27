package dev.ujhhgtg.wekit.features.items.system.predictive_back

import android.app.Activity
import android.os.Build
import androidx.annotation.RequiresApi
import com.tencent.mm.ui.widget.SwipeBackLayout
import dev.ujhhgtg.wekit.features.api.ui.WeLauncherBackApi

/**
 * What one opted-in activity's legacy BACK chain would do right now, for [PredictiveBackController].
 */
interface BackHost {

    /** Whether a legacy BACK would leave the activity (finish it or move its task back). */
    fun isIdle(): Boolean

    /** The in-window chat's SwipeBackLayout when a BACK would leave that chat right now. */
    fun readyChatSwipeLayout(): SwipeBackLayout? = null

    /** Whether the in-window chat is still foreground (a driven swipe commits instead of cancelling). */
    fun isChatForeground(): Boolean = false

    /** Whether a BACK was consumed outside the key chain during the replay. */
    fun consumedOutsideKeyChain(): Boolean = false

    /**
     * Whether to still leave through the platform's default back when an untracked consumer took a
     * BACK the system had already animated as leaving. Only safe where leaving is non-destructive.
     */
    val leavesAfterUntrackedConsumer: Boolean
        get() = false

    /** State dump for diagnostics. */
    fun describe(): String
}

/** LauncherUI: home screen, overlays and the in-window chat (`NewChattingTabUI`). */
class LauncherBackHost(private val activity: Activity) : BackHost {
    override fun isIdle() = PredictiveBackGestures.isHomeIdle(activity)

    @RequiresApi(Build.VERSION_CODES.R)
    override fun readyChatSwipeLayout() = PredictiveBackGestures.readyLauncherChatSwipeLayout(activity)

    override fun isChatForeground() = PredictiveBackGestures.isLauncherChatForeground(activity)

    override fun consumedOutsideKeyChain() = WeLauncherBackApi.isConsumedThisFrame(activity)

    // Leaving LauncherUI only moves the task back.
    override val leavesAfterUntrackedConsumer = true

    override fun describe() = PredictiveBackGestures.describeHomeState(activity)
}

/** `BaseConversationUI` (folders, service/app-brand conversation lists): a list plus an in-window chat. */
class ConversationBackHost(private val activity: Activity) : BackHost {
    override fun isIdle() = PredictiveBackGestures.isConversationIdle(activity)

    @RequiresApi(Build.VERSION_CODES.R)
    override fun readyChatSwipeLayout() = PredictiveBackGestures.readyConversationChatSwipeLayout(activity)

    override fun isChatForeground() = PredictiveBackGestures.isConversationChatForeground(activity)

    override fun describe() = PredictiveBackGestures.describeStandardState(activity)
}

/** Any other WeChat activity without back handling of its own: BACK finishes it unless a shared consumer is active. */
class StandardBackHost(private val activity: Activity) : BackHost {
    override fun isIdle() = PredictiveBackGestures.areStandardConsumersIdle(activity)

    override fun describe() = PredictiveBackGestures.describeStandardState(activity)
}
