package moe.ouom.wekit.hooks.items.chat

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.hooks.api.core.model.MessageType
import moe.ouom.wekit.hooks.api.ui.WeChatMessageContextMenuApi
import moe.ouom.wekit.utils.HostInfo
import moe.ouom.wekit.utils.ModuleRes
import moe.ouom.wekit.utils.ToastUtils
import moe.ouom.wekit.utils.logging.WeLogger
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天/贴纸保存到本地", desc = "在贴纸消息菜单添加保存按钮, 允许将图片保存到本地")
object SaveStickersToLocalStorage : SwitchHookItem(), IResolvesDex,
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    private val TAG = nameof(SaveStickersToLocalStorage)

    private val classEmojiFileEncryptMgr by dexClass()

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        classEmojiFileEncryptMgr.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        usingEqStrings(
                            "MicroMsg.emoji.EmojiFileEncryptMgr",
                            "decode emoji file failed. path is no exist :%s "
                        )
                    }
                }
            }
        }

        return descriptors
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            @Suppress("UNCHECKED_CAST")
            WeChatMessageContextMenuApi.MenuItem(
                777001,
                "存本地",
                lazy { ModuleRes.getDrawable("download_24px") },
                { msgInfo -> msgInfo.isType(MessageType.STICKER) }
            ) { _, _, msgInfo ->
                val md5 = msgInfo.imagePath
                val emojiInfo = StickersSync.getEmojiInfoByMd5(md5)
                val emojiFileEncryptMgr = classEmojiFileEncryptMgr.clazz.asResolver()
                    .firstMethod {
                        modifiers(Modifiers.STATIC)
                        parameterCount = 0
                    }
                    .invoke()!!
                var bytes = emojiFileEncryptMgr.asResolver()
                    .firstMethod {
                        parameters("com.tencent.mm.api.IEmojiInfo")
                        returnType = ByteArray::class
                    }
                    .invoke(emojiInfo) as ByteArray
                bytes = "com.tencent.mm.plugin.gif.MMWXGFJNI".toClass().asResolver()
                    .firstMethod {
                        name = "nativeWxamToGif"
                    }
                    .invoke(bytes) as ByteArray

                val resolver = HostInfo.application.contentResolver
                val fileName = "sticker_${System.currentTimeMillis()}.gif"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/WeKit"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val imageUri =
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                imageUri?.let { uri ->
                    try {
                        resolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(bytes)
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)

                        ToastUtils.showToast("已将贴纸保存到 /sdcard/Pictures/WeKit/$fileName")
                    } catch (e: Exception) {
                        WeLogger.e(TAG, "failed to save sticker to local storage", e)
                        resolver.delete(uri, null, null)
                    }
                }
            }
        )
    }
}