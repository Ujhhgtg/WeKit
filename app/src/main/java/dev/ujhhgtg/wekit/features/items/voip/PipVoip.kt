package dev.ujhhgtg.wekit.features.items.voip

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.View
import com.tencent.mm.plugin.multitalk.ui.MultiTalkMainUI
import com.tencent.mm.plugin.voip.ui.VideoActivity
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.activity.PipVoipActivity
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.Intent
import java.lang.reflect.Modifier
import java.util.WeakHashMap

@Feature(
    name = "音视频通话使用画中画",
    categories = ["聊天", "音视频通话"],
    description = "让微信的音视频通话使用原生的画中画模式而非悬浮窗 (没写完)"
)
object PipVoip : SwitchFeature(), IResolveDex {

    private const val TAG = "PipVoip"
    private const val HANGUP_SCENE = 4103
    private const val FLAG_SUPPORTS_PICTURE_IN_PICTURE = 0x400000
    private const val RESIZE_MODE_RESIZEABLE = 2

    private sealed class Session(val activity: Activity) {
        var pipActive = false

        abstract val micMuted: Boolean
        open val videoEnabled: Boolean = true

        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                when (resultCode) {
                    PipVoipActivity.RESULT_HANG_UP -> {
                        hangUp()
                        pipActive = false
                    }

                    PipVoipActivity.RESULT_TOGGLE_MIC -> toggleMic()
                    PipVoipActivity.RESULT_TOGGLE_VIDEO -> toggleVideo()
                    PipVoipActivity.RESULT_RESTORE -> restoreCallActivity()
                    PipVoipActivity.RESULT_CLOSED -> pipActive = false
                }
            }
        }

        abstract fun hangUp()
        abstract fun toggleMic()
        open fun toggleVideo() = Unit

        fun enterPip() {
            if (pipActive) return
            pipActive = true
            activity.startActivity(
                Intent().apply {
                    component = ComponentName(PackageNames.MODULE, PipVoipActivity::class.java.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(PipVoipActivity.EXTRA_GROUP_CALL, this@Session is GroupSession)
                    putExtra(PipVoipActivity.EXTRA_MIC_MUTED, micMuted)
                    putExtra(PipVoipActivity.EXTRA_VIDEO_ENABLED, videoEnabled)
                    putExtra(PipVoipActivity.EXTRA_RESULT_RECEIVER, receiver)
                }
            )
            activity.moveTaskToBack(true)
        }

        @SuppressLint("MissingPermission")
        private fun restoreCallActivity() {
            pipActive = false
            val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.moveTaskToFront(activity.taskId, 0)
        }
    }

    private class SingleSession(
        activity: VideoActivity,
        val manager: Any,
    ) : Session(activity) {
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
    }

    private class GroupSession(
        val groupActivity: MultiTalkMainUI,
    ) : Session(groupActivity) {
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
            methodMultiTalkExit.method.invoke(groupActivity)
        }

        override fun toggleMic() {
            methodMultiTalkMic.method.invoke(viewModel, true)
        }

        override fun toggleVideo() {
            methodMultiTalkCamera.method.invoke(viewModel, null)
        }

        private val viewModel: Any
            get() = fieldMultiTalkViewModel.field.get(groupActivity)!!
    }

    private val sessions = WeakHashMap<Activity, Session>()

    private val classVoipActivityProxy by dexClass {
        matcher {
            usingEqStrings("MicroMsg.ILinkVoipVideoActivityProxy-")
        }
    }

    private val methodVoipActivityProxyDealContentView by dexMethod {
        matcher {
            declaredClass(classVoipActivityProxy.clazz)
            paramTypes(View::class.java)
            returnType = "void"
        }
    }

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
            declaredClass(classBaseVoipManager.clazz)
            type(classVoipAudioManager.clazz.interfaces.single())
        }
    }

    private val methodVoipMinimize by dexMethod {
        matcher {
            declaredClass(classBaseVoipManager.clazz)
            paramTypes("boolean")
            returnType = "boolean"
            usingEqStrings("onMinimizeVoip, async to minimize")
        }
    }

    private val methodSetVoipMuted by dexMethod {
        matcher {
            declaredClass(classFlutterVoipManager.clazz)
            paramTypes("boolean")
            returnType = "void"
            usingEqStrings("qipeng, enableMute.", "qipeng, disableMute.")
        }
    }

    private val methodVoipHangUp by dexMethod {
        matcher {
            declaredClass(classBaseVoipManager.clazz)
            paramTypes("int")
            returnType = "void"
            usingEqStrings("hangupTalkingOrCancelInvite")
        }
    }

    private val fieldVoipMuted by dexField {
        matcher {
            declaredClass(classVoipAudioManager.clazz)
            type = "boolean"
            addWriteMethod {
                declaredClass(classFlutterVoipManager.clazz)
                paramTypes("boolean")
                usingEqStrings("qipeng, enableMute.", "qipeng, disableMute.")
            }
        }
    }

    private val methodFlutterVoipMinimize by dexMethod {
        matcher {
            declaredClass(classFlutterVoipPlugin.clazz)
            paramCount = 4
            returnType = "void"
            usingEqStrings(
                "MicroMsg.FlutterVoipPlugin",
                "minimize: activity=",
                "voip is already minimized, ignore!",
                "minimize, permission denied",
            )
        }
    }

    private val fieldFlutterVoipActivity by dexField {
        matcher {
            declaredClass(classFlutterVoipPlugin.clazz)
            type(Activity::class.java)
        }
    }

    private val fieldFlutterVoipManager by dexField {
        matcher {
            declaredClass(classFlutterVoipPlugin.clazz)
            type(classFlutterVoipManager.clazz)
        }
    }

    private val methodFlutterVoipAttachedToActivity by dexMethod {
        matcher {
            declaredClass(classFlutterVoipPlugin.clazz)
            paramCount = 1
            returnType = "void"
            usingEqStrings("onAttachedToActivity: ", "init flutter voip mgr")
        }
    }

    private val methodFlutterVoipReattachedToActivity by dexMethod {
        matcher {
            declaredClass(classFlutterVoipPlugin.clazz)
            paramCount = 1
            returnType = "void"
            usingEqStrings("onReattachedToActivityForConfigChanges:")
        }
    }

    private val methodFlutterCallbackInvoke by dexMethod {
        matcher {
            declaredClass(methodFlutterVoipMinimize.method.parameterTypes.last())
            paramTypes(Any::class.java)
            returnType(Any::class.java)
        }
    }

    private val classMultiTalkViewModel by dexClass {
        matcher {
            usingEqStrings(
                "MicroMsg.MT.MultiTalkUIViewModel",
                "onCameraClick, cur state: ",
                "onMicClick, cur state: ",
            )
        }
    }

    private val fieldMultiTalkViewModel by dexField {
        matcher {
            declaredClass(MultiTalkMainUI::class.java)
            type(classMultiTalkViewModel.clazz)
        }
    }

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

    private val methodMultiTalkMinimize by dexMethod {
        matcher {
            declaredClass(MultiTalkMainUI::class.java)
            paramCount = 0
            returnType = "void"
            usingEqStrings("onMiniMultiTalk")
        }
    }

    private val methodMultiTalkExit by dexMethod {
        matcher {
            declaredClass(MultiTalkMainUI::class.java)
            paramCount = 0
            returnType = "void"
            usingEqStrings("onExitMultiTalk")
        }
    }

    private val methodMultiTalkMic by dexMethod {
        matcher {
            declaredClass(classMultiTalkViewModel.clazz)
            paramTypes("boolean")
            returnType = "void"
            usingEqStrings("onMicClick, cur state: ")
        }
    }

    private val methodMultiTalkCamera by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.MT.MultiTalkUIViewModel", "onCameraClick, cur state: ")
        }
    }

    private val fieldMultiTalkMicState by dexField {
        matcher {
            declaredClass(classMultiTalkViewModel.clazz)
            type(classObservableState.clazz)
            addReadMethod {
                usingEqStrings("MicroMsg.MT.MultiTalkUIViewModel", "onMicClick, cur state: ")
            }
        }
    }

    private val fieldMultiTalkCameraState by dexField {
        matcher {
            declaredClass(classMultiTalkViewModel.clazz)
            type(classObservableState.clazz)
            addReadMethod {
                usingEqStrings("MicroMsg.MT.MultiTalkUIViewModel", "onCameraClick, cur state: ")
            }
        }
    }

    private val methodObservableValue by dexMethod {
        matcher {
            declaredClass(classObservableState.clazz)
            paramCount = 0
            returnType(Any::class.java)
        }
    }

    override fun onEnable() {
        methodVoipActivityProxyDealContentView.hookBefore {
            WeLogger.d(TAG, "dealContentView: ${args[0]!!.javaClass}")
        }

        ActivityInfo::class.reflekt()
            .firstConstructor()
            .hookAfter {
                val info = thisObject as ActivityInfo
                if (info.name == VideoActivity::class.java.name) applyPipFlags(info)
            }

        Activity::class.reflekt()
            .firstMethod {
                name = "onPictureInPictureModeChanged"
                parameterCount = 2
            }
            .hookBefore {
                if (thisObject is VideoActivity) {
                    WeLogger.i(TAG, "VideoActivity picture-in-picture mode: ${args[0]}")
                }
            }

        methodFlutterVoipAttachedToActivity.hookAfter {
            registerSingleSession(thisObject!!)
        }

        methodFlutterVoipReattachedToActivity.hookAfter {
            registerSingleSession(thisObject!!)
        }

        methodFlutterVoipMinimize.hookBefore {
            val activity = fieldFlutterVoipActivity.field.get(thisObject) as VideoActivity
            sessions.getValue(activity).enterPip()
            methodFlutterCallbackInvoke.method.invoke(args[3], true)
            result = null
        }

        methodVoipMinimize.hookBefore {
            val session = sessions.values.filterIsInstance<SingleSession>()
                .first { it.manager === thisObject }
            session.enterPip()
            result = true
        }

        VideoActivity::class.reflekt().firstMethod {
            name = "onUserLeaveHint"
            parameterCount = 0
        }.hookBefore {
            sessions.getValue(thisObject as VideoActivity).enterPip()
        }
        VideoActivity::class.reflekt().firstMethod {
            name = "onDestroy"
            parameterCount = 0
        }.hookBefore {
            removeSession(thisObject as VideoActivity)
        }

        MultiTalkMainUI::class.reflekt().firstMethod {
            name = "onCreate"
            parameterCount = 1
        }.hookAfter {
            val activity = thisObject as MultiTalkMainUI
            sessions[activity] = GroupSession(activity)
        }
        MultiTalkMainUI::class.reflekt().firstMethod {
            name = "onDestroy"
            parameterCount = 0
        }.hookBefore {
            removeSession(thisObject as MultiTalkMainUI)
        }

        methodMultiTalkMinimize.hookBefore {
            sessions.getValue(thisObject as MultiTalkMainUI).enterPip()
            result = null
        }

        Activity::class.reflekt().firstMethod { name = "onUserLeaveHint" }.hookBefore {
            val activity = thisObject
            if (activity is MultiTalkMainUI) {
                sessions.getValue(activity).enterPip()
            }
        }
    }

    override fun onDisable() {
        sessions.values.filter { it.pipActive }.forEach { closePipActivity(it.activity) }
        sessions.clear()
    }

    private fun registerSingleSession(plugin: Any) {
        val activity = fieldFlutterVoipActivity.field.get(plugin) as VideoActivity
        val manager = fieldFlutterVoipManager.field.get(plugin)!!
        WeLogger.d(TAG, "placing $activity into map")
        sessions[activity] = SingleSession(activity, manager)
    }

    private fun applyPipFlags(info: ActivityInfo) {
        info.flags = info.flags or FLAG_SUPPORTS_PICTURE_IN_PICTURE
        info.reflekt().firstField { name = "resizeMode" }.set(RESIZE_MODE_RESIZEABLE)
    }

    private fun removeSession(activity: Activity) {
        if (sessions.remove(activity)!!.pipActive) closePipActivity(activity)
    }

    private fun closePipActivity(context: Context) {
        context.startActivity(
            Intent {
                component = ComponentName(PackageNames.WECHAT, PipVoipActivity::class.java.name)
                action = PipVoipActivity.ACTION_CLOSE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }
}
