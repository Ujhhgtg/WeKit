package moe.ouom.wekit.hooks.item.chat.msg

import android.content.ContentValues
import android.content.Context
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.createInstance
import com.highcapable.kavaref.extension.toClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.utils.io.PathUtils
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries

@HookItem(path = "聊天与消息/贴纸表情同步", desc = "从指定路径将所有图片注册为贴纸表情")
class StickersSync : BaseSwitchFunctionHookItem(), IDexFind {
    companion object {
        private const val TAG = "StickersSync"
    }

    private val stickersList: MutableList<Any> = mutableListOf()

    private val methodGetEmojiGroupInfo by dexMethod()
    private val methodAddAllGroupItems by dexMethod()
    private val serviceManagerMethodGetService by dexMethod()
    private val constructorGroupItemInfo by dexMethod()
    private val classEmojiFeatureService by dexClass()
    private val classEmojiMgrImpl by dexClass()
    private val classEmojiStorageMgr by dexClass()
    private val classEmojiInfoStorage by dexClass()
    private val methodSaveEmojiThumb by dexMethod()

    private val stickersDir: Path?
        get() = PathUtils.moduleDataPath?.resolve("stickers")

    private fun getServiceByClass(clazz: Class<*>): Any {
        return serviceManagerMethodGetService.method.invoke(null, clazz)!!
    }

    private fun getEmojiFeatureService(): Any {
        val service = getServiceByClass(classEmojiFeatureService.clazz)
        return service.asResolver()
            .firstMethod {
                returnType = classEmojiMgrImpl.clazz
            }
            .invoke()!!
    }

    private fun `getEmojiSomethingFromPath`(path: String): String {
        return getEmojiFeatureService()
            .asResolver()
            .firstMethod {
                parameters(Context::class.java, String::class.java)
                returnType = String::class.java
            }
            .invoke(path) as String
    }

    private fun getEmojiThumbBySomething(md5: String): Any {
        val emojiStorageMgr = classEmojiStorageMgr.clazz.asResolver()
            .firstMethod {
                modifiers(Modifiers.STATIC)
                returnType = classEmojiInfoStorage.clazz
            }
            .invoke()!!
        val emojiInfoStorage = emojiStorageMgr.asResolver()
            .firstMethod {
                returnType = classEmojiInfoStorage.clazz
            }
            .invoke()!!
        val emojiThumb = emojiInfoStorage.asResolver()
            .firstMethod {
                parameters(String::class)
                returnType = methodSaveEmojiThumb.method.declaringClass
            }
            .invoke(md5)!!
        return emojiThumb
    }

    override fun entry(classLoader: ClassLoader) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val dir = stickersDir
            if (dir == null) {
                WeLogger.w(TAG, "could not determine stickers directory")
                return@launch
            }

