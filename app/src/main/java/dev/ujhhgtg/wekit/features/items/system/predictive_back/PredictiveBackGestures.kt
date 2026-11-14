package dev.ujhhgtg.wekit.features.items.system.predictive_back

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityThread
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.annotation.RequiresApi
import com.android.dx.stock.ProxyBuilder
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.ui.MMFragment
import com.tencent.mm.ui.widget.SwipeBackLayout
import dev.ujhhgtg.dexsql.meta.DexFieldInfo
import dev.ujhhgtg.dexsql.meta.DexMethodInfo
import dev.ujhhgtg.dexsql.meta.FieldUsingType
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.data.KvStore
import dev.ujhhgtg.wekit.dex.dsl.dexClass
import dev.ujhhgtg.wekit.dex.dsl.dexField
import dev.ujhhgtg.wekit.dex.dsl.dexFieldFrom
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.dex.dsl.dexMethodFrom
import dev.ujhhgtg.wekit.features.api.ui.WeLauncherBackApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.system.predictive_back.PredictiveBackGestures.nonConsumingUics
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.baseActivity
import java.util.ArrayDeque
import java.util.WeakHashMap

/**
 * Predictive back for WeKit's own pages and for WeChat activities.
 * - Module activities (Compose) are opted in and handle predictive back themselves.
 * - WeChat activities (see [PredictiveBackController] and [BackHost]):
 *     - LauncherUI and `BaseConversationUI` host a chat in-window; a plain chat follows the gesture
 *       with WeChat's own swipe, everything else keeps its legacy key semantics.
 *     - Other WeChat activities without back handling of their own (or only the verified
 *       [leavingBackHandlers]) get the platform's cross-activity animation. Activities that handle
 *       back themselves are not opted in.
 *
 * Every question "would WeChat consume this back?" is answered with the exact predicates WeChat's
 * own key chains evaluate — `LauncherUI.dispatchKeyEvent`, `MainUI.onKeyDown`, the chat
 * FootComponent's key handler and MMActivity's controller — resolved from their Dex metadata.
 */
object PredictiveBackGestures : SwitchFeature() {

