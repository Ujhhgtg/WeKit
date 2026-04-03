package dev.ujhhgtg.wekit.hooks.items.chat

import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeServiceApi
import dev.ujhhgtg.wekit.hooks.api.core.model.MessageType
import dev.ujhhgtg.wekit.hooks.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.KnownPaths
import dev.ujhhgtg.wekit.utils.ModuleRes
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

@HookItem(
    path = "聊天/语音保存到本地",
    desc = "在语音消息菜单添加保存按钮, 允许将语音文件保存到本地"
)
object SaveVoicesToLocalStorage : SwitchHookItem(), IResolvesDex,
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    private val TAG = nameOf(SaveVoicesToLocalStorage)

    private val classVoiceLogic by dexClass()
    private val methodGetAmrFullPath by dexMethod()


    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        classVoiceLogic.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.VoiceLogic", "startRecord insert voicestg success")
            }
        }

        methodGetAmrFullPath.find(dexKit) {
            matcher {
                usingEqStrings("getAmrFullPath cost: ")
            }
        }
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777003,
                "存本地",
                { ModuleRes.getDrawable(R.drawable.download_24px)!! },
                { msgInfo -> msgInfo.isType(MessageType.VOICE) }
            ) { _, _, msgInfo ->
                CoroutineScope(Dispatchers.IO).launch {
                    val encPath = msgInfo.imagePath
                    var service: Any? = null
                    if (!Modifier.isStatic(methodGetAmrFullPath.method.modifiers)) {
                        service =
                            WeServiceApi.getServiceByClass(methodGetAmrFullPath.method.declaringClass)
                    }
                    val silkOriginalPath =
                        Path(methodGetAmrFullPath.method.invoke(service, null, encPath, true) as String)
                    val mp3Name = silkOriginalPath.nameWithoutExtension + ".mp3"
                    val silkPath = KnownPaths.downloads / silkOriginalPath.name
                    val pcmPath = KnownPaths.downloads / (silkOriginalPath.nameWithoutExtension + ".pcm")
                    val mp3Path = KnownPaths.downloads / mp3Name

                    runCatching {
                        silkPath.deleteIfExists()
                        silkOriginalPath.copyTo(silkPath, overwrite = true)
                        AudioUtils.silkToPcm(silkPath.absolutePathString(), pcmPath.absolutePathString())
                        AudioUtils.pcmToMp3(pcmPath.absolutePathString(), mp3Path.absolutePathString())
                        pcmPath.deleteIfExists()
                    }.onSuccess {
                        withContext(Dispatchers.Main) {
                            showToast("已将语音保存到 ${mp3Path.absolutePathString()}")
                        }
                    }.onFailure { e ->
                        WeLogger.e(TAG, "failed to save voice to ${mp3Path.absolutePathString()}", e)
                        withContext(Dispatchers.Main) {
                            showToast("语音保存失败! 查看日志以了解错误详情")
                        }
                    }
                }
            }
        )
    }
}
