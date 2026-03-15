package moe.ouom.wekit.hooks.api.core

import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

@HookItem(path = "API/微信服务管理服务", desc = "为其他功能提供获取并使用微信服务的能力")
object WeServiceApi : ApiHookItem(), IResolvesDex {

    private val methodServiceManagerGetService by dexMethod()
    private val classEmojiFeatureService by dexClass()
    private val classContactStorage by dexClass()
    private val classConversationStorage by dexClass()
    private val classStorageFeatureService by dexClass()
    private val classChatroomService by dexClass()
    val methodApiManagerGetApi by dexMethod()
    private val methodMmKernelGetServiceImpl by dexMethod()
    private val classMsgInfoStorage by dexClass()

    val emojiFeatureService by lazy {
        getServiceByClass(classEmojiFeatureService.clazz)
    }

    val storageFeatureService by lazy {
        getServiceByClass(classStorageFeatureService.clazz)
    }

    val chatroomService by lazy {
        getServiceImplByClass(classChatroomService.clazz.interfaces[0])
    }

    fun getServiceByClass(clazz: Class<*>): Any {
        return methodServiceManagerGetService.method.invoke(null, clazz)!!
    }

    fun getServiceImplByClass(clazz: Class<*>): Any {
        return methodMmKernelGetServiceImpl.method.invoke(null, clazz)!!
    }

    fun getApiByClass(apiManager: Any, clazz: Class<*>): Any {
        return methodApiManagerGetApi.method.invoke(apiManager, clazz.interfaces[0])!!
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
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

        classConversationStorage.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("PRAGMA table_info( rconversation)")
            }
        }

        classMsgInfoStorage.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("MicroMsg.MsgInfoStorage", "deleted dirty msg ,count is %d")
            }
        }

        classStorageFeatureService.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.plugin.messenger.foundation")
            matcher {
                addMethod {
                    returnType(classContactStorage.clazz)
                }
                addMethod {
                    returnType(classMsgInfoStorage.clazz)
                }
                addMethod {
                    returnType(classConversationStorage.clazz)
                }
            }
        }

        classChatroomService.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("MicroMsg.ChatroomService", "[isEnableRoomManager]")
            }
        }

        methodApiManagerGetApi.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.ui.chatting.manager")
            matcher {
                usingEqStrings("[get] ", " is not a interface!")
            }
        }

        methodMmKernelGetServiceImpl.find(dexKit, descriptors) {
            matcher {
                declaredClass(WeDatabaseApi.classMmKernel.clazz)
                paramTypes(Class::class.java)
            }
        }

        return descriptors
    }

}