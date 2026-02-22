package moe.ouom.wekit.hooks.sdk.base

import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

@HookItem(path = "API/微信服务管理服务", desc = "为其他功能提供获取并使用微信服务的能力")
object WeServiceApi : ApiHookItem(), IDexFind {

    private val methodServiceManagerGetService by dexMethod()
    private val classEmojiFeatureService by dexClass()
    private val classContactStorage by dexClass()
    private val classMsgInfoStorage by dexClass()
    private val classConversationStorage by dexClass()
//    private val classStorageFeatureService by dexClass()

    val emojiFeatureService by lazy {
        getServiceByClass(classEmojiFeatureService.clazz)
    }

//    val storageFeatureService by lazy {
//        getServiceByClass(classStorageFeatureService.clazz)
//    }

    fun getServiceByClass(clazz: Class<*>): Any {
        return methodServiceManagerGetService.method.invoke(null, clazz)!!
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodServiceManagerGetService.find(dexKit, descriptors) {
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

        classContactStorage.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("PRAGMA table_info( contact_ext )")
            }
        }

        classMsgInfoStorage.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("MicroMsg.MsgInfoStorage", "deleted dirty msg ,count is %d")
            }
        }

        classConversationStorage.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("PRAGMA table_info( rconversation)")
            }
        }

        // FIXME
//        classStorageFeatureService.find(dexKit, descriptors) {
//            searchPackages("com.tencent.mm.plugin.messenger.foundation")
//            matcher {
//                methods {
//                    add {
//                        paramTypes(
//                            classContactStorage.clazz,
//                            classMsgInfoStorage.clazz,
//                            classConversationStorage.clazz)
//                    }
//                }
//            }
//        }

        return descriptors
    }

}