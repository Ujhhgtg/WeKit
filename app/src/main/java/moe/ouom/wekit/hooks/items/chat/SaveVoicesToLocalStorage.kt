package moe.ouom.wekit.hooks.items.chat

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.base.WeServiceApi
import moe.ouom.wekit.hooks.sdk.base.model.MessageType
import moe.ouom.wekit.hooks.sdk.ui.WeChatMessageContextMenuApi
import moe.ouom.wekit.host.HostInfo
import moe.ouom.wekit.utils.common.ModuleRes
import moe.ouom.wekit.utils.common.ToastUtils
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import xyz.xxin.silkdecoder.SilkDecoder
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.inputStream

@HookItem(
    path = "聊天/语音保存到本地",
    desc = "在语音消息菜单添加保存按钮, 允许将语音文件保存到本地"
)
object SaveVoicesToLocalStorage : SwitchHookItem(), IDexFind,
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    private val TAG = nameof(SaveVoicesToLocalStorage)

    private val classVoiceLogic by dexClass()
    private val methodGetAmrFullPath by dexMethod()

    private lateinit var methodStreamSilkDecInit: Method
    private lateinit var methodStreamSilkDecUnInit: Method
    private lateinit var methodStreamSilkDoDec: Method

    override fun onLoad() {
        val clazz = "com.tencent.mm.modelvoice.MediaRecorder".toClass()
        methodStreamSilkDecInit = clazz.asResolver()
            .firstMethod { name = "StreamSilkDecInit" }
            .self
        methodStreamSilkDecUnInit = clazz.asResolver()
            .firstMethod { name = "StreamSilkDecUnInit" }
            .self
        methodStreamSilkDoDec = clazz.asResolver()
            .firstMethod { name = "StreamSilkDoDec" }
            .self

        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onUnload() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        classVoiceLogic.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("MicroMsg.VoiceLogic", "startRecord insert voicestg success")
            }
        }

        methodGetAmrFullPath.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("getAmrFullPath cost: ")
            }
        }

        return descriptors
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            @Suppress("UNCHECKED_CAST")
            WeChatMessageContextMenuApi.MenuItem(
                777003,
                "存本地",
                lazy { ModuleRes.getDrawable("download_24px") },
                { msgInfo -> msgInfo.isType(MessageType.VOICE) }
            ) { _, _, msgInfo ->
                val encPath = msgInfo.imagePath

                var service: Any? = null
                if (!Modifier.isStatic(methodGetAmrFullPath.method.modifiers)) {
                    service =
                        WeServiceApi.getServiceByClass(methodGetAmrFullPath.method.declaringClass)
                }
                val amrPath =
                    Path(methodGetAmrFullPath.method.invoke(service, null, encPath, true) as String)
                val mp3Path = amrPath.resolveSibling(amrPath.fileName.toString() + ".mp3")
                SilkDecoder.decodeToMp3(amrPath.toString(), mp3Path.toString())
                saveAudio(mp3Path)
                WeLogger.d(TAG, "mp3: $mp3Path")
            }
        )
    }

    private fun saveAudio(sourceFile: Path) {
        val extension = sourceFile.extension
        val resolver = HostInfo.application.contentResolver
        val fileName = "voice_${System.currentTimeMillis()}.$extension"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/$extension")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/WeKit"
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val audioUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)

        audioUri?.let { uri ->
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    sourceFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                ToastUtils.showToast("已将语音保存到 /sdcard/Music/WeKit/$fileName")
            } catch (e: Exception) {
                WeLogger.e(TAG, "failed to save voice message", e)
                resolver.delete(uri, null, null)
            }
        }
    }
}