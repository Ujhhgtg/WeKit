package dev.ujhhgtg.wekit.activity

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Call_end
import com.composables.icons.materialsymbols.outlined.Mic
import com.composables.icons.materialsymbols.outlined.Mic_off
import com.composables.icons.materialsymbols.outlined.Videocam
import com.composables.icons.materialsymbols.outlined.Videocam_off
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleAppTheme

@Keep
class PipVoipActivity : ComponentActivity() {

    private lateinit var receiver: ResultReceiver
    private var groupCall = false
    private var micMuted by mutableStateOf(false)
    private var videoEnabled by mutableStateOf(true)
    private var closing = false
    private var pipRequested = false

    /** 宿主进程用这个通道反过来控制画中画（关闭 / 同步状态） */
    private val commands = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            when (resultCode) {
                COMMAND_CLOSE -> {
                    closing = true
                    finishAndRemoveTask()
                }

                COMMAND_UPDATE_STATE -> {
                    resultData ?: return
                    micMuted = resultData.getBoolean(EXTRA_MIC_MUTED, micMuted)
                    videoEnabled = resultData.getBoolean(EXTRA_VIDEO_ENABLED, videoEnabled)
                    updatePipParams()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_CLOSE) {
            finish()
            return
        }

        receiver = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_RESULT_RECEIVER,
            ResultReceiver::class.java,
        )!!
        current = this
        groupCall = intent.getBooleanExtra(EXTRA_GROUP_CALL, false)
        micMuted = intent.getBooleanExtra(EXTRA_MIC_MUTED, false)
        videoEnabled = intent.getBooleanExtra(EXTRA_VIDEO_ENABLED, true)
        setContent {
            ModuleAppTheme(darkTheme = true) {
                PipControls()
            }
        }

