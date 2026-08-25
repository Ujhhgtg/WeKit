package dev.ujhhgtg.wekit.features.items.contacts.hidecontacts

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.contacts.HideContacts
import dev.ujhhgtg.wekit.features.items.notifications.NotificationsEvolved
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger

/**
 * Notification suppression for 隐藏联系人, split out of [HideContacts] so it can also load in
 * `com.tencent.mm:push`.
 *
 * ## Why this is a separate feature
 *
 * WeChat raises new-message notifications from `com.tencent.mm.booter.CoreService`, which the host
 * manifest pins to `android:process=":push"`. [HideContacts] does not override
 * `shouldLoadInCurrentProcess`, so it only ever loads in the main process — which means the
 * `dealNotify` hook it used to install inline was registered in a process that never calls it. The
 * suppression therefore (almost) never fired. There is a second path on top of that: the LightPush
 * builder raises the notification and plays the ringtone/vibration straight off the push payload and
 * never goes through `dealNotify` at all, so it was not covered even in principle.
 *
 * Widening [HideContacts]'s process set is not an option: its `onEnable` is almost entirely
 * main-process work (SQL hooks, LauncherUI/ChattingUI hooks, the screen-off receiver, the shake
 * detector, the one-shot legacy `parentRef` DB migration) that would be wrong — and in the migration's
 * case racy — if it ran a second time in `:push`. So only the two notification hooks live here, in a
 * feature modelled on
 * [dev.ujhhgtg.wekit.features.items.notifications.NotificationsEvolved], which already opts into
 * main + push the same way.
 *
 * ## Not user-visible on purpose
 *
 * This is an [ApiFeature] in the `API` category, so it never shows up in the feature list or in the
 * settings search (which only lists `SwitchFeature`s). Notification suppression is not a separate
 * user-facing concept — it is part of "隐藏联系人" — and a second toggle for the same concept would
 * only be confusing. Instead the hooks are installed unconditionally and read [HideContacts]'s own
 * persisted on/off state per invocation, so flipping the 隐藏联系人 switch takes effect immediately
 * without a restart, in both processes.
 *
 * ## Known inconsistency: `temporarilyShown` is main-process only
 *
 * [HideContacts]'s temporary-show escape hatch (`#show` in the input bar, or the triple-tap on the
 * main-screen title) is a plain in-memory flag in the main process; it is deliberately **not**
 * persisted. `:push` cannot see it, and duplicating it into MMKV would mean persisting a state that
 * is supposed to die with the process. The accepted consequence: while temporary-show is active a
 * hidden contact's chats and contact rows reappear, but their **notifications stay suppressed**.
 * Turn 隐藏联系人 off entirely to get notifications back.
 */
object HideContactsNotifications : ApiFeature(), IResolveDex {

    override val technicalId = "隐藏联系人通知抑制"
    override val nameRes = R.string.feature_hide_contacts_notifications_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_hide_contacts_notifications_description

    private const val TAG = "HideContactsNotifications"

