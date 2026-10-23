package dev.ujhhgtg.wekit.features.api.core

import android.content.Context
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.sql.sqlClass
import dev.ujhhgtg.wekit.dexkit.sql.sqlMethod
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.reflection.BString
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

object WeServiceApi : ApiFeature(), IResolveDex {

    override val technicalId = "微信服务管理服务"
    override val nameRes = R.string.feature_we_service_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_service_api_description

    private val methodServiceManagerGetService by dexMethod(
        sqlMethod {
            paramTypes("java.lang.Class")
            modifiers = 8
            usingEqStrings("calling getService(...)")
        },
    )
    private val classEmojiFeatureService by dexClass(
        sqlClass {
            searchPackages("com.tencent.mm.feature.emoji")
            method {
                usingEqStrings("MicroMsg.EmojiFeatureService", "[onAccountInitialized]")
            }
        },
    )
    private val classStorageFeatureService by dexClass {
        searchPackages("com.tencent.mm.plugin.messenger.foundation")
        matcher {
            addMethod {
                returnType {
                    usingEqStrings("PRAGMA table_info( contact_ext )")
                }
            }
            addMethod {
                returnType {
                    usingEqStrings("MicroMsg.MsgInfoStorage", "deleted dirty msg ,count is %d")
                }
            }
            addMethod {
                returnType {
                    usingEqStrings("PRAGMA table_info( rconversation)")
                }
            }
        }
    }
    private val classChatroomService by dexClass(
        sqlClass {
            usingEqStrings("MicroMsg.ChatroomService", "[isEnableRoomManager]")
        },
    )
    val classImageInfoStorage by dexClass(
        sqlClass {
            usingEqStrings("MicroMsg.ImgInfoStorage", "generateMd5: %s, %s")
        },
    )
    val methodDownloadImageServiceDownloadImage by dexMethod(
        sqlMethod {
            usingEqStrings("ModelImage.DownloadImgService", "] add failed, task already done")
        },
    )
    val classImageFeatureService by dexClass {
        matcher {
            addFieldForType(classImageInfoStorage.data.name)
            addFieldForType(methodDownloadImageServiceDownloadImage.data.declaredClassName)
        }
    }
    private val methodApiManagerGetApi by dexMethod(
        sqlMethod {
            searchPackages("com.tencent.mm.ui.chatting.manager")
            usingEqStrings("[get] ", " is not a interface!")
        },
    )
    private val methodMmKernelGetServiceImpl by dexMethod()
    private val classMmKernel by dexClass(
        sqlClass {
            usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.")
        },
    )
    private val methodVideoPathFeatureServiceRestoreMp4Path by dexMethod() // formerly VideoInfoStorage
    val classVideoService by dexClass(
        sqlClass {
            usingEqStrings("MicroMsg.VideoService", "MicroMsg.SubCoreVideo", "quitVideoSendThread")
        },
    )
    val classEmojiMgrImpl by dexClass(
        sqlClass {
            usingEqStrings("MicroMsg.emoji.EmojiMgrImpl", "sendEmoji: context is null")
        },
    )
    val classEmojiInfoStorage by dexClass(
        sqlClass {
            usingEqStrings("MicroMsg.emoji.EmojiInfoStorage", "md5 is null or invalue. md5:%s")
        },
    )
    private val classEmojiStorageMgr by dexClass(
        sqlClass {
            searchPackages("com.tencent.mm.storage")
            usingEqStrings("MicroMsg.emoji.EmojiStorageMgr", "EmojiStorageMgr: %s")
        },
    )
    val methodSaveEmojiThumb by dexMethod(
        sqlMethod {
            declaredClass = "com.tencent.mm.storage.emotion.EmojiInfo"
            usingEqStrings("save emoji thumb error")
        },
    )
    val apiManagerClass: Class<*>
        get() = methodApiManagerGetApi.method.declaringClass

    val emojiFeatureService
        get() = getServiceByClass(classEmojiFeatureService.clazz)

