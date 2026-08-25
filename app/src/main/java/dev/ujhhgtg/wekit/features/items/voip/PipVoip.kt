package dev.ujhhgtg.wekit.features.items.voip

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.View
import android.widget.FrameLayout
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.core.os.BundleCompat
import com.tencent.mm.plugin.multitalk.ui.MultiTalkMainUI
import com.tencent.mm.plugin.voip.ui.VideoActivity
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.PipVoipActivity
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.contacts.SplitGroupCall
import dev.ujhhgtg.wekit.loader.entry.zygisk.ZygiskLoaderService
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.Intent
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.WeakHashMap

/**
 * 用系统画中画代替微信通话最小化时的悬浮窗。
 *
 * 微信有三套通话实现，本功能都要照顾到：
 *  - 新版 VoIPMP（8.0.69 起的单人语音/视频通话，UI 是 Flutter，逻辑在 native core 里，
 *    通过 ZIDL 调用；最小化时把 Flutter `small_window` 页塞进「悬浮球」框架）；
 *  - 旧版 FlutterVoip（`FlutterVoipMgr`，仅在没有走 VoIPMP 时使用）；
 *  - 多人通话 [MultiTalkMainUI]。
 *
 * 统一的拦截点是微信自己的悬浮球：`VoipFloatBallHelper.addVoipView(...)`。
 * 它只在微信准备显示最小化悬浮窗时被调用，屏蔽它就等于屏蔽了微信的悬浮窗，
 * 与具体是哪套通话实现无关。多人通话有自己的悬浮球 helper，因此额外拦截
 * `onMiniMultiTalk`。
 *
 * **只能在 LSPosed 模式下使用。** [PipVoipActivity] 必须跑在模块自己的进程里：
 * system_server 校验的是被启动组件在清单里的 `supportsPictureInPicture`
 * （`ActivityRecord.supportsPictureInPicture()`），微信没有任何 Activity 声明过它，
 * 所以借宿主 stub 的话画中画一定被拒。Zygisk 模式下模块应用根本没有安装，
 * 因此本功能在该模式下直接停用。
 */
object PipVoip : SwitchFeature(), IResolveDex {