            dir.createDirectories()
            val images = dir.listDirectoryEntries("*.{png,jpg,gif}")
            WeLogger.i(TAG, "found ${images.count()} sticker images in ${dir.absolutePathString()}")
            images.forEach { path ->
                val absPath = path.absolutePathString()
                val something = getEmojiSomethingFromPath(absPath)
                val emojiThumb = getEmojiThumbBySomething(something)
                methodSaveEmojiThumb.method.invoke(emojiThumb, null, true)
                val groupItemInfo = constructorGroupItemInfo.method.invoke(emojiThumb, 2, "", 0)!!
                stickersList.add(groupItemInfo)
                WeLogger.i(TAG, "prepared sticker at: $absPath")
            }
        }

        val emojiGroupInfoCls = "com.tencent.mm.storage.emotion.EmojiGroupInfo".toClass(classLoader)

        methodGetEmojiGroupInfo.toDexMethod {
            hook {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "UNCHECKED_CAST")
                afterIfEnabled { param ->
                    if (param.result !is java.util.List<*>) {
                        return@afterIfEnabled
                    }

                    val stickersPackData = ContentValues()
                    stickersPackData.put(
                        "packGrayIconUrl",
                        "https://avatars.githubusercontent.com/u/49312623"
                    )
                    stickersPackData.put(
                        "packIconUrl",
                        "https://avatars.githubusercontent.com/u/49312623"
                    )
                    stickersPackData.put("packName", "贴纸表情同步")
                    stickersPackData.put("packStatus", 1)
                    stickersPackData.put(
                        "productID", "wekit.stickers.sync"
                    )
                    stickersPackData.put("status", 7)
                    stickersPackData.put("sync", 2)

                    val emojiGroupInfo = emojiGroupInfoCls.createInstance(arrayOf<Any?>())
                    emojiGroupInfoCls.getMethod("convertFrom", ContentValues::class.java, Boolean::class.java)
                        .invoke(emojiGroupInfo, stickersPackData, true)

                    (param.result as java.util.List<Any?>).add(0, emojiGroupInfo)
                }
            }
        }

        methodAddAllGroupItems.toDexMethod {
            hook {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "UNCHECKED_CAST")
                beforeIfEnabled { param ->
                    val manager = param.args[0]
                    if (manager == null) {
                        WeLogger.w("args[0] is null, skipped")
                        return@beforeIfEnabled
                    }

                    val packConfig = manager.asResolver()
                        .firstMethod {
                            modifiers(Modifiers.FINAL)
                            returnType {
                                it != Boolean::class.java
                            }
                        }
                        .invoke()
                    val emojiGroupInfo = packConfig!!.asResolver()
                        .firstField {
                            type("com.tencent.mm.storage.emotion.EmojiGroupInfo".toClass(classLoader))
                        }.get()!!
                    val packName = emojiGroupInfo.asResolver()
                        .firstField { name = "field_packName" }
                        .get()!! as String
                    if (packName == "贴纸表情同步") {
                        val stickerList = manager.asResolver().firstMethod { returnType = List::class.java }.invoke() as java.util.List<Any?>
                        stickerList.addAll(stickerList)
                    }
                }
            }
        }
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodGetEmojiGroupInfo.find(dexKit, descriptors) {
            matcher {
                declaredClass = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                usingEqStrings("checkNeedShowOriginVideoBtn")
            }
        }

        methodAddAllGroupItems.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("data")
                addInvoke {
                    usingEqStrings("checkScrollToPosition: ")
                }
            }
        }

        constructorGroupItemInfo.find(dexKit, descriptors) {
            matcher {
                paramTypes("com.tencent.mm.api.IEmojiInfo".toClass(), Int::class.java, String::class.java, Int::class.java)
                usingEqStrings("emojiInfo", "sosDocId")
            }
        }

        serviceManagerMethodGetService.find(dexKit, descriptors) {
            matcher {
                modifiers(Modifier.STATIC)
                paramTypes(Class::class.java)
                usingEqStrings("calling getService(...)")
            }
        }

        classEmojiFeatureService.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.feature.emoji")
            matcher {
                methods {
                    add {
                        usingEqStrings("MicroMsg.EmojiFeatureService", "[onAccountInitialized]")
                    }
                }
            }
        }

        classEmojiMgrImpl.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        usingEqStrings("MicroMsg.emoji.EmojiMgrImpl", "sendEmoji: context is null")
                    }
                }
            }
        }

        classEmojiStorageMgr.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                methods {
                    add {
                        usingEqStrings("MicroMsg.emoji.EmojiStorageMgr", "EmojiStorageMgr: %s")
                    }
                }
            }
        }

        classEmojiInfoStorage.find(dexKit, descriptors) {
            matcher {
                methods {
                    add {
                        usingEqStrings("MicroMsg.emoji.EmojiInfoStorage", "md5 is null or invalue. md5:%s")
                    }
                }
            }
        }

        methodSaveEmojiThumb.find(dexKit, descriptors) {
            matcher {
                declaredClass("com.tencent.mm.storage.emotion.EmojiInfo")
                usingEqStrings("save emoji thumb error")
            }
        }

        return descriptors
    }
}