package dev.ujhhgtg.wekit.hooks.api.core

import com.highcapable.kavaref.condition.type.Modifiers
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

@HookItem(path = "API/微信服务管理服务", description = "提供获取并使用微信服务的能力")
object WeServiceApi : ApiHookItem(), IResolvesDex {

    private val methodServiceManagerGetService by dexMethod()
    private val classEmojiFeatureService by dexClass()
    private val classContactStorage by dexClass()
    private val classConversationStorage by dexClass()
    private val classStorageFeatureService by dexClass()
    private val classChatroomService by dexClass()
    private val classImageInfoStorage by dexClass()
    private val classDownloadImageService by dexClass()
    private val classImageFeatureService by dexClass()
    private val methodApiManagerGetApi by dexMethod()
    private val methodMmKernelGetServiceImpl by dexMethod()
    private val classMsgInfoStorage by dexClass()
    private val methodVideoPathFeatureServiceRestoreMp4Path by dexMethod() // formerly VideoInfoStorage
    private val classVideoService by dexClass()

    val classApiManager: Class<*> by lazy { methodApiManagerGetApi.method.declaringClass }

    val emojiFeatureService by lazy {
        getServiceByClass(classEmojiFeatureService.clazz)
    }

    val storageFeatureService by lazy {
        getServiceByClass(classStorageFeatureService.clazz)
    }

    val messageInfoStorage by lazy {
        storageFeatureService.asResolver()
            .firstMethod {
                parameterCount = 0
                returnType = WeMessageApi.classMsgInfoStorage.clazz
            }
            .invoke()!!
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

    val imageInfoStorage by lazy {
        classImageFeatureService.asResolver()
            .firstMethod {
                modifiers { it.contains(Modifiers.STATIC) }
                returnType = classImageInfoStorage.clazz
            }.invoke()!!
    }

    fun getImageMd5FromMsgInfo(msgInfo: MessageInfo): String {
        val imageInfoStorage = imageInfoStorage
        return imageInfoStorage.asResolver()
            .firstMethod {
                returnType = String::class
                parameters(WeMessageApi.classMsgInfo.clazz)
            }.invoke(msgInfo.instance)!! as String
    }

    val videoPathFeatureService by lazy {
        classVideoService.asResolver()
            .firstMethod {
                modifiers { it.contains(Modifiers.STATIC) }
                returnType = methodVideoPathFeatureServiceRestoreMp4Path.method.declaringClass
            }.invoke()!!
    }

    fun getVideoMp4PathFromMsgInfo(msgInfo: MessageInfo): String {
        return methodVideoPathFeatureServiceRestoreMp4Path.method.invoke(
            videoPathFeatureService, msgInfo.imagePath
        ) as String
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        val classMmKernel = dexKit.findClass {
            matcher {
                usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.")
            }
        }.single()

        methodMmKernelGetServiceImpl.find(dexKit) {
            matcher {
                declaredClass = classMmKernel.name
                paramTypes(Class::class.java)
            }
        }

        methodServiceManagerGetService.find(dexKit) {
            matcher {
                modifiers(Modifier.STATIC)
                paramTypes(Class::class.java)
                usingEqStrings("calling getService(...)")
            }
        }

        classEmojiFeatureService.find(dexKit) {
            searchPackages("com.tencent.mm.feature.emoji")
            matcher {
                methods {
                    add {
                        usingEqStrings("MicroMsg.EmojiFeatureService", "[onAccountInitialized]")
                    }
                }
            }
        }

        classImageInfoStorage.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.ImgInfoStorage", "generateMd5: %s, %s")
            }
        }

        classDownloadImageService.find(dexKit) {
            matcher {
                usingEqStrings("ModelImage.DownloadImgService", "cancelNetScene reset curTaskInfo (%s %s %s)")
            }
        }

        classImageFeatureService.find(dexKit) {
            matcher {
                addFieldForType(classImageInfoStorage.clazz)
                addFieldForType(classDownloadImageService.clazz)
            }
        }

        classContactStorage.find(dexKit) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("PRAGMA table_info( contact_ext )")
            }
        }

        classConversationStorage.find(dexKit) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("PRAGMA table_info( rconversation)")
            }
        }

        classMsgInfoStorage.find(dexKit) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("MicroMsg.MsgInfoStorage", "deleted dirty msg ,count is %d")
            }
        }

        classStorageFeatureService.find(dexKit) {
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

        classChatroomService.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.ChatroomService", "[isEnableRoomManager]")
            }
        }

        val results = dexKit.findMethod {
            // >= 8.0.61
            matcher {
                usingEqStrings("MicroMsg.C2CVideoPathFeatureService", "success restore file, from ", ".mp4")
            }
        }.ifEmpty {
            // < 8.0.61
            dexKit.findMethod {
                matcher {
                    usingEqStrings("MicroMsg.VideoInfoStorage", "success restore file, from ", ".mp4")
                }
            }
        }
        methodVideoPathFeatureServiceRestoreMp4Path.setDescriptor(results.single())

        classVideoService.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.VideoService", "MicroMsg.SubCoreVideo", "quitVideoSendThread")
            }
        }

        methodApiManagerGetApi.find(dexKit) {
            searchPackages("com.tencent.mm.ui.chatting.manager")
            matcher {
                usingEqStrings("[get] ", " is not a interface!")
            }
        }
    }
}