    override val technicalId = "音视频通话使用画中画"
    override val nameRes = R.string.feature_pip_voip_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT, FeatureCategoryIds.VOIP)
    override val descriptionRes = R.string.feature_pip_voip_description

    private const val TAG = "PipVoip"
    private const val HANGUP_SCENE = 4103

    /** VoIPMP core 的 SetAppCmd 命令号：静音 / 取消静音麦克风 */
    private const val CMD_MUTE_MIC = 412
    private const val CMD_UNMUTE_MIC = 413

    /**
     * Zygisk 模式下模块应用没有安装，[PipVoipActivity] 无处可跑，本功能不可用。
     *
     * 在模块应用自己的进程里 [StartupInfo.loaderService] 没有初始化，那种情况下
     * 一定是 LSPosed 模式（否则模块应用不存在）。
     */
    private val isZygiskMode: Boolean by lazy {
        runCatching { StartupInfo.loaderService is ZygiskLoaderService }.getOrDefault(false)
    }

    override val shouldEnableOnStartup: Boolean
        get() = super.shouldEnableOnStartup && !isZygiskMode

    // ---------------------------------------------------------------- sessions

    /** 一次进行中的通话，屏蔽掉三套实现之间的差异。 */
    private abstract class Session {

        var pipActive = false

        /** 画中画进程回传的命令通道（[PipVoipActivity.RESULT_READY]） */
        private var commands: ResultReceiver? = null

        /** 日志用的名字；release 构建里类名已经被混淆了 */
        abstract val label: String

        open val groupCall = false
        open val videoEnabled = true
        open val alive = true

        abstract val micMuted: Boolean

        abstract fun hangUp()
        abstract fun toggleMic()
        open fun toggleVideo() = Unit
        abstract fun restore()

        /** 画中画已经起来了；通话界面还活着的实现可以借此把微信退到后台。 */
        open fun onEnteredPip() = Unit

        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                when (resultCode) {
                    PipVoipActivity.RESULT_READY -> {
                        commands = resultData?.let {
                            // ResultReceiver is an anonymous PipVoipActivity class. This Bundle is
                            // unmarshalled in WeChat, whose default class loader cannot see it.
                            it.classLoader = PipVoipActivity::class.java.classLoader
                            BundleCompat.getParcelable(
                                it,
                                PipVoipActivity.EXTRA_COMMAND_RECEIVER,
                                ResultReceiver::class.java,
                            )
                        }
                    }

                    PipVoipActivity.RESULT_HANG_UP -> {
                        markPipClosed()
                        act("hangUp") { hangUp() }
                    }

                    PipVoipActivity.RESULT_TOGGLE_MIC -> act("toggleMic") { toggleMic() }
                    PipVoipActivity.RESULT_TOGGLE_VIDEO -> act("toggleVideo") { toggleVideo() }

                    PipVoipActivity.RESULT_RESTORE -> {
                        markPipClosed()
                        act("restore") { restore() }
                    }

                    PipVoipActivity.RESULT_CLOSED -> {
                        markPipClosed()
                        commands = null
                    }
                }
            }
        }

        fun enterPip() {
            if (pipActive) return
            val muted = act("micMuted") { micMuted } ?: false
            pipActive = true
            pipSession = this
            WeLogger.i(TAG, "entering pip via $label, micMuted=$muted")
            runCatching {
                // 必须跑在模块自己的进程里：只有模块清单里的 PipVoipActivity 声明了
                // supportsPictureInPicture，宿主的 stub 借不到这个 flag
                HostInfo.application.startActivity(
                    Intent {
                        component = ComponentName(
                            PackageNames.MODULE,
                            PipVoipActivity::class.java.name,
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(PipVoipActivity.EXTRA_GROUP_CALL, groupCall)
                        putExtra(PipVoipActivity.EXTRA_MIC_MUTED, muted)
                        putExtra(PipVoipActivity.EXTRA_VIDEO_ENABLED, videoEnabled)
                        putExtra(PipVoipActivity.EXTRA_RESULT_RECEIVER, receiver)
                    }
                )
            }.onFailure {
                pipActive = false
                if (pipSession === this) pipSession = null
                WeLogger.e(TAG, "failed to start pip activity", it)
            }
            if (pipActive) act("onEnteredPip") { onEnteredPip() }
        }

        fun closePip() {
            if (!pipActive) return
            pipActive = false
            if (pipSession === this) pipSession = null
            val commands = commands
            this.commands = null
            if (commands != null) {
                runCatching { commands.send(PipVoipActivity.COMMAND_CLOSE, null) }
                    .onFailure { WeLogger.w(TAG, "failed to close pip via command channel", it) }
            } else {
                closePipActivityByIntent()
            }
        }

        private fun markPipClosed() {
            pipActive = false
            if (pipSession === this) pipSession = null
        }

        /** 这些回调跑在 Binder 线程上，抛出去只会变成一个不透明的 DeadObjectException。 */
        private fun <T> act(what: String, block: () -> T): T? =
            runCatching(block)
                .onFailure { WeLogger.e(TAG, "$what failed on $label session", it) }
                .getOrNull()
    }

    /** 旧版 FlutterVoip 单人通话 */
    private class LegacySession(
        val activity: Activity,
        val manager: Any,
    ) : Session() {

        override val label = "legacy"
        override val alive get() = !activity.isDestroyed

        override val micMuted: Boolean
            get() {
                val audioManager = fieldVoipAudioManager.field.get(manager)
                return fieldVoipMuted.field.getBoolean(audioManager)
            }

        override fun hangUp() {
            methodVoipHangUp.method.invoke(manager, HANGUP_SCENE)
        }

        override fun toggleMic() {
            methodSetVoipMuted.method.invoke(manager, !micMuted)
        }

        override fun restore() = moveTaskToFront(activity)

        override fun onEnteredPip() {
            activity.moveTaskToBack(true)
        }
    }

    /** 多人通话 */
    private class GroupSession(
        val activity: Activity,
    ) : Session() {

        override val label = "multitalk"
        override val groupCall = true
        override val alive get() = !activity.isDestroyed

        override val micMuted: Boolean
            get() {
                val state = fieldMultiTalkMicState.field.get(viewModel)
                return !(methodObservableValue.method.invoke(state) as Boolean)
            }

        override val videoEnabled: Boolean
            get() {
                val state = fieldMultiTalkCameraState.field.get(viewModel)
                return methodObservableValue.method.invoke(state) as Boolean
            }

        override fun hangUp() {
            methodMultiTalkExit.method.invoke(activity)
        }

        override fun toggleMic() {
            if (methodMultiTalkMic.isPlaceholder) {
                toggleLegacyMultiTalkMic(viewModel)
            } else {
                methodMultiTalkMic.method.invoke(viewModel, true)
            }
        }

        override fun toggleVideo() {
            methodMultiTalkCamera.method.invoke(viewModel, null)
        }

        override fun restore() = moveTaskToFront(activity)

        override fun onEnteredPip() {
            activity.moveTaskToBack(true)
        }

        private val viewModel: Any
            get() = fieldMultiTalkViewModel.field.get(activity)!!
    }

    /** 8.0.65 将多人通话麦克风逻辑内联到 ControlPanelLogic。 */
    private fun toggleLegacyMultiTalkMic(viewModel: Any) {
        val state = fieldMultiTalkMicState.field.get(viewModel)
        val micEnabled = methodObservableValue.method.invoke(state) as Boolean

        state.reflekt().firstMethod {
            name = "setValue"
            parameterCount = 1
        }.invoke(!micEnabled)

        val manager = SplitGroupCall.methodGetMultiTalkManager.method.invoke(null)
        methodMultiTalkManagerMute.method.invoke(manager, micEnabled)

        val engine = methodGetMultiTalkEngine.method.invoke(null)
        methodMultiTalkEngineMic.method.invoke(engine, !micEnabled)
    }

    /**
     * 新版 VoIPMP 单人通话。
     *
     * 这套实现的通话界面在最小化时会被 finish 掉，所以 session 不能挂在 Activity 上；
     * 挂断走 core 的 `CallHangupAsync`，麦克风走 Java 侧的录音器（core 只是被告知状态），
     * 摄像头开关只有 Dart 侧能调用，因此画中画里不提供。
     */
    private object VoipMpSession : Session() {

        override val label = "voipmp"

        override val micMuted: Boolean
            get() = audioController?.let { fieldVoipMpMicMuted.field.getBoolean(it) } ?: false

        override fun hangUp() {
            if (methodVoipMpHangUp.isPlaceholder) {
                WeLogger.w(TAG, "voipmp CallHangup wasn't resolved, cannot hang up")
                return
            }
            val core = voipMpCore ?: error("voipmp core sdk is unavailable")
            methodVoipMpHangUp.method.invoke(core, false, hangUpCallback)
        }

        override fun toggleMic() {
            val controller = audioController ?: error("voipmp audio controller is unavailable")
            val muted = !micMuted
            val recorder = methodVoipMpAudioCapturer.method.invoke(controller)
                ?.let { fieldVoipMpRecorder.field.get(it) }
            if (recorder == null) {
                WeLogger.w(TAG, "no active voipmp recorder, mic state left untouched")
                return
            }
            methodVoipMpSwitchMute.method.invoke(recorder, muted)
            fieldVoipMpMicMuted.field.setBoolean(controller, muted)
            sendAppCmd(
                if (muted) CMD_MUTE_MIC else CMD_UNMUTE_MIC,
                if (muted) 0 else 1,
            )
        }

        override fun restore() {
            if (methodVoipMpLaunchPage.isPlaceholder) {
                WeLogger.w(TAG, "voipmp launchPage wasn't resolved, cannot restore call UI")
                return
            }
            val service = voipMpService ?: error("voipmp service is unavailable")
            methodVoipMpLaunchPage.method.invoke(service, HostInfo.application, false)
        }

        private fun sendAppCmd(cmd: Int, value: Int) {
            val core = voipMpCore ?: return
            val buffer = ByteBuffer.allocateDirect(1).apply {
                put(value.toByte())
                position(0)
            }
            methodVoipMpSetAppCmd.method.invoke(core, cmd, buffer, 1)
        }
    }

    /** 只有旧版通话和多人通话的 session 与 Activity 绑定 */
    private val sessions = WeakHashMap<Activity, Session>()

    /**
     * 正在画中画里的通话。
     *
     * 微信最小化时会把通话界面 finish 掉，所以这里单独留一份引用：Activity 没了以后
     * 挂断 / 麦克风还得靠 session 里的 manager 对象。
     */
    private var pipSession: Session? = null

    /** 新版实现的音频控制器实例，构造时抓下来 */
    private var audioController: Any? = null

    /** ZIDL 的完成回调，只有一个 `complete()`；native 会持有它，所以要留强引用 */
    private val hangUpCallback: Any by lazy {
        val iface = methodVoipMpHangUp.method.parameterTypes[1]
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> $$"WeKitPipVoip$ZidlCallback"
                else -> null
            }
        }
    }

    private val voipMpCore: Any? get() = fieldVoipMpCoreInstance.field.get(null)
    private val voipMpService: Any? get() = fieldVoipMpServiceInstance.field.get(null)

    // ------------------------------------------------------------ 微信悬浮球（悬浮窗）

    private val methodBallAddVoipView by dexMethod {
        matcher {
            paramTypes("int", "boolean", View::class.java.name, "long", "boolean")
            returnType = "void"
            usingEqStrings(
                "MicroMsg.VoipFloatBallHelper",
                "addVoipView, no ball, add delayed:%s",
            )
        }
    }

    private val methodBallRemoveVoipView by dexMethod {
        matcher {
            declaredClass(methodBallAddVoipView.data.declaredClassName)
            paramTypes(FrameLayout::class.java.name)
            returnType = "void"
            usingEqStrings("removeVoipView, no ball, view:%s")
        }
    }

    // --------------------------------------------------------------- 新版 VoIPMP

    private val classVoipMpService by dexClass {
        matcher {
            usingEqStrings("MicroMsg.VoIPMP.Launcher", "dismissSmallWindow: ")
        }
    }

    private val fieldVoipMpServiceInstance by dexField {
        matcher {
            declaredClass(classVoipMpService.data.name)
            type(classVoipMpService.data.name)
            modifiers(Modifier.STATIC)
        }
    }

    /** `launchPage(context, needAnimation)`：把通话界面重新拉起来 */
    private val methodVoipMpLaunchPage by dexMethod(allowFailure = true)  {
        matcher {
            declaredClass(classVoipMpService.data.name)
            paramTypes(Context::class.java.name, "boolean")
            returnType = "void"
        }
    }

    /** `dismissSmallWindow()`：微信认为最小化界面该消失了 */
    private val methodVoipMpDismissSmallWindow by dexMethod {
        matcher {
            declaredClass(classVoipMpService.data.name)
            paramCount = 0
            returnType = "void"
            usingEqStrings("MicroMsg.VoIPMP.Launcher", "dismissSmallWindow: ")
        }
    }

    /** `voipmp.VoipmpCoreSdkService` 的 ZIDL 调用方 */
    private val classVoipMpCore by dexClass {
        matcher {
            usingEqStrings("voipmp.VoipmpCoreSdkService@Get")
        }
    }

    private val fieldVoipMpCoreInstance by dexField {
        matcher {
            declaredClass(classVoipMpCore.data.name)
            type(classVoipMpCore.data.name)
            modifiers(Modifier.STATIC)
        }
    }

    /** `SetAppCmd(cmd, payload, length)` */
    private val methodVoipMpSetAppCmd by dexMethod {
        matcher {
            declaredClass(classVoipMpCore.data.name)
            paramTypes("int", ByteBuffer::class.java.name, "int")
            returnType = "int"
        }
    }

    /**
     * `CallHangupAsync(isSubCall, callback)`。
     *
     * 同签名的 ZIDL 包装方法有好几个，靠调用链区分：只有挂断会被
     * `rejectByShortCut` 起的那个协程调用。这条链最脆，找不到就只是画中画里
     * 挂不了电话，不该拖垮整个功能。
     */
    private val methodVoipMpHangUp by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classVoipMpCore.data.name)
            paramTypes("boolean", null)
            returnType = "void"
            addCaller {
                declaredClass {
                    methods {
                        add {
                            name = "<init>"
                            addCaller {
                                usingEqStrings("MicroMsg.VoIPMP.CoreV2", "rejectByShortCut")
                            }
                        }
                    }
                }
            }
        }
    }

    /** `VoIPMPAudioController.muteMicrophone()`，同时用来定位音频控制器类 */
    private val methodVoipMpMuteMic by dexMethod {
        matcher {
            paramCount = 0
            returnType = "boolean"
            usingEqStrings("MicroMsg.VoIPMPAudioCapturer", "muteMicrophone")
        }
    }

    private val fieldVoipMpMicMuted by dexField {
        matcher {
            declaredClass(methodVoipMpMuteMic.data.declaredClassName)
            type = "boolean"
            addWriteMethod {
                usingEqStrings("MicroMsg.VoIPMPAudioCapturer", "muteMicrophone")
            }
        }
    }

    private val classVoipMpAudioCapturer by dexClass {
        matcher {
            usingEqStrings("MicroMsg.VoIPMPAudioCapturer", "release")
        }
    }

    private val methodVoipMpAudioCapturer by dexMethod {
        matcher {
            declaredClass(methodVoipMpMuteMic.data.declaredClassName)
            paramCount = 0
            returnType(classVoipMpAudioCapturer.data.interfaces.single().name)
        }
    }

    /** `MMPcmRecorder.switchMute(mute)` */
    private val methodVoipMpSwitchMute by dexMethod {
        matcher {
            paramTypes("boolean")
            returnType = "void"
            usingEqStrings("MicroMsg.MMPcmRecorder", "switchMute mute:")
        }
    }

    private val fieldVoipMpRecorder by dexField {
        matcher {
            declaredClass(classVoipMpAudioCapturer.data.name)
            type(methodVoipMpSwitchMute.data.declaredClassName)
        }
    }

    // ---------------------------------------------------------- 旧版 FlutterVoip

    private val classBaseVoipManager by dexClass {
        matcher {
            usingEqStrings("MicroMsg.Voip.NewVoipMgr", "hangupTalkingOrCancelInvite")
        }
    }

    private val classFlutterVoipManager by dexClass {
        matcher {
            usingEqStrings("MicroMsg.FlutterVoipMgr", "qipeng, enableMute.")
        }
    }

    private val classVoipAudioManager by dexClass {
        matcher {
            modifiers(Modifier.FINAL)
            usingEqStrings(
                "MicroMsg.VoIP.VoIPAudioManager",
                "requestAudioFocus: gain focus",
                "requestAudioFocus: not gain focus",
            )
        }
    }

    private val classFlutterVoipPlugin by dexClass {
        matcher {
            usingEqStrings(
                "MicroMsg.FlutterVoipPlugin",
                "minimize: activity=",
                "voip is already minimized, ignore!",
                "minimize, permission denied",
            )
        }
    }

    private val fieldVoipAudioManager by dexField {
        matcher {
            declaredClass(classBaseVoipManager.data.name)
            type(classVoipAudioManager.data.interfaces.single().name)
        }
    }

    private val methodSetVoipMuted by dexMethod {
        matcher {
            declaredClass(classFlutterVoipManager.data.name)
            paramTypes("boolean")
            returnType = "void"
            usingEqStrings("qipeng, enableMute.", "qipeng, disableMute.")
        }
    }

    private val methodVoipHangUp by dexMethod {
        matcher {
            declaredClass(classBaseVoipManager.data.name)
            paramTypes("int")
            returnType = "void"
            usingEqStrings("hangupTalkingOrCancelInvite")
        }
    }

    private val fieldVoipMuted by dexField {
        matcher {
            declaredClass(classVoipAudioManager.data.name)
            type = "boolean"
            addWriteMethod {
                declaredClass(classFlutterVoipManager.data.name)
                paramTypes("boolean")
                usingEqStrings("qipeng, enableMute.", "qipeng, disableMute.")
            }
        }
    }

    private val fieldFlutterVoipActivity by dexField {
        matcher {
            declaredClass(classFlutterVoipPlugin.data.name)
            type(Activity::class.java)
        }
    }

    private val fieldFlutterVoipManager by dexField {
        matcher {
            declaredClass(classFlutterVoipPlugin.data.name)
            type(classFlutterVoipManager.data.name)
        }
    }

    private val methodFlutterVoipAttachedToActivity by dexMethod {
        matcher {
            declaredClass(classFlutterVoipPlugin.data.name)
            paramCount = 1
            returnType = "void"
            usingEqStrings("onAttachedToActivity: ", "init flutter voip mgr")
        }
    }

    private val methodFlutterVoipReattachedToActivity by dexMethod {
        matcher {
            declaredClass(classFlutterVoipPlugin.data.name)
            paramCount = 1
            returnType = "void"
            usingEqStrings("onReattachedToActivityForConfigChanges:")
        }
    }

    // -------------------------------------------------------------------- 多人通话

    private val classMultiTalkViewModel by dexClass()

    private val fieldMultiTalkViewModel by dexField()

    private val classObservableState by dexClass {
        searchPackages("androidx.lifecycle")
        matcher {
            modifiers(Modifier.PUBLIC or Modifier.ABSTRACT)
            methods {
                add {
                    name = "getValue"
                    paramCount = 0
                    returnType(Any::class.java)
                }
                add {
                    name = "hasObservers"
                    paramCount = 0
                    returnType = "boolean"
                }
            }
        }
    }

    private val classMutableObservableState by dexClass {
        searchPackages("androidx.lifecycle")
        matcher {
            superClass = classObservableState.data.name
            methods {
                add {
                    name = "setValue"
                    paramTypes("java.lang.Object")
                    returnType = "void"
                }
            }
        }
    }

    private val methodMultiTalkMinimize by dexMethod()

    private val methodMultiTalkExit by dexMethod()

    private val methodMultiTalkMic by dexMethod()

    private val methodMultiTalkCamera by dexMethod()

    private val classMultiTalkManager by dexClass()

    private val methodMultiTalkManagerMute by dexMethod()

    private val classMultiTalkEngine by dexClass()

    private val methodMultiTalkEngineMic by dexMethod()

    private val methodGetMultiTalkEngine by dexMethod()

    private val fieldMultiTalkMicState by dexField()

    private val fieldMultiTalkCameraState by dexField()

    private val methodObservableValue by dexMethod {
        matcher {
            declaredClass(classObservableState.data.name)
            paramCount = 0
            returnType(Any::class.java)
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        val multiTalkViewModels = dexKit.findClass {
            matcher {
                usingEqStrings(
                    "MicroMsg.MT.MultiTalkUIViewModel",
                    "onCameraClick, cur state: ",
                )
            }
        }

        when (multiTalkViewModels.size) {
            1 -> classMultiTalkViewModel.setDescriptor(multiTalkViewModels.single())
            0 -> {
                val reason = "legacy MultiTalk UI architecture is absent"
                classMultiTalkViewModel.setPlaceholderDescriptor(true, reason)
                fieldMultiTalkViewModel.setPlaceholderDescriptor(true, reason)
                methodMultiTalkMinimize.setPlaceholderDescriptor(true, reason)
                methodMultiTalkExit.setPlaceholderDescriptor(true, reason)
                methodMultiTalkMic.setPlaceholderDescriptor(true, reason)
                methodMultiTalkCamera.setPlaceholderDescriptor(true, reason)
                classMultiTalkManager.setPlaceholderDescriptor(true, reason)
                methodMultiTalkManagerMute.setPlaceholderDescriptor(true, reason)
                classMultiTalkEngine.setPlaceholderDescriptor(true, reason)
                methodMultiTalkEngineMic.setPlaceholderDescriptor(true, reason)
                methodGetMultiTalkEngine.setPlaceholderDescriptor(true, reason)
                fieldMultiTalkMicState.setPlaceholderDescriptor(true, reason)
                fieldMultiTalkCameraState.setPlaceholderDescriptor(true, reason)
                return
            }

            else -> error(
                "multiple MultiTalk UI view models found: " +
                    multiTalkViewModels.joinToString { it.name }
            )
        }

        fieldMultiTalkViewModel.find(dexKit) {
            matcher {
                declaredClass(MultiTalkMainUI::class.java)
                type(classMultiTalkViewModel.data.name)
            }
        }

        methodMultiTalkMinimize.find(dexKit) {
            matcher {
                declaredClass(MultiTalkMainUI::class.java)
                paramCount = 0
                returnType = "void"
                usingEqStrings("onMiniMultiTalk")
            }
        }

        methodMultiTalkExit.find(dexKit) {
            matcher {
                declaredClass(MultiTalkMainUI::class.java)
                paramCount = 0
                returnType = "void"
                usingEqStrings("onExitMultiTalk")
            }
        }

        methodMultiTalkCamera.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.MT.MultiTalkUIViewModel", "onCameraClick, cur state: ")
            }
        }

        classMultiTalkManager.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.MT.MultiTalkManager", "hy: set mute record: %b")
            }
        }

        methodMultiTalkManagerMute.find(dexKit) {
            matcher {
                declaredClass(classMultiTalkManager.data.name)
                paramTypes("boolean")
                returnType = "void"
                usingEqStrings("MicroMsg.Multitalk.ILinkService", "hy: set mute record: %b")
            }
        }

        require(
            SplitGroupCall.methodGetMultiTalkManager.data.returnTypeName == classMultiTalkManager.data.name
        ) { "shared MultiTalk manager getter return type does not match the UI manager" }

        classMultiTalkEngine.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.MT.MultiTalkEngine", "setEngineMicOn, %s")
            }
        }

        methodMultiTalkEngineMic.find(dexKit) {
            matcher {
                declaredClass(classMultiTalkEngine.data.name)
                paramTypes("boolean")
                returnType = "void"
                usingEqStrings("MicroMsg.MT.MultiTalkEngine", "setEngineMicOn, %s")
            }
        }

        methodGetMultiTalkEngine.find(dexKit) {
            matcher {
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
                paramCount = 0
                returnType(classMultiTalkEngine.data.name)
            }
        }

        fieldMultiTalkMicState.find(dexKit) {
            matcher {
                declaredClass(classMultiTalkViewModel.data.name)
                type(classMutableObservableState.data.name)
                addReadMethod {
                    usingEqStrings("onMicClick, cur state: ")
                }
                addReadMethod {
                    declaredClass(MultiTalkMainUI::class.java)
                    usingEqStrings("mMultiTalkGroupMemberList", "usrName")
                }
            }
        }

        fieldMultiTalkCameraState.find(dexKit) {
            matcher {
                declaredClass(classMultiTalkViewModel.data.name)
                type(classObservableState.data.name)
                addReadMethod {
                    usingEqStrings(
                        "MicroMsg.MT.MultiTalkUIViewModel",
                        "onCameraClick, cur state: ",
                    )
                }
            }
        }

        val directMicMethods = dexKit.findMethod {
            matcher {
                declaredClass(classMultiTalkViewModel.data.name)
                paramTypes("boolean")
                returnType = "void"
                usingEqStrings("onMicClick, cur state: ")
            }
        }

        when (directMicMethods.size) {
            1 -> methodMultiTalkMic.setDescriptor(directMicMethods.single())
            0 -> methodMultiTalkMic.setPlaceholderDescriptor(
                expectedFailure = true,
                reason = "direct MultiTalk mic method is absent; using inlined ControlPanelLogic path",
            )

            else -> error(
                "multiple direct MultiTalk mic methods found: ${directMicMethods.joinToString { it.descriptor }}"
            )
        }
    }

    // ------------------------------------------------------------------- 钩子

    override fun onEnable() {
        if (isZygiskMode) {
            WeLogger.w(TAG, "zygisk mode: module app isn't installed, feature unavailable")
            return
        }

        // 新版实现的音频控制器是进程内单例，构造时抓一份，用来读/切麦克风状态。
        // 抓不到也只是画中画里控不了麦克风，不该影响别的
        runCatching {
            methodVoipMpMuteMic.method.declaringClass.reflekt().firstConstructor().hookAfter {
                audioController = thisObject
                WeLogger.d(TAG, "captured voipmp audio controller")
            }
        }.onFailure { WeLogger.e(TAG, "failed to hook voipmp audio controller", it) }

        methodFlutterVoipAttachedToActivity.hookAfter { registerLegacySession(thisObject!!) }
        methodFlutterVoipReattachedToActivity.hookAfter { registerLegacySession(thisObject!!) }

        val multiTalkAvailable = !classMultiTalkViewModel.isPlaceholder
        if (multiTalkAvailable) {
            MultiTalkMainUI::class.reflekt()
                .firstMethod {
                    name = "onCreate"
                    parameterCount = 1
                }
                .hookAfter {
                    val activity = thisObject as Activity
                    sessions[activity] = GroupSession(activity)
                }

            MultiTalkMainUI::class.reflekt()
                .firstMethod {
                    name = "onDestroy"
                    parameterCount = 0
                }
                .hookBefore { removeSession(thisObject as Activity) }
        }

        VideoActivity::class.reflekt()
            .firstMethod {
                name = "onDestroy"
                parameterCount = 0
            }
            .hookBefore { removeSession(thisObject as Activity) }

        // 用户按 home / 切走时提前进入画中画：此时微信还在前台，启动 Activity 不会被拦
        Activity::class.reflekt()
            .firstMethod {
                name = "onUserLeaveHint"
                parameterCount = 0
            }
            .hookBefore {
                when (val activity = thisObject) {
                    is VideoActivity -> currentSession().enterPip()

                    is MultiTalkMainUI -> if (multiTalkAvailable) {
                        activitySession()?.enterPip()
                            ?: WeLogger.w(TAG, "no session for $activity, leaving wechat alone")
                    }
                }
            }

        // 微信要显示自己的悬浮窗了 —— 屏蔽掉，改成画中画。
        // 这个 helper 只服务单人通话，多人通话有自己的一份
        methodBallAddVoipView.hookBefore {
            WeLogger.i(TAG, "suppressing voip float ball (state=${args[0]})")
            currentSession().enterPip()
            result = null
        }

        // 微信自己撤掉最小化界面（通话结束 / 已恢复）时，把画中画也收掉
        methodBallRemoveVoipView.hookBefore { closeActivePip() }
        methodVoipMpDismissSmallWindow.hookBefore { closeActivePip() }

        // 多人通话有自己的悬浮球 helper，直接拦最小化本身
        if (multiTalkAvailable) {
            methodMultiTalkMinimize.hookBefore {
                val session = activitySession() ?: return@hookBefore
                session.enterPip()
                result = null
            }
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState && isZygiskMode) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(stringResource(R.string.feature_pip_voip_name)) },
                    text = {
                        Text(
                            stringResource(R.string.voip_pip_zygisk_unavailable)
                        )
                    },
                    confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } })
            }
            return false
        }

        return true
    }

    override fun onDisable() {
        closeActivePip()
        sessions.clear()
        // audioController 是宿主进程内的单例，留着它，功能中途重新开启时还用得上
    }

    /** 与通话界面绑定的 session：旧版单人通话和多人通话，已经在画中画里的优先 */
    private fun activitySession(): Session? {
        pipSession?.takeIf { it.pipActive }?.let { return it }
        sessions.values.removeAll { !it.alive }
        return sessions.values.firstOrNull()
    }

    /**
     * 当前通话属于哪套实现。没有 Activity 绑定的 session 就说明是新版 VoIPMP：
     * 旧版通话和多人通话都会在界面创建时注册自己的 session。
     */
    private fun currentSession(): Session = activitySession() ?: VoipMpSession

    private fun closeActivePip() {
        pipSession?.closePip()
    }

    private fun registerLegacySession(plugin: Any) {
        val activity = fieldFlutterVoipActivity.field.get(plugin) as? Activity ?: run {
            WeLogger.w(TAG, "flutter voip plugin has no activity attached")
            return
        }
        val manager = fieldFlutterVoipManager.field.get(plugin) ?: run {
            WeLogger.w(TAG, "flutter voip plugin has no manager attached")
            return
        }
        WeLogger.d(TAG, "registering legacy voip session for $activity")
        sessions[activity] = LegacySession(activity, manager)
    }

    /**
     * 通话界面被销毁。注意不能顺手把画中画关掉：微信最小化时就会销毁通话界面，
     * 那时候画中画才刚起来。真正该收掉画中画的时机由微信撤销悬浮窗时的钩子决定。
     */
    private fun removeSession(activity: Activity) {
        val session = sessions.remove(activity) ?: return
        if (session.pipActive)
            WeLogger.d(TAG, "${activity.javaClass.simpleName} destroyed while in pip, keeping session")
    }

    @SuppressLint("MissingPermission")
    private fun moveTaskToFront(activity: Activity) {
        val activityManager =
            activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.moveTaskToFront(activity.taskId, 0)
    }

    /** 命令通道还没建立时的兜底关闭方式 */
    private fun closePipActivityByIntent() {
        runCatching {
            HostInfo.application.startActivity(
                Intent {
                    component = ComponentName(
                        PackageNames.MODULE,
                        PipVoipActivity::class.java.name,
                    )
                    action = PipVoipActivity.ACTION_CLOSE
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            )
        }.onFailure { WeLogger.e(TAG, "failed to close pip activity", it) }
    }
}