    val emojiStorageMgr
        get() = classEmojiStorageMgr.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            returnType = classEmojiStorageMgr.clazz
        }.invoke()!!

    val emojiMgr
        get() = emojiFeatureService.reflekt()
            .firstMethod {
                parameterCount = 0
                returnType = classEmojiMgrImpl.clazz
            }.invoke(emojiFeatureService)!!

    val emojiMgrImpl
        get() = emojiFeatureService.reflekt()
            .firstMethod {
                returnType = classEmojiMgrImpl.clazz
            }
            .invoke()!!

    fun processEmojiPath(path: String): String {
        return emojiMgrImpl.reflekt().firstMethod {
            parameters(Context::class, BString)
            returnType = BString
        }.invoke(HostInfo.application, path)!! as String
    }

    fun saveEmojiThumb(path: String): Any {
        return emojiInfoStorage.reflekt().firstMethod {
            parameters(BString)
            returnType = methodSaveEmojiThumb.method.declaringClass
        }.invoke(path)!!
    }

    fun getEmojiMd5FromPath(context: Context, path: String): String {
        return emojiMgrImpl
            .reflekt()
            .firstMethod {
                parameters(Context::class.java, String::class.java)
                returnType = String::class.java
            }
            .invoke(context, path) as String
    }

    val emojiInfoStorage
        get() = emojiStorageMgr.reflekt()
            .firstMethod {
                returnType = classEmojiInfoStorage.clazz
            }
            .invoke()!!

    fun getEmojiInfoByMd5(md5: String): Any {
        return emojiInfoStorage.reflekt()
            .firstMethod {
                parameters(BString)
                returnType = "com.tencent.mm.storage.emotion.EmojiInfo"
            }
            .invoke(md5)!!
    }

    val storageFeatureService
        get() = getServiceByClass(classStorageFeatureService.clazz)

    val msgInfoStorage
        get() = storageFeatureService.reflekt()
            .firstMethod {
                parameterCount = 0
                returnType = WeMessageApi.classMsgInfoStorage.clazz
            }
            .invoke()!!

    val chatroomService
        get() = getServiceImplByClass(classChatroomService.clazz.interfaces[0])

    val chatroomStorage
        get() =
            chatroomService.reflekt().firstField {
                type = WeConversationApi.methodChatroomStorageGetMemberCount.method.declaringClass
            }.get()!!

    fun getServiceByClass(clazz: Class<*>): Any {
        return methodServiceManagerGetService.method.invoke(null, clazz)!!
    }

    fun getServiceImplByClass(clazz: Class<*>): Any {
        return methodMmKernelGetServiceImpl.method.invoke(null, clazz)!!
    }

    fun getApiByClass(apiManager: Any, clazz: Class<*>): Any {
        return methodApiManagerGetApi.method.invoke(apiManager, clazz.interfaces[0])!!
    }

    val imageInfoStorage
        get() = classImageFeatureService.reflekt()
            .firstMethod {
                modifiers(Modifiers.STATIC)
                returnType = classImageInfoStorage.clazz
            }.invoke()!!

    fun getImageMd5FromMsgInfo(msgInfo: MessageInfo): String {
        return imageInfoStorage.reflekt()
            .firstMethod {
                returnType = String::class
                parameters(WeMessageApi.classMsgInfo.clazz)
            }.invoke(msgInfo.instance)!! as String
    }

    val videoPathFeatureService
        get() = getServiceByClass(
            methodVideoPathFeatureServiceRestoreMp4Path
                .method.declaringClass
                .interfaces[0]
        )

    fun getVideoMp4PathFromMsgInfo(msgInfo: MessageInfo): String {
        return methodVideoPathFeatureServiceRestoreMp4Path.method.invoke(
            videoPathFeatureService, msgInfo.imagePath!!
        ) as String
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodMmKernelGetServiceImpl.find(dexKit) {
            matcher {
                declaredClass = classMmKernel.data.name
                paramTypes(Class::class.java)
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
    }
}
