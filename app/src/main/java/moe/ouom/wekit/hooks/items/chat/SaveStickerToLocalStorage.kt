package moe.ouom.wekit.hooks.items.chat

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.toClass
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.ui.WeChatMessageLongPressMenuApi
import moe.ouom.wekit.host.HostInfo
import moe.ouom.wekit.utils.common.ToastUtils
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天与消息/贴纸保存到本地", desc = "在贴纸消息长按菜单添加保存按钮, 允许将表情包保存到本地")
object SaveStickerToLocalStorage : BaseSwitchFunctionHookItem(), IDexFind, WeChatMessageLongPressMenuApi.IMenuItemsProvider {

    private const val TAG = "SaveStickerToLocalStorage"

    private val classEmojiFileEncryptMgr by dexClass()

    override fun entry(classLoader: ClassLoader) {
        WeChatMessageLongPressMenuApi.addProvider(this)
    }

    override fun unload(classLoader: ClassLoader) {
        super.unload(classLoader)
        WeChatMessageLongPressMenuApi.removeProvider(this)
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        classEmojiFileEncryptMgr.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        usingEqStrings("MicroMsg.emoji.EmojiFileEncryptMgr", "decode emoji file failed. path is no exist :%s ")
                    }
                }
            }
        }

        return descriptors
    }

    override fun getMenuItems(
        hookParam: XC_MethodHook.MethodHookParam,
        msgInfoBean: Any
    ): List<WeChatMessageLongPressMenuApi.MenuItem> {
        if (msgInfoBean.asResolver()
                .firstField { name = "field_type"; superclass() }.get() as Int != 43) {
            // not a sticker message
            return emptyList()
        }

        return listOf(
            @Suppress("UNCHECKED_CAST")
            WeChatMessageLongPressMenuApi.MenuItem(0, "保存\n到本地", 0) { _, msgInfoBean ->
//                val msgId = msgInfoBean.asResolver()
//                    .firstField {
//                        name = "field_msgId"
//                        superclass()
//                    }
//                    .get() as Long
                val md5 = msgInfoBean.asResolver()
                    .firstField {
                        name = "field_imgPath"
                        superclass()
                    }
                    .get() as String
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

                val resolver = HostInfo.getApplication().contentResolver
                val fileName = "sticker_${System.currentTimeMillis()}.gif"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WeKit")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
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