        updatePipParams()
        receiver.send(
            RESULT_READY,
            Bundle().apply { putParcelable(EXTRA_COMMAND_RECEIVER, commands) },
        )
    }

    override fun onResume() {
        super.onResume()
        // enterPictureInPictureMode() 只能在 RESUMED 状态下调用，在 onCreate 里会被系统拒绝
        if (closing || pipRequested || isInPictureInPictureMode) return
        pipRequested = true
        // 宿主的 stub Activity 在清单里没有 supportsPictureInPicture，system_server 会
        // 直接抛 IllegalStateException —— 绝不能让它冒到微信的 onResume 里去
        val entered = runCatching { enterPictureInPictureMode(updatePipParams()) }
            .onFailure { Log.e(TAG, "enterPictureInPictureMode threw", it) }
            .getOrDefault(false)
        if (!entered) {
            Log.e(TAG, "system refused picture-in-picture, handing the call back to wechat")
            closing = true
            receiver.send(RESULT_RESTORE, null)
            finishAndRemoveTask()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_CLOSE) {
            closing = true
            finishAndRemoveTask()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode && !closing) {
            closing = true
            receiver.send(RESULT_RESTORE, null)
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        if (::receiver.isInitialized) receiver.send(RESULT_CLOSED, null)
        if (current === this) current = null
        super.onDestroy()
    }

    fun handleAction(action: String) {
        when (action) {
            ACTION_HANG_UP -> {
                closing = true
                receiver.send(RESULT_HANG_UP, null)
                finishAndRemoveTask()
            }

            ACTION_TOGGLE_MIC -> toggleMic()
            ACTION_TOGGLE_VIDEO -> toggleVideo()
        }
    }

    private fun toggleMic() {
        micMuted = !micMuted
        receiver.send(RESULT_TOGGLE_MIC, null)
        updatePipParams()
    }

    private fun toggleVideo() {
        videoEnabled = !videoEnabled
        receiver.send(RESULT_TOGGLE_VIDEO, null)
        updatePipParams()
    }

    private fun updatePipParams(): PictureInPictureParams {
        val actions = mutableListOf(
            remoteAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                localizedString(R.string.voip_hang_up),
                ACTION_HANG_UP,
                REQUEST_HANG_UP,
            ),
            remoteAction(
                android.R.drawable.ic_btn_speak_now,
                localizedString(if (micMuted) R.string.voip_turn_on_microphone else R.string.voip_turn_off_microphone),
                ACTION_TOGGLE_MIC,
                REQUEST_TOGGLE_MIC,
            ),
        )
        if (groupCall) {
            actions += remoteAction(
                android.R.drawable.ic_menu_camera,
                localizedString(if (videoEnabled) R.string.voip_turn_off_video else R.string.voip_turn_on_video),
                ACTION_TOGGLE_VIDEO,
                REQUEST_TOGGLE_VIDEO,
            )
        }

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)
            .build()
        setPictureInPictureParams(params)
        return params
    }

    private fun localizedString(@StringRes resourceId: Int): String =
        LocalizedContextFactory.create(
            this,
            WeKitLocaleController.resolvedLocale,
            LocaleResourceMode.ModuleApp,
        ).getString(resourceId)

    private fun remoteAction(icon: Int, title: String, action: String, requestCode: Int): RemoteAction {
        val intent = Intent(this, PipVoipActionReceiver::class.java).setAction(action)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(this, icon), title, title, pendingIntent)
    }

    @Composable
    private fun PipControls() {
        val hangUp = stringResource(R.string.voip_hang_up)
        val turnOnMicrophone = stringResource(R.string.voip_turn_on_microphone)
        val turnOffMicrophone = stringResource(R.string.voip_turn_off_microphone)
        val turnOnVideo = stringResource(R.string.voip_turn_on_video)
        val turnOffVideo = stringResource(R.string.voip_turn_off_video)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF151515)),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = {
                        closing = true
                        receiver.send(RESULT_HANG_UP, null)
                        finishAndRemoveTask()
                    },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(MaterialSymbols.Outlined.Call_end, contentDescription = hangUp)
                }
                FilledIconButton(onClick = ::toggleMic, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (micMuted) MaterialSymbols.Outlined.Mic else MaterialSymbols.Outlined.Mic_off,
                        contentDescription = if (micMuted) turnOnMicrophone else turnOffMicrophone,
                    )
                }
                if (groupCall) {
                    FilledIconButton(onClick = ::toggleVideo, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (videoEnabled) MaterialSymbols.Outlined.Videocam_off else MaterialSymbols.Outlined.Videocam,
                            contentDescription = if (videoEnabled) turnOffVideo else turnOnVideo,
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PipVoipActivity"

        const val EXTRA_RESULT_RECEIVER = "pip_voip_result_receiver"
        const val EXTRA_COMMAND_RECEIVER = "pip_voip_command_receiver"
        const val EXTRA_GROUP_CALL = "pip_voip_group_call"
        const val EXTRA_MIC_MUTED = "pip_voip_mic_muted"
        const val EXTRA_VIDEO_ENABLED = "pip_voip_video_enabled"

        const val ACTION_CLOSE = "dev.ujhhgtg.wekit.action.CLOSE_VOIP_PIP"
        private const val ACTION_HANG_UP = "dev.ujhhgtg.wekit.action.HANG_UP_VOIP"
        private const val ACTION_TOGGLE_MIC = "dev.ujhhgtg.wekit.action.TOGGLE_VOIP_MIC"
        private const val ACTION_TOGGLE_VIDEO = "dev.ujhhgtg.wekit.action.TOGGLE_VOIP_VIDEO"

        const val RESULT_HANG_UP = 1
        const val RESULT_TOGGLE_MIC = 2
        const val RESULT_TOGGLE_VIDEO = 3
        const val RESULT_RESTORE = 4
        const val RESULT_CLOSED = 5
        const val RESULT_READY = 6

        const val COMMAND_CLOSE = 1
        const val COMMAND_UPDATE_STATE = 2

        private const val REQUEST_HANG_UP = 1
        private const val REQUEST_TOGGLE_MIC = 2
        private const val REQUEST_TOGGLE_VIDEO = 3

        var current: PipVoipActivity? = null
    }
}

class PipVoipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PipVoipActivity.current!!.handleAction(intent.action!!)
    }
}