    /**
     * `com.tencent.mm.booter.notification.x.d(x self, String talker, String content, int msgType,
     * int tipsFlag, boolean isRevokeMessage)` — `static void`, so `args[1]` is the talker and
     * cancelling means `result = null`.
     *
     * Verified identical on both trees:
     * - 8.0.76 `com/tencent/mm/booter/notification/x.java:231`
     *   `public static void d(x xVar, String username, String str, int i17, int i18, boolean z17)`,
     *   anchor string at `:249`
     * - 8.0.69 `com/tencent/mm/booter/notification/x.java:319`
     *   `public static void d(x xVar, String talker, String str, int i16, int i17, boolean z16)`,
     *   anchor string at `:394`
     *
     * The anchor occurs exactly once in each tree. Same matcher as
     * [dev.ujhhgtg.wekit.features.items.notifications.NotificationsEvolved]'s, deliberately — if it
     * ever 0-hits, that feature would already surface the dex-repair dialog, so adding
     * `allowFailure` here would buy nothing.
     */
    /**
     * `com.tencent.mm.booter.notification.e0.f(long msgId, String userName, String nickName,
     * String content, String avatarPath, Map msgSource, j4 cmd)` — the LightPush notification
     * builder, an **instance** method returning `void`, so cancelling is `result = null`.
     *
     * Verified on both trees (only the obfuscated method name and the cmd-proto type differ, neither
     * of which the matcher depends on):
     * - 8.0.76 `com/tencent/mm/booter/notification/e0.java:444`
     *   `public void f(long j17, String str, String str2, String str3, String str4, Map map, j4 j4Var)`,
     *   anchor string at `:490`
     * - 8.0.69 `com/tencent/mm/booter/notification/e0.java:419`
     *   `public void g(long j16, String str, String str2, String str3, String str4, Map map, i4 i4Var)`,
     *   anchor string at `:476`
     *
     * `args[1]` is the userName: the guard `if (t8.K0(str) || t8.K0(str2))` logs
     * `"LightPush [NO NOTIFICATION] Util.isNullOrNil(userName) || Util.isNullOrNil(nickName)"`
     * (8.0.76 `:497`, 8.0.69 `:483`), binding `str`→userName positionally, and `str` is reused as the
     * talker further down (`"... talker: %s, tipsFlag: %s "`).
     *
     * The anchor occurs exactly once per tree, and in both cases the target method is the last
     * declaration before it, so the string is inside the method DexKit will return — which is what
     * makes this matcher safe, not `allowFailure`. `allowFailure` covers **only the 0-hit case**:
     * `DexMethodDelegate.find` (`dexkit/dsl/DexDelegates.kt`) `error()`s on a multi-hit regardless of
     * it. It is set here purely for the 0-hit case: only 8.0.76 and 8.0.69 could be inspected, and on
     * an unverified 8.0.6x build a 0-hit would mark this feature broken and pop the dex-repair dialog
     * on every launch — worse than losing the LightPush bypass while [methodDealNotify] keeps working.
     */
    private val methodNotifyForLightPush by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(7)
            usingEqStrings("notifyForLightPush push:isShake: %B, isSound: %B ctrlFlag:%s")
        }
    }

    /** `CoreService` — and therefore both notification paths — lives in `:push`, not in main. */
    override fun startup() {
        if (!TargetProcesses.isInMain && TargetProcesses.currentType != TargetProcesses.PROC_PUSH)
            return
        enable()
    }

    /**
     * Whether a notification for [wxId] must be swallowed.
     *
     * Everything is read from [WePrefs] (MMKV in `MULTI_PROCESS_MODE`) rather than from
     * [HideContacts]'s runtime state, because none of that state exists in `:push`:
     * - `SwitchFeature` persists its on/off state under the feature's `technicalId`, so the 隐藏联系人 switch
     *   is readable here;
     * - `HideContacts.hiddenContacts` is itself nothing but a [WePrefs] string-set read.
     *
     * `HideContacts.temporarilyShown` is intentionally *not* consulted — see the class KDoc.
     */
    private fun isSuppressed(wxId: String): Boolean =
        WePrefs.getBoolOrDef(HideContacts.technicalId, false) && wxId in HideContacts.hiddenContacts

    override fun onEnable() {
        // Both bodies only cancel the call; they mutate no WeChat state and so cannot re-trigger the
        // method they are hooked onto.
        //
        // Accepted design: as an ApiFeature this is always enabled, so both hooks are installed even
        // when 隐藏联系人 itself is off — the on/off decision is made *per invocation*, inside the
        // bodies, by isSuppressed(). That is what buys restart-free toggling of the 隐藏联系人 switch
        // in both processes (and in :push, where none of HideContacts' runtime state exists). The
        // price is two permanently installed hooks that do nothing but an MMKV read and an early
        // return while the feature is off. Deliberate; do not "fix" it by gating installation on the
        // switch, which would make turning 隐藏联系人 on require a WeChat restart to suppress
        // notifications.

        NotificationsEvolved.methodDealNotify.hookBefore(100) {
            val talker = args[1] as? String ?: return@hookBefore
            if (!isSuppressed(talker)) return@hookBefore
            WeLogger.i(TAG, "suppressing message notification from $talker")
            result = null
        }

        if (methodNotifyForLightPush.isPlaceholder) {
            WeLogger.w(TAG, "LightPush notify method not resolved; its notifications stay visible")
            return
        }

        methodNotifyForLightPush.hookBefore(100) {
            val userName = args[1] as? String ?: return@hookBefore
            if (!isSuppressed(userName)) return@hookBefore
            WeLogger.i(TAG, "suppressing LightPush notification from $userName")
            result = null
        }
    }
}
