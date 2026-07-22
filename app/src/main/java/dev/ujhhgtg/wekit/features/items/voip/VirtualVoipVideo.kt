@file:Suppress("DEPRECATION")

package dev.ujhhgtg.wekit.features.items.voip

import android.graphics.ColorSpace
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.reflection.BInt
import kotlin.io.path.div

@Feature(
    name = "虚拟视频通话", categories = ["聊天", "音视频通话"],
    description = "在微信视频通话相机预览中播放本地视频或网络直播流"
)
object VirtualVoipVideo : ClickableFeature(), IResolveDex {

    private const val TAG = "VirtualVoipVideo"

    private val VIDEO_PATH by lazy {
        KnownPaths.moduleData / "virtual_voip_video.mp4"
    }

    private var sourceType by prefOption("virtual_voip_source_type", "file")
    private var streamUrl by prefOption("virtual_voip_stream_url", "")
    private var streamOrientation by prefOption("virtual_voip_stream_orientation", "auto")

    private val CAMERA2_METHODS = setOf(
        "createCaptureSession",
        "createCaptureSessionByOutputConfigurations",
        "createExtensionSession"
    )

    @Volatile
    private var isVoipUiActive = false

    private var mediaPlayer: MediaPlayer? = null

    private var ownedPlaybackSurface: Surface? = null

    private val dummySurfaceTexture: SurfaceTexture by lazy {
        SurfaceTexture(10).apply { setDefaultBufferSize(1, 1) }
    }

    private var cachedDummySurface: Surface? = null
    private val dummySurface: Surface
        @Synchronized get() = cachedDummySurface?.takeIf { it.isValid }
            ?: Surface(dummySurfaceTexture).also { cachedDummySurface = it }

    @Volatile
    private var lastCheckedKey: String? = null

    @Volatile
    private var isVideoPortraitCached: Boolean = false

    private val camera2HookBypass = ThreadLocal.withInitial { false }