    override val technicalId = "微信预见性返回手势"
    override val nameRes = R.string.feature_predictive_back_gestures_name
    override val descriptionRes = R.string.feature_predictive_back_gestures_description
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)

    /**
     * Until first toggled, carry over the theme setting that used to switch the module pages'
     * predictive back.
     */
    override val defaultEnabled: Boolean
        get() = KvStore.getBoolOrFalse(Preferences.THEME_PREDICTIVE_BACK_ENABLED)

    override val declaredErrors: List<Int>
        get() =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                listOf(R.string.feature_error_requires_android_14)
            } else emptyList()

    private const val TAG = "PredictiveBackGestures"

    private const val LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI"
    private const val BASE_CONVERSATION_UI = "com.tencent.mm.ui.conversation.BaseConversationUI"
    private const val MM_FRAGMENT_ACTIVITY = "com.tencent.mm.ui.MMFragmentActivity"
    private const val MM_ACTIVITY = "com.tencent.mm.ui.MMActivity"
    private const val VAS_ACTIVITY = "com.tencent.mm.ui.vas.VASActivity"
    private const val UI_COMPONENT = "com.tencent.mm.ui.component.UIComponent"
    private const val VAS_LAUNCHER = "com.tencent.mm.ui.vas.launcher.VASLauncher"
    private const val NEW_CHATTING_TAB_UI = "com.tencent.mm.ui.NewChattingTabUI"
    private const val MAIN_TAB_UI = "com.tencent.mm.ui.MainTabUI"
    private const val MM_FRAGMENT = "com.tencent.mm.ui.MMFragment"
    private const val MAIN_UI = "com.tencent.mm.ui.conversation.MainUI"
    private const val MORE_TAB_UI = "com.tencent.mm.ui.MoreTabUI"
    private const val BASE_CHATTING_UI_FRAGMENT =
        "com.tencent.mm.ui.chatting.BaseChattingUIFragment"
    private const val MULTITASK_UIC = "com.tencent.mm.plugin.multitask.ui.uic.MultiTaskUIC"
    private const val CHATBOT_UIC = "com.tencent.mm.plugin.multitask.ui.uic.MultiTaskChatbotUIC"
    private const val CHAT_FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
    private const val MORE_BTN_BAR = "com.tencent.mm.ui.chatting.ChattingFooterMoreBtnBar"
    private const val LANGUAGE_CHOICE_LAYOUT =
        "com.tencent.mm.plugin.transvoice.ui.LanguageChoiceLayout"
    private const val SWIPE_BACK_LAYOUT = "com.tencent.mm.ui.widget.SwipeBackLayout"
    private const val SNS_HEADER_UIC =
        "com.tencent.mm.plugin.sns.ui.improve.component.header.ImproveHeaderUIC"
    private const val SNS_COVER_LAYOUT =
        "com.tencent.mm.plugin.sns.ui.improve.cover.SnsImproveCoverLayout"
    private const val APP_COMPAT_ACTIVITY = "androidx.appcompat.app.AppCompatActivity"

    /** Activity/UIC methods through which a class can take BACK away from the default chain. */
    private val backHandlerNames =
        setOf(
            "onBackPressed",
            "onKeyDown",
            "onKeyUp",
            "dispatchKeyEvent",
            "onKeyLongPress",
            "onKeyMultiple",
        )

    /**
     * WeChat bases whose back handlers are modelled by [areStandardConsumersIdle]: MMActivity's
     * listeners and controller search, and VASActivity's handlers, which only matter in VAS mode.
     */
    private val modelledBases = setOf(MM_ACTIVITY, VAS_ACTIVITY)

    /**
     * UICs whose `onBackPressed` only hides their comment footer and returns `UIComponent`'s false
     * (verified on 8.0.65–8.0.79): the page still leaves.
     */
    private val nonConsumingUics =
        setOf(
            "com.tencent.mm.plugin.sns.ui.improve.component.ImproveInputUIC",
            "com.tencent.mm.plugin.sns.ui.improve.component.SnsWsFoldInputUIC",
        )

    // --- Dex: LauncherUI.dispatchKeyEvent and the predicates it checks before HomeUI ---

    private val methodLauncherDispatchKeyEvent by dexMethod {
        declaredClass = "com.tencent.mm.ui.LauncherUI"
        name = "dispatchKeyEvent"
        returnType = "boolean"
        paramTypes("android.view.KeyEvent")
    }

    /** `VASLauncher.r7()`: a VAS pane is shown and takes every key. */
    private val methodVasPaneVisible by dexMethodFrom {
        methodLauncherDispatchKeyEvent.data.invokes
            .distinctBy { it.descriptor }
            .single {
                it.declaredClassName == VAS_LAUNCHER && it.isNoArgBoolean()
            }
    }

    /** `NewChattingTabUI.m()`: the chat fragment is foreground and takes BACK. */
    private val methodChatForeground by dexMethodFrom {
        methodLauncherDispatchKeyEvent.data.invokes
            .distinctBy { it.descriptor }
            .single {
                it.declaredClassName == NEW_CHATTING_TAB_UI && it.isNoArgBoolean()
            }
    }

    /** `BaseChattingUIFragment`'s ChattingContext, read by `NewChattingTabUI.m()`. */
    private val fieldFragmentChattingContext by dexFieldFrom {
        methodChatForeground.data.readFields().single {
            it.declaredClassName == BASE_CHATTING_UI_FRAGMENT
        }
    }

    /** The ChattingContext's foreground flag: the footer only takes BACK while it is set. */
    private val fieldChattingContextForeground by dexFieldFrom {
        methodChatForeground.data.readFields().single {
            it.declaredClassName == fieldFragmentChattingContext.data.typeName &&
                it.typeName == "boolean"
        }
    }

    /** `MultiTaskUIC.M7(KeyEvent)`: the multitask panel's key handler. */
    private val methodMultiTaskKeyHandler by dexMethodFrom {
        methodLauncherDispatchKeyEvent.data.invokes
            .distinctBy { it.descriptor }
            .single {
                it.declaredClassName == MULTITASK_UIC &&
                    it.paramTypeNames == listOf("android.view.KeyEvent") &&
                    it.returnTypeName == "boolean"
            }
    }

    /** `MultiTaskUIC.S7()`: the multitask panel is open. */
    private val methodMultiTaskPanelOpen by dexMethodFrom {
        methodMultiTaskKeyHandler.data.invokes
            .distinctBy { it.descriptor }
            .single {
                it.declaredClassName == MULTITASK_UIC && it.isNoArgBoolean()
            }
    }

    /**
     * `MultiTaskChatbotUIC.P7()`: the chatbot panel is open; absent where LauncherUI does not route
     * BACK to it (before 8.0.77).
     */
    private val methodChatbotPanelOpen by
        dexMethodFrom(allowFailure = true) {
            val invokes = methodLauncherDispatchKeyEvent.data.invokes.distinctBy { it.descriptor }
            if (invokes.none { it.declaredClassName == CHATBOT_UIC }) null
            else
                invokes.single {
                    it.declaredClassName == CHATBOT_UIC &&
                        it.name != "onBackPressed" &&
                        it.isNoArgBoolean()
                }
        }

    /** `MainTabUI.g()`: the current home tab, asked for `onKeyDown` before moving the task back. */
    private val methodCurrentTabFragment by dexMethodFrom {
        methodLauncherDispatchKeyEvent.data.invokes
            .distinctBy { it.descriptor }
            .single {
                it.declaredClassName == MAIN_TAB_UI &&
                    it.paramCount == 0 &&
                    it.returnTypeName == MM_FRAGMENT
            }
    }

    // --- Dex: MainUI.onKeyDown closes the conversation list's pull-down header ---

    private val methodMainUiOnKeyDown by dexMethod {
        declaredClass = "com.tencent.mm.ui.conversation.MainUI"
        name = "onKeyDown"
        returnType = "boolean"
        paramTypes("int", "android.view.KeyEvent")
    }

    /**
     * MainUI's conversation list host (a ConversationListView, or the list-host interface on
     * 8.0.78+).
     */
    private val fieldMainUiListHost by dexFieldFrom {
        methodMainUiOnKeyDown.data.readFields().single { it.declaredClassName == MAIN_UI }
    }

    /** The list host's `isHeaderOpen()`. */
    private val methodListHostHeaderOpen by dexMethodFrom {
        methodMainUiOnKeyDown.data.invokes
            .distinctBy { it.descriptor }
            .single {
                it.declaredClassName == fieldMainUiListHost.data.typeName && it.isNoArgBoolean()
            }
    }

    // --- Dex: the chat FootComponent's key handler, the only chat component that takes BACK ---

    private val classFootComponent by dexClass {
        searchPackages("com.tencent.mm.ui.chatting.component")
        usingEqStrings(
            "MicroMsg.ChattingUI.FootComponent",
            "onNotifyChange event %s talker %s",
        )
    }

    private val methodFootKeyHandler by dexMethod {
        declaredClass(classFootComponent.data.name)
        returnType = "boolean"
        paramTypes("int", "android.view.KeyEvent")
        usingEqStrings("chatting onKeyDown, code:%d action:%d")
    }

    /** `ChatFooter`'s voice-input (speech-to-text) panel flag. */
    private val fieldFooterVoiceInput by dexFieldFrom {
        methodFootKeyHandler.data
            .readFields()
            .filter { it.declaredClassName == CHAT_FOOTER }
            .single { it.typeName == "boolean" }
    }

    /** `ChatFooter`'s full-screen text edit helper. */
    private val fieldFooterFullScreenEdit by dexFieldFrom {
        val handler = methodFootKeyHandler.data
        handler
            .readFields()
            .filter { it.declaredClassName == CHAT_FOOTER }
            .single { field ->
                handler.invokes
                    .distinctBy { it.descriptor }
                    .any {
                        it.declaredClassName == field.typeName && it.isNoArgBoolean()
                    }
            }
    }

    /** The full-screen edit helper's `isActive()`. */
    private val methodFullScreenEditActive by dexMethodFrom {
        methodFootKeyHandler.data.invokes
            .distinctBy { it.descriptor }
            .single {
                it.declaredClassName == fieldFooterFullScreenEdit.data.typeName &&
                    it.isNoArgBoolean()
            }
    }

    // --- Dex: SwipeBackLayout's gesture switch (off during the chat enter animation and after
    // pop-out) ---

    private val fieldSwipeBackGestureEnabled by dexField {
        declaredClass = "com.tencent.mm.ui.widget.SwipeBackLayout"
        type = "boolean"
        writeMethod {
            declaredClass = "com.tencent.mm.ui.widget.SwipeBackLayout"
            name = "setEnableGesture"
        }
    }

    // --- Dex: MMActivity.onKeyDown's controller collapses an expanded action-bar search on BACK
    // ---

    private val methodControllerKeyDown by dexMethod {
        searchPackages("com.tencent.mm.ui")
        returnType = "boolean"
        paramTypes("int", "android.view.KeyEvent")
        usingEqStrings("MicroMsg.MMActivityController", "dancy onKeyDown ++")
    }

    /** The controller's action-bar search helper. */
    private val fieldControllerSearchHelper by dexFieldFrom {
        val controller = methodControllerKeyDown.data
        val fields =
            controller.readFields().filter { it.declaredClassName == controller.declaredClassName }
        val handler =
            controller.invokes
                .distinctBy { it.descriptor }
                .single { invoke ->
                    invoke.paramTypeNames == listOf("int", "android.view.KeyEvent") &&
                        invoke.returnTypeName == "boolean" &&
                        fields.any { it.typeName == invoke.declaredClassName }
                }
        fields.single { it.typeName == handler.declaredClassName }
    }

    /** The search helper's expanded flag, which its BACK handler checks. */
    private val fieldSearchExpanded by dexFieldFrom {
        val controller = methodControllerKeyDown.data
        val fields =
            controller.readFields().filter { it.declaredClassName == controller.declaredClassName }
        val handler =
            controller.invokes
                .distinctBy { it.descriptor }
                .single { invoke ->
                    invoke.paramTypeNames == listOf("int", "android.view.KeyEvent") &&
                        invoke.returnTypeName == "boolean" &&
                        fields.any { it.typeName == invoke.declaredClassName }
                }
        handler.readFields().single {
            it.declaredClassName == handler.declaredClassName && it.typeName == "boolean"
        }
    }

    // --- Dex: the UIC every GloUIComponentActivity imports only forwards BACK to registered
    // children ---

    /**
     * The global UIC's children by tag. Children (text status and Finder previews) register and
     * unregister through their interface's `(MMFragmentActivity)` default methods.
     */
    private val fieldGlobalUicChildren by dexField {
        declaredClass {
            superClass {
                className = UI_COMPONENT
            }
        }
        type = "java.util.HashMap"
        readMethod {
            name = "onBackPressed"
            paramCount = 0
            returnType = "boolean"
        }
        readMethod {
            paramTypes(MM_FRAGMENT_ACTIVITY)
            returnType = "void"
        }
    }

    // --- Dex: the Moments timeline header collapses its pulled-down cover on BACK ---

    /**
     * The cover's stage: 1 collapsed, 2 pulled down, 3 animating. Read by the cover's
     * `onBackPressed` (inlined into `ImproveHeaderUIC.onBackPressed`) and set to 3 by
     * `updateViewHeight`.
     */
    private val fieldSnsCoverStage by dexField {
        declaredClass = "com.tencent.mm.plugin.sns.ui.improve.cover.SnsImproveCoverLayout"
        type = "int"
        readMethod {
            usingEqStrings(
                "onBackPressed",
                "com.tencent.mm.plugin.sns.ui.improve.cover.SnsImproveCoverLayout",
            )
        }
        writeMethod {
            usingEqStrings(
                "updateViewHeight",
                "com.tencent.mm.plugin.sns.ui.improve.cover.SnsImproveCoverLayout",
            )
        }
    }

    private fun DexMethodInfo.isNoArgBoolean() = paramCount == 0 && returnTypeName == "boolean"

    private fun DexMethodInfo.readFields(): List<DexFieldInfo> =
        usingFields
            .filter { it.usingType == FieldUsingType.Read }
            .map { it.field }
            .distinctBy { it.descriptor }

    // --- Which activities are opted in ---

    enum class HostKind {
        LAUNCHER,
        CONVERSATION,
        STANDARD,
    }

    private val hostKinds = HashMap<String, HostKind?>()

    /**
     * How [className] is handled, or null to keep it legacy: it is not a WeChat MMFragmentActivity,
     * or a class of its own (below the bases this feature models) handles back itself, unless that
     * handler is one of the verified [leavingBackHandlers].
     */
    fun hostKindOf(className: String): HostKind? {
        if (className in hostKinds) return hostKinds[className]
        val kind = classify(className)
        hostKinds[className] = kind
        return kind
    }

    private fun classify(className: String): HostKind? {
        if (className == LAUNCHER_UI) return HostKind.LAUNCHER
        val chain = generateSequence(className.toClassOrNull()) { it.superclass }.toList()
        val modelledBase = chain.indexOfFirst {
            it.name == BASE_CONVERSATION_UI || it.name == MM_FRAGMENT_ACTIVITY
        }
        if (modelledBase < 0) return null
        val ownClasses = chain.subList(0, modelledBase).filter { it.name !in modelledBases }
        val handler =
            if (className in leavingBackHandlers) null
            else ownClasses.firstOrNull { it.declaresBackHandler() }
        if (handler != null) {
            WeLogger.d(TAG, "$className stays legacy: ${handler.name} handles back itself")
            return null
        }
        return if (chain[modelledBase].name == BASE_CONVERSATION_UI) HostKind.CONVERSATION
        else HostKind.STANDARD
    }

    private fun Class<*>.declaresBackHandler(): Boolean =
        reflekt().firstMethodOrNull { name { it in backHandlerNames } } != null

    // --- Runtime state ---

    private val controllers = WeakHashMap<Activity, PredictiveBackController>()
    private val multiTaskUics = WeakHashMap<Activity, Any>()
    private val chatbotUics = WeakHashMap<Activity, Any>()
    private val uicHandlesBack = HashMap<Class<*>, Boolean>()

    // Read on every pre-draw; a String type would go through Class.forName each time.
    private val mainTabUiClass by lazy { MAIN_TAB_UI.toClass() }
    private val mmActivityClass by lazy { MM_ACTIVITY.toClass() }
    private val vasActivityClass by lazy { VAS_ACTIVITY.toClass() }
    private val uiComponentClass by lazy { UI_COMPONENT.toClass() }
    private val globalUicClass by lazy { fieldGlobalUicChildren.field.declaringClass }

    /** Whether [context] belongs to an activity whose back is driven by this feature. */
    fun isOptedIn(context: Context): Boolean =
        controllers.containsKey(context.baseActivity ?: return false)

    // --- LauncherUI ---

    fun isLauncherChatForeground(activity: Activity): Boolean =
        methodChatForeground.method.invoke(chattingTabUi(activity)) as Boolean

    /** False when anything on LauncherUI would consume a BACK before WeChat moves the task back. */
    fun isHomeIdle(activity: Activity): Boolean =
        !isVasPaneVisible(activity) &&
            !isLauncherChatForeground(activity) &&
            !isMultiTaskPanelOpen(activity) &&
            !isChatbotPanelOpen(activity) &&
            !currentTabConsumesBack(activity) &&
            !WeLauncherBackApi.hasActiveHandler(activity)

    fun describeHomeState(activity: Activity): String =
        "vas=${isVasPaneVisible(activity)} chat=${isLauncherChatForeground(activity)} " +
            "multiTask=${isMultiTaskPanelOpen(activity)} chatbot=${isChatbotPanelOpen(activity)} " +
            "tab=${currentTab(activity)?.javaClass?.name} tabConsumes=${currentTabConsumesBack(activity)} " +
            "weKit=${WeLauncherBackApi.hasActiveHandler(activity)}"

    /** LauncherUI's chat SwipeBackLayout when a BACK would leave the chat right now. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun readyLauncherChatSwipeLayout(activity: Activity): SwipeBackLayout? {
        if (
            isVasPaneVisible(activity) ||
                isMultiTaskPanelOpen(activity) ||
                isChatbotPanelOpen(activity) ||
                WeLauncherBackApi.hasActiveHandler(activity)
        )
            return null
        val chattingTab = chattingTabUi(activity)
        if (!(methodChatForeground.method.invoke(chattingTab) as Boolean)) return null
        val fragment =
            chattingTab.reflekt().firstField { type = BASE_CHATTING_UI_FRAGMENT }.get()
                as MMFragment
        return readyChatSwipeLayout(activity, fragment)
    }

    private fun chattingTabUi(activity: Activity): Any =
        activity.reflekt().getField("chattingTabUI")!!

    private fun isVasPaneVisible(activity: Activity): Boolean =
        methodVasPaneVisible.method.invoke(activity) as Boolean

    /** Null before the tab's fragment has been created. */
    private fun currentTab(activity: Activity): Any? {
        val homeUi = activity.reflekt().firstMethod { name = "getHomeUI" }.invoke()!!
        val mainTabUi = homeUi.reflekt().firstField { type = mainTabUiClass }.get()!!
        return methodCurrentTabFragment.method.invoke(mainTabUi)
    }

    /** `MainUI` closes its pull-down header and `MoreTabUI` its story gallery on BACK. */
    private fun currentTabConsumesBack(activity: Activity): Boolean {
        val tab = currentTab(activity) ?: return false
        if (tab.javaClass.isNamed(MAIN_UI)) {
            val host = fieldMainUiListHost.field.get(tab) ?: return false
            return methodListHostHeaderOpen.method.invoke(host) as Boolean
        }
        if (tab.javaClass.isNamed(MORE_TAB_UI)) {
            // Conservative: the gallery's open state is not readable without side effects.
            val gallery = tab.reflekt().getField("storyGallery") as View?
            return gallery?.isShown == true
        }
        return false
    }

    // --- BaseConversationUI: `dispatchKeyEvent` = isAnimating → MultiTask → chat → default chain
    // ---

    /** Null until the first chat is opened. */
    private fun conversationChat(activity: Activity): MMFragment? =
        activity.reflekt().getField("chattingFragmet", true) as MMFragment?

    private fun isConversationAnimating(activity: Activity): Boolean =
        activity.reflekt().getField("isAnimating", true) as Boolean

    fun isConversationChatForeground(activity: Activity): Boolean =
        conversationChat(activity)?.let(::isChatFragmentForeground) == true

    fun isConversationIdle(activity: Activity): Boolean =
        !isConversationAnimating(activity) &&
            !isConversationChatForeground(activity) &&
            areStandardConsumersIdle(activity)

    /** The conversation list's chat SwipeBackLayout when a BACK would leave the chat right now. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun readyConversationChatSwipeLayout(activity: Activity): SwipeBackLayout? {
        if (isConversationAnimating(activity) || isMultiTaskPanelOpen(activity)) return null
        val fragment = conversationChat(activity) ?: return null
        if (!isChatFragmentForeground(fragment)) return null
        return readyChatSwipeLayout(activity, fragment)
    }

    // --- Chat fragment (shared by LauncherUI and BaseConversationUI) ---

    private fun isChatFragmentForeground(fragment: MMFragment): Boolean {
        val chattingContext = fieldFragmentChattingContext.field.get(fragment)!!
        return fieldChattingContextForeground.field.getBoolean(chattingContext)
    }

    /**
     * [fragment]'s SwipeBackLayout when its footer key handler would reach `triggerGoBack` (no
     * panel, voice input, full-screen edit, language choice or multi-select) and the swipe engine
     * is idle, otherwise null.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readyChatSwipeLayout(activity: Activity, fragment: MMFragment): SwipeBackLayout? {
        if (!fragment.isSupportNavigationSwipeBack) return null
        val layout = fragment.swipeBackLayout
        if (!fieldSwipeBackGestureEnabled.field.getBoolean(layout)) return null
        if (layout.targetContentView.left != 0) return null
        if (activity.window.decorView.rootWindowInsets!!.isVisible(WindowInsets.Type.ime()))
            return null
        val root = fragment.view as ViewGroup
        val footer = root.findFirst { it is ChatFooter } as ChatFooter?
        if (footer != null) {
            if (footer.panelType != 0 || fieldFooterVoiceInput.field.getBoolean(footer)) return null
            val fullScreenEdit = fieldFooterFullScreenEdit.field.get(footer)!!
            if (methodFullScreenEditActive.method.invoke(fullScreenEdit) as Boolean) return null
        }
        if (root.findFirst { it.isShown && it.javaClass.isNamed(LANGUAGE_CHOICE_LAYOUT) } != null)
            return null
        if (root.findFirst { it.isShown && it.javaClass.isNamed(MORE_BTN_BAR) } != null) return null
        return layout
    }

    // --- Consumers every opted-in MMFragmentActivity chain shares ---

    /**
     * False when a consumer shared by WeChat's base activities would take a BACK before the
     * activity leaves: MMFragmentActivity's MultiTask panel and swipe-back settle, MMActivity's
     * back listeners and action-bar search, VAS mode, UICs with back handlers, and androidx
     * `OnBackPressedCallback`s (fragment back stacks).
     */
    fun areStandardConsumersIdle(activity: Activity): Boolean =
        !isMultiTaskPanelOpen(activity) &&
            !isActivitySwipeSettling(activity) &&
            !isVasMode(activity) &&
            !hasBackListeners(activity) &&
            !isActionBarSearchExpanded(activity) &&
            !hasBackHandlingUic(activity) &&
            !hasEnabledAndroidxBackCallback(activity)

    fun describeStandardState(activity: Activity): String =
        "multiTask=${isMultiTaskPanelOpen(activity)} swipeSettling=${isActivitySwipeSettling(activity)} " +
            "vas=${isVasMode(activity)} listeners=${hasBackListeners(activity)} " +
            "search=${isActionBarSearchExpanded(activity)} uic=${backHandlingUic(activity)?.javaClass?.name} " +
            "androidx=${hasEnabledAndroidxBackCallback(activity)}"

    private fun isMultiTaskPanelOpen(activity: Activity): Boolean {
        val uic = multiTaskUics[activity] ?: return false
        return methodMultiTaskPanelOpen.method.invoke(uic) as Boolean
    }

    private fun isChatbotPanelOpen(activity: Activity): Boolean {
        val uic = chatbotUics[activity] ?: return false
        return methodChatbotPanelOpen.method.invoke(uic) as Boolean
    }

    /**
     * MMFragmentActivity swallows BACK while its own swipe-back is settling. Null when swipe-back
     * is off.
     */
    private fun isActivitySwipeSettling(activity: Activity): Boolean {
        val layout =
            activity
                .reflekt()
                .firstMethod {
                    name = "getSwipeBackLayout"
                    superclass()
                }
                .invoke() as? SwipeBackLayout? ?: return false
        return layout.targetContentView.left != 0
    }

    private fun isVasMode(activity: Activity): Boolean =
        vasActivityClass.isInstance(activity) &&
            activity
                .reflekt()
                .firstMethod {
                    name = "isVASValid"
                    superclass()
                }
                .invoke() as Boolean

    private fun hasBackListeners(activity: Activity): Boolean =
        mmActivityClass.isInstance(activity) &&
            (activity.reflekt().getField("onBackPressListeners", true) as List<*>).isNotEmpty()

    /** Null helper: the activity has no action-bar search. */
    private fun isActionBarSearchExpanded(activity: Activity): Boolean {
        if (!mmActivityClass.isInstance(activity)) return false
        val controller = activity.reflekt().getField("mController", true)!!
        val helper = fieldControllerSearchHelper.field.get(controller) ?: return false
        return fieldSearchExpanded.field.getBoolean(helper)
    }

    private fun hasBackHandlingUic(activity: Activity): Boolean = backHandlingUic(activity) != null

    /**
     * The first UIC that would consume BACK, or null. MultiTask and chatbot UICs only consume it
     * while their panels are open, the global UIC only through registered children and the Moments
     * header only while its cover is not collapsed, which are read directly; [nonConsumingUics]
     * never consume. Any other UIC that overrides a back handler counts as consuming.
     */
    private fun backHandlingUic(activity: Activity): Any? {
        val uics =
            activity
                .reflekt()
                .firstMethod {
                    name = "getUiComponents"
                    superclass()
                }
                .invoke() as Collection<*>
        return uics.firstOrNull { uic ->
            val clazz = uic!!.javaClass
            when {
                clazz.isNamed(MULTITASK_UIC) -> false
                clazz.isNamed(CHATBOT_UIC) && methodChatbotPanelOpen.isFound -> false
                clazz == globalUicClass ->
                    (fieldGlobalUicChildren.field.get(uic) as Map<*, *>).isNotEmpty()
                clazz.isNamed(SNS_HEADER_UIC) -> isSnsCoverOpen(uic)
                clazz.name in nonConsumingUics -> false
                else -> uicHandlesBack.getOrPut(clazz) { clazz.uicDeclaresBackHandler() }
            }
        }
    }

    /** `ImproveHeaderUIC.onBackPressed` takes BACK unless the cover is collapsed (stage 1). */
    private fun isSnsCoverOpen(headerUic: Any): Boolean {
        val cover =
            headerUic
                .reflekt()
                .firstMethod {
                    returnType = SNS_COVER_LAYOUT
                    parameters()
                }
                .invoke()!!
        return fieldSnsCoverStage.field.getInt(cover) != 1
    }

    private fun Class<*>.uicDeclaresBackHandler(): Boolean {
        var current: Class<*> = this
        while (current != uiComponentClass) {
            // WeKit's injected setting items (WeChatSettingsManager) are dexmaker proxies: they
            // override every method, but forward the back handlers to super.
            if (!ProxyBuilder.isProxyClass(current) && current.declaresBackHandler()) return true
            current = current.superclass!!
        }
        return false
    }

    /**
     * WeChat's androidx `OnBackPressedDispatcher`, whose enabled callbacks run before Activity's
     * default.
     */
    private fun hasEnabledAndroidxBackCallback(activity: Activity): Boolean {
        val dispatcher =
            activity
                .reflekt()
                .firstMethod {
                    name = "getOnBackPressedDispatcher"
                    superclass()
                }
                .invoke()!!
        val callbacks =
            dispatcher.reflekt().firstField { type = ArrayDeque::class }.get() as ArrayDeque<*>
        return callbacks.any { callback ->
            callback!!
                .reflekt()
                .firstMethod {
                    name = "isEnabled"
                    superclass()
                }
                .invoke() as Boolean
        }
    }

    private fun Class<*>.isNamed(className: String): Boolean {
        var current: Class<*>? = this
        while (current != null) {
            if (current.name == className) return true
            current = current.superclass
        }
        return false
    }

    private fun View.findFirst(predicate: (View) -> Boolean): View? {
        if (predicate(this)) return this
        if (this !is ViewGroup) return null
        for (i in 0 until childCount) {
            getChildAt(i).findFirst(predicate)?.let {
                return it
            }
        }
        return null
    }

    // --- Hooks ---

    @SuppressLint("NewApi") // declaredErrors keeps this feature off below API 34.
    override fun onEnable() {
        ApplicationInfo::class.reflekt()
            .firstConstructor {
                parameters(ApplicationInfo::class.java)
            }
            .hookAfter {
                val info = args[0] as ApplicationInfo
                val field = info.reflekt().firstField { name = "privateFlagsExt" }
                field.set(
                    field.get() as Int or
                        PredictiveBackFlags.PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK,
                )
            }

        ActivityInfo::class.reflekt().firstConstructor().hookAfter {
            val info = thisObject as ActivityInfo
            if (isModuleActivity(info)) PredictiveBackFlags.enable(info)
        }

        ActivityThread::class.reflekt()
            .firstMethod { name = "handleLaunchActivity" }
            .hookBefore {
                val record = args[0]!!
                val info =
                    record.reflekt().firstField { name = "activityInfo" }.get() as ActivityInfo
                val intent = record.reflekt().firstField { name = "intent" }.get() as Intent?
                if (isModuleActivity(info, intent) || hostKindOf(info.name) != null)
                    PredictiveBackFlags.enable(info)
            }

        Activity::class.reflekt()
            .firstMethod {
                name = "performCreate"
                parameters(Bundle::class, PersistableBundle::class)
            }
            .hookAfter {
                val activity = thisObject as Activity
                val kind = hostKindOf(activity.javaClass.name) ?: return@hookAfter
                val host =
                    when (kind) {
                        HostKind.LAUNCHER -> LauncherBackHost(activity)
                        HostKind.CONVERSATION -> ConversationBackHost(activity)
                        HostKind.STANDARD -> StandardBackHost(activity)
                    }
                val controller = PredictiveBackController.attachTo(activity, host)
                WeLogger.d(
                    TAG,
                    "${activity.javaClass.name}: ${if (controller != null) "$kind predictive back" else "not opted in by the platform"}",
                )
                if (controller != null) controllers[activity] = controller
            }

        Activity::class.reflekt()
            .firstMethod { name = "performDestroy" }
            .hookBefore {
                controllers.remove(thisObject as Activity)?.detach()
            }

        MULTITASK_UIC.toClass()
            .reflekt()
            .firstConstructor { parameters(APP_COMPAT_ACTIVITY) }
            .hookAfter { multiTaskUics[args[0] as Activity] = thisObject }

        if (methodChatbotPanelOpen.isFound) {
            CHATBOT_UIC.toClass()
                .reflekt()
                .firstConstructor { parameters(APP_COMPAT_ACTIVITY) }
                .hookAfter { chatbotUics[args[0] as Activity] = thisObject }
        }

        Activity::class.reflekt()
            .firstMethod {
                name = "moveTaskToBack"
                parameters(Boolean::class)
            }
            .hookBefore { controllers[thisObject as Activity]?.onLeaving() }

        // Activity's own default back (reached when a legacy chain falls through to
        // Activity.onBackPressed).
        Activity::class.reflekt()
            .firstMethod {
                name = "onBackInvoked"
                parameters()
            }
            .hookBefore { controllers[thisObject as Activity]?.onLeaving() }

        val dispatchTouchEvent =
            SwipeBackLayout::class.reflekt().firstMethod { name = "dispatchTouchEvent" }
        dispatchTouchEvent.hookBefore {
            // Real touches must not reach an engine we are driving (on 15+ the gesture's own
            // CANCEL arrives right after onBackStarted; the synthetic DOWN already reset children).
            if (!ChatSwipeDriver.injecting && ChatSwipeDriver.isDriving(thisObject as View))
                result = true
        }
        dispatchTouchEvent.hookAfter {
            if (ChatSwipeDriver.injecting) return@hookAfter
            val event = args[0] as MotionEvent
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return@hookAfter
            val layout = thisObject as SwipeBackLayout
            if (!isOptedIn(layout.context)) return@hookAfter
            // An edge touch inside the system gesture inset belongs to the system back gesture.
            // Real touches reach the app until the gesture is recognized, and WeChat's own swipe
            // (in-window chats and activity-level swipe-back alike) would race it; 3-button nav
            // has no inset, so WeChat's swipe keeps working there.
            val insetLeft =
                layout.rootWindowInsets!!.getInsets(WindowInsets.Type.systemGestures()).left
            val location = IntArray(2)
            layout.getLocationInWindow(location)
            if (location[0] + event.x < insetLeft) layout.setOnceDisEnableGesture(true)
        }

        Dialog::class.reflekt()
            .firstMethod { name = "show" }
            .hookAfter {
                val dialog = thisObject as Dialog
                if (dialog.isShowing) DialogBackBridge.onDialogShowing(dialog)
            }

        Dialog::class.reflekt()
            .firstMethod {
                name = "setOnKeyListener"
                parameters(DialogInterface.OnKeyListener::class)
            }
            .hookAfter {
                val dialog = thisObject as Dialog
                if (dialog.isShowing) DialogBackBridge.onDialogShowing(dialog)
            }
    }

    @SuppressLint("NewApi") // declaredErrors keeps this feature off below API 34.
    override fun onDisable() {
        controllers.values.forEach { it.detach() }
        controllers.clear()
        DialogBackBridge.clear()
    }

    /**
     * [dev.ujhhgtg.wekit.loader.utils.ActivityProxy] recovers the target Intent before
     * ActivityThread launches a module Activity, but must keep the host stub's ActivityInfo.
     * Inspect the recovered component as well, so the host ActivityInfo receives the flags for the
     * Activity it will instantiate.
     */
    private fun isModuleActivity(info: ActivityInfo, intent: Intent? = null): Boolean =
        info.name?.startsWith(PackageNames.MODULE) == true ||
            intent?.component?.className?.startsWith(PackageNames.MODULE) == true
}