    private val methodLaunchVoipPage by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.VoIPMP.Launcher", "launch page ")
        }
    }

    private val methodStartCamera by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.ILinkVoIPCameraHelper", "old startCamera abandon")
        }
    }

    private val isVideoPortrait: Boolean
        get() {
            val orientation = streamOrientation
            if (sourceType == "stream") {
                return orientation != "landscape"
            }

            if (orientation == "portrait") return true
            if (orientation == "landscape") return false

            val file = VIDEO_PATH.toFile()
            if (!file.exists()) return false

            val currentKey = "${file.absolutePath}_${file.lastModified()}_${file.length()}"
            if (lastCheckedKey == currentKey) {
                return isVideoPortraitCached
            }

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

                val realWidth = if (rotation == 90 || rotation == 270) height else width
                val realHeight = if (rotation == 90 || rotation == 270) width else height

                isVideoPortraitCached = realWidth < realHeight
                WeLogger.d(TAG, "video check: width=$width, height=$height, rotation=$rotation -> isPortrait=$isVideoPortraitCached")
            } catch (e: Exception) {
                WeLogger.e(TAG, "failed to parse video orientation metadata", e)
                isVideoPortraitCached = false
            } finally {
                runCatching { retriever.release() }
            }
            lastCheckedKey = currentKey
            return isVideoPortraitCached
        }

    override fun onEnable() {
        methodStartCamera.hookBefore {
            isVoipUiActive = true
            WeLogger.d(TAG, "entered voip ui: MicroMsg.ILinkVoIPCameraHelper")
        }

        methodLaunchVoipPage.hookBefore {
            isVoipUiActive = true
            WeLogger.d(TAG, "entered voip ui: MicroMsg.VoIPMP.Launcher")
        }

        listOf(
            "com.tencent.mm.plugin.voip.ui.VideoActivity",
            "com.tencent.mm.plugin.multitalk.ui.MultiTalkMainUI"
        ).forEach { className ->
            className.toClass().apply {
                firstMethod { name = "onCreate" }.hookBefore {
                    isVoipUiActive = true
                    WeLogger.d(TAG, "entered voip ui: $className")
                }

                firstMethod { name = "onDestroy" }.hookBefore {
                    isVoipUiActive = false
                    releasePlayer("left voip ui: $className")
                }
            }
        }

        Camera::class.apply {
            firstMethod { name = "setPreviewTexture" }.hookBefore {
                WeLogger.i(TAG, "Camera::setPreviewTexture is called")

                if (!shouldInterceptCamera) return@hookBefore

                WeLogger.i(TAG, "should intercept Camera::setPreviewTexture, starting virtual video...")

                val targetTexture = args[0] as? SurfaceTexture? ?: return@hookBefore

                args[0] = dummySurfaceTexture
                startVirtualVideo(
                    surface = Surface(targetTexture),
                    ownsSurface = true
                )
                WeLogger.d(TAG, "camera1 preview texture replaced")
            }

            firstMethod {
                name = "getCameraInfo"
                parameters(BInt, Camera.CameraInfo::class)
            }.hookAfter {
                if (!shouldInterceptCamera) return@hookAfter
                val info = args[1] as? Camera.CameraInfo ?: return@hookAfter

                if (isVideoPortrait) {
                    val isFront = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                    info.orientation = if (isFront) 90 else 270
                    WeLogger.i(TAG, "portrait video detected: forcing camera orientation to ${info.orientation}")
                } else {
                    info.orientation = 0
                    WeLogger.i(TAG, "landscape video detected: forcing camera orientation to 0")
                }
                info.facing = Camera.CameraInfo.CAMERA_FACING_BACK
                WeLogger.i(TAG, "forcing camera facing to back")
            }
        }

        val cameraDeviceImpl = runCatching {
            "android.hardware.camera2.impl.CameraDeviceImpl".toClass()
        }.getOrNull()

        if (cameraDeviceImpl == null) {
            WeLogger.w(TAG, "CameraDeviceImpl not found, Camera2 hook skipped")
            return
        }

        cameraDeviceImpl.declaredMethods.filter {
            it.name in CAMERA2_METHODS
        }.forEach { method ->
            method.hookBefore {
                WeLogger.i(TAG, "Camera2::$method is called")
                if (!shouldInterceptCamera) return@hookBefore
                WeLogger.i(TAG, "should intercept Camera2::$method, starting virtual video...")
                hijackCamera2Session(this)
            }
        }

        CameraCharacteristics::class.firstMethod { name = "get" }.hookAfter {
            if (camera2HookBypass.get()!!) return@hookAfter
            if (!shouldInterceptCamera) return@hookAfter
            val key = args[0] as? CameraCharacteristics.Key<*>? ?: return@hookAfter

            if (key == CameraCharacteristics.SENSOR_ORIENTATION) {
                if (isVideoPortrait) {
                    camera2HookBypass.set(true)
                    val characteristics = thisObject as? CameraCharacteristics
                    val lensFacing = runCatching { characteristics?.get(CameraCharacteristics.LENS_FACING) }.getOrNull()
                    camera2HookBypass.set(false)

                    val isFront = lensFacing == CameraCharacteristics.LENS_FACING_FRONT
                    result = if (isFront) 90 else 270
                    WeLogger.i(TAG, "portrait video detected: forcing Camera2 SENSOR_ORIENTATION to $result")
                } else {
                    result = 0
                    WeLogger.i(TAG, "landscape video detected: forcing Camera2 SENSOR_ORIENTATION to 0")
                }
            } else if (key == CameraCharacteristics.LENS_FACING) {
                WeLogger.i(TAG, "forcing lens facing to BACK")
                result = CameraCharacteristics.LENS_FACING_BACK
            }
        }
    }

    override fun onDisable() {
        isVoipUiActive = false
        releasePlayer("disabled")
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var currentType by remember { mutableStateOf(sourceType) }
            var urlText by remember { mutableStateOf(streamUrl) }

            // 确保如果当前保存的配置是 auto 却切换到了 stream 模式时，降级显示为 portrait
            var orientationText by remember {
                mutableStateOf(if (currentType == "stream" && streamOrientation == "auto") "portrait" else streamOrientation)
            }
            var fileExists by remember { mutableStateOf(VIDEO_PATH.toFile().exists()) }

            AlertDialogContent(
                title = { Text("虚拟视频通话配置") },
                text = {
                    DefaultColumn {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = currentType == "file",
                                onClick = {
                                    currentType = "file"
                                    sourceType = "file"
                                    // 恢复为保存的全局文件方向配置
                                    orientationText = streamOrientation
                                }
                            )
                            Text("本地文件", modifier = Modifier.clickable {
                                currentType = "file"
                                sourceType = "file"
                                orientationText = streamOrientation
                            })

                            Spacer(modifier = Modifier.width(16.dp))

                            RadioButton(
                                selected = currentType == "stream",
                                onClick = {
                                    currentType = "stream"
                                    sourceType = "stream"
                                    // 网络流不支持自动，若当前为 auto 则强制修正为 portrait
                                    if (orientationText == "auto") {
                                        orientationText = "portrait"
                                        streamOrientation = "portrait"
                                    }
                                }
                            )
                            Text("网络流地址", modifier = Modifier.clickable {
                                currentType = "stream"
                                sourceType = "stream"
                                if (orientationText == "auto") {
                                    orientationText = "portrait"
                                    streamOrientation = "portrait"
                                }
                            })
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (currentType == "file") {
                            Button(
                                onClick = {
                                    TransparentActivity.launch(context) {
                                        val selMediaLauncher = registerForActivityResult(
                                            ActivityResultContracts.PickVisualMedia()
                                        ) { uri ->
                                            finish()
                                            if (uri == null) return@registerForActivityResult

                                            contentResolver.openInputStream(uri)?.use { input ->
                                                VIDEO_PATH.toFile().outputStream().use { output ->
                                                    input.copyTo(output)
                                                }
                                            } ?: run {
                                                WeLogger.e(TAG, "failed to open input stream")
                                                showToast(context, "视频文件打开失败!")
                                                return@registerForActivityResult
                                            }

                                            fileExists = true
                                            showToast(context, "视频文件导入成功")
                                        }

                                        selMediaLauncher.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.VideoOnly
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("选择本地视频")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = if (fileExists) "当前视频文件已就绪" else "当前无视频文件, 会自动放行真实相机"
                            )
                            Text("固定路径: $VIDEO_PATH")
                        } else {
                            OutlinedTextField(
                                value = urlText,
                                onValueChange = {
                                    urlText = it
                                    streamUrl = it
                                },
                                label = { Text("输入流媒体 URL (HTTP/HLS/RTSP)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text("视频显示方向:")
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (currentType == "file") {
                                RadioButton(
                                    selected = orientationText == "auto",
                                    onClick = { orientationText = "auto"; streamOrientation = "auto" }
                                )
                                Text("自动", modifier = Modifier.clickable { orientationText = "auto"; streamOrientation = "auto" })

                                Spacer(modifier = Modifier.width(16.dp))
                            }

                            RadioButton(
                                selected = orientationText == "portrait",
                                onClick = { orientationText = "portrait"; streamOrientation = "portrait" }
                            )
                            Text("竖屏", modifier = Modifier.clickable { orientationText = "portrait"; streamOrientation = "portrait" })

                            Spacer(modifier = Modifier.width(16.dp))

                            RadioButton(
                                selected = orientationText == "landscape",
                                onClick = { orientationText = "landscape"; streamOrientation = "landscape" }
                            )
                            Text("横屏", modifier = Modifier.clickable { orientationText = "landscape"; streamOrientation = "landscape" })
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = onDismiss) { Text("确定") }
                }
            )
        }
    }

    private fun hijackCamera2Session(param: HookParam) {
        param.args.forEachIndexed { index, arg ->
            if (arg == null) return@forEachIndexed
            val hijackedArg = when (arg) {
                is List<*> -> hijackSurfaceList(arg)
                else -> hijackSessionConfiguration(arg)
            } ?: return@forEachIndexed

            param.args[index] = hijackedArg.replacement
            startVirtualVideo(
                surface = hijackedArg.playbackSurface,
                ownsSurface = false
            )
            WeLogger.d(TAG, "Camera2 session argument replaced: ${param.member.name}")
            return
        }
    }

    private fun hijackSurfaceList(list: List<*>): HijackedArg<*>? {
        if (list.isEmpty()) return null
        return when (list.firstOrNull()) {
            is Surface -> hijackDirectSurfaces(list.filterIsInstance<Surface>())
            is OutputConfiguration -> hijackOutputConfigurations(list.filterIsInstance<OutputConfiguration>())
            else -> null
        }
    }

    private fun hijackDirectSurfaces(list: List<Surface>): HijackedArg<List<Surface>>? {
        val playbackSurface = list.firstOrNull() ?: return null
        return HijackedArg(
            replacement = List(list.size) { dummySurface },
            playbackSurface = playbackSurface
        )
    }

    private fun hijackOutputConfigurations(list: List<OutputConfiguration>): HijackedArg<List<OutputConfiguration>>? {
        var playbackSurface: Surface? = null
        val replacement = list.map { config ->
            val surface = runCatching { config.surface }.getOrNull()
            if (surface != null) {
                if (playbackSurface == null) playbackSurface = surface
                OutputConfiguration(dummySurface)
            } else {
                config
            }
        }
        return playbackSurface?.let { HijackedArg(replacement, it) }
    }

    private fun hijackSessionConfiguration(config: Any): HijackedArg<*>? {
        return when (config) {
            is SessionConfiguration -> {
                val hijackedOutputs = hijackOutputConfigurations(config.outputConfigurations) ?: return null
                SessionConfiguration(
                    config.sessionType,
                    hijackedOutputs.replacement,
                    config.executor,
                    config.stateCallback
                ).apply {
                    sessionParameters = config.sessionParameters
                    inputConfiguration = config.inputConfiguration
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val colorSpace = config.reflekt().getField("mColorSpace") as Int
                        ColorSpace.Named.entries.getOrNull(colorSpace)?.let { setColorSpace(it) }
                    }
                }.let { HijackedArg(it, hijackedOutputs.playbackSurface) }
            }

            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && config is ExtensionSessionConfiguration) {
                    val hijackedOutputs = hijackOutputConfigurations(config.outputConfigurations) ?: return null
                    ExtensionSessionConfiguration(
                        config.extension,
                        hijackedOutputs.replacement,
                        config.executor,
                        config.stateCallback
                    ).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                            config.sessionWideParams?.let { setSessionWideParams(it) }
                        }
                        postviewOutputConfiguration = config.postviewOutputConfiguration
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                            val colorSpace = config.reflekt().firstField {
                                name = "mColorSpace"
                            }.get() as Int
                            ColorSpace.Named.entries.getOrNull(colorSpace)?.let {
                                setColorSpace(it)
                            }
                        }
                    }.let { HijackedArg(it, hijackedOutputs.playbackSurface) }
                } else null
            }
        }
    }

    private val shouldInterceptCamera: Boolean
        get() = isVoipUiActive && (sourceType == "file" && VIDEO_PATH.toFile().exists() || sourceType == "stream" && streamUrl.isNotEmpty())

    @Synchronized
    private fun startVirtualVideo(surface: Surface, ownsSurface: Boolean) {
        if (!surface.isValid) {
            WeLogger.w(TAG, "playback surface is invalid")
            return
        }

        val type = sourceType
        val url = streamUrl
        val file = VIDEO_PATH.toFile()

        if (type == "file" && !file.exists()) return
        if (type == "stream" && url.isEmpty()) return

        releasePlayer("restart")

        val player = MediaPlayer()
        mediaPlayer = player
        ownedPlaybackSurface = if (ownsSurface) surface else null

        runCatching {
            if (type == "file") {
                player.setDataSource(file.absolutePath)
            } else {
                player.setDataSource(url)
            }
            player.setSurface(surface)
            player.isLooping = type == "file"
            player.setVolume(0f, 0f)
            player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            player.setOnPreparedListener { preparedPlayer ->
                if (mediaPlayer !== preparedPlayer) return@setOnPreparedListener
                runCatching {
                    preparedPlayer.start()
                    WeLogger.d(TAG, "virtual video playback started")
                }.onFailure { WeLogger.e(TAG, "failed to start virtual video", it) }
            }
            player.setOnErrorListener { erroredPlayer, what, extra ->
                WeLogger.w(TAG, "virtual video playback error: what=$what, extra=$extra")
                if (mediaPlayer === erroredPlayer) releasePlayer("player error")
                true
            }
            player.prepareAsync()
        }.onFailure {
            val sourceLocation = if (type == "file") file.absolutePath else url
            WeLogger.e(TAG, "failed to prepare virtual video: $sourceLocation", it)
            releasePlayer("prepare failed")
        }
    }

    @Synchronized
    private fun releasePlayer(reason: String) {
        WeLogger.d(TAG, "releasing player due to $reason")

        val player = mediaPlayer
        mediaPlayer = null

        if (player != null) {
            runCatching {
                player.release()
            }.onFailure { WeLogger.w(TAG, "failed to release player after $reason", it) }
        }

        ownedPlaybackSurface?.runCatching { release() }
        ownedPlaybackSurface = null
    }

    private data class HijackedArg<T>(
        val replacement: T,
        val playbackSurface: Surface
    )
}
