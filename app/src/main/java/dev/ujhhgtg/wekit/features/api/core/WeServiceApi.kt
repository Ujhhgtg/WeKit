package dev.ujhhgtg.wekit.features.api.core

import android.content.Context
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.dexClass
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.dex.dsl.dexMethodFrom
import dev.ujhhgtg.wekit.dex.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.reflection.BString

object WeServiceApi : ApiFeature() {
    override val technicalId = "微信服务管理服务"
    override val nameRes = R.string.feature_we_service_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_service_api_description

    private val methodServiceManagerGetService by dexMethod {
        paramTypes("java.lang.Class")
        modifiers = 8
        usingEqStrings("calling getService(...)")
    }
    private val classEmojiFeatureService by dexClass {
        searchPackages("com.tencent.mm.feature.emoji")
        method {
            usingEqStrings("MicroMsg.EmojiFeatureService", "[onAccountInitialized]")
        }
    }
    private val classStorageFeatureService by dexClass {
        searchPackages("com.tencent.mm.plugin.messenger.foundation")
        method {
            returnType {
                usingEqString("PRAGMA table_info( contact_ext )")
            }
        }
        method {
            returnType {
                usingEqString("MicroMsg.MsgInfoStorage")
                usingEqString("deleted dirty msg ,count is %d")
            }
        }
        method {
            returnType {
                usingEqString("PRAGMA table_info( rconversation)")
            }
        }
    }
    private val classChatroomService by dexClass {
        usingEqStrings("MicroMsg.ChatroomService", "[isEnableRoomManager]")
    }
    val classImageInfoStorage by dexClass {
        usingEqStrings("MicroMsg.ImgInfoStorage", "generateMd5: %s, %s")
    }
    val methodDownloadImageServiceDownloadImage by dexMethod {
        usingEqStrings("ModelImage.DownloadImgService", "] add failed, task already done")
    }
    val classImageFeatureService by dexClass {
        field {
            type = classImageInfoStorage.data.name
        }
        field {
            type {
                className = methodDownloadImageServiceDownloadImage.data.declaredClassName
            }
        }
    }
    private val methodApiManagerGetApi by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.manager")
        usingEqStrings("[get] ", " is not a interface!")
    }
    private val methodMmKernelGetServiceImpl by dexMethod {
        declaredClass = classMmKernel.data.name
        paramTypes(Class::class.java.name)
    }
    private val classMmKernel by dexClass {
        usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.")
    }
    val classVideoService by dexClass {
        usingEqStrings(
            "MicroMsg.VideoService",
            "MicroMsg.SubCoreVideo",
            "quitVideoSendThread",
        )
    }
    val classEmojiMgrImpl by dexClass {
        usingEqStrings("MicroMsg.emoji.EmojiMgrImpl", "sendEmoji: context is null")
    }
    val classEmojiInfoStorage by dexClass {
        usingEqStrings("MicroMsg.emoji.EmojiInfoStorage", "md5 is null or invalue. md5:%s")
    }
    private val classEmojiStorageMgr by dexClass {
        searchPackages("com.tencent.mm.storage")
        usingEqStrings("MicroMsg.emoji.EmojiStorageMgr", "EmojiStorageMgr: %s")
    }
    val methodSaveEmojiThumb by dexMethod {
        declaredClass = "com.tencent.mm.storage.emotion.EmojiInfo"
        usingEqStrings("save emoji thumb error")
    }
    val apiManagerClass: Class<*>
        get() = methodApiManagerGetApi.method.declaringClass

    val emojiFeatureService
        get() = getServiceByClass(classEmojiFeatureService.clazz)

    val emojiStorageMgr
        get() =
            classEmojiStorageMgr
                .reflekt()
                .firstMethod {
                    modifiers(Modifiers.STATIC)
                    returnType = classEmojiStorageMgr.clazz
                }
                .invoke()!!

    val emojiMgr
        get() =
            emojiFeatureService
                .reflekt()
                .firstMethod {
                    parameterCount = 0
                    returnType = classEmojiMgrImpl.clazz
                }
                .invoke(emojiFeatureService)!!

    val emojiMgrImpl
        get() =
            emojiFeatureService
                .reflekt()
                .firstMethod {
                    returnType = classEmojiMgrImpl.clazz
                }
                .invoke()!!

    fun processEmojiPath(path: String): String =
        emojiMgrImpl
            .reflekt()
            .firstMethod {
                parameters(Context::class, BString)
                returnType = BString
            }
            .invoke(HostInfo.application, path)!!
            as String

    fun saveEmojiThumb(path: String): Any =
        emojiInfoStorage
            .reflekt()
            .firstMethod {
                parameters(BString)
                returnType = methodSaveEmojiThumb.method.declaringClass
            }
            .invoke(path)!!

    fun getEmojiMd5FromPath(context: Context, path: String): String =
        emojiMgrImpl
            .reflekt()
            .firstMethod {
                parameters(Context::class.java, String::class.java)
                returnType = String::class.java
            }
            .invoke(context, path) as String

    val emojiInfoStorage
        get() =
            emojiStorageMgr
                .reflekt()
                .firstMethod {
                    returnType = classEmojiInfoStorage.clazz
                }
                .invoke()!!

    fun getEmojiInfoByMd5(md5: String): Any =
        emojiInfoStorage
            .reflekt()
            .firstMethod {
                parameters(BString)
                returnType = "com.tencent.mm.storage.emotion.EmojiInfo"
            }
            .invoke(md5)!!

    val storageFeatureService
        get() = getServiceByClass(classStorageFeatureService.clazz)

    val msgInfoStorage
        get() =
            storageFeatureService
                .reflekt()
                .firstMethod {
                    parameterCount = 0
                    returnType = WeMessageApi.classMsgInfoStorage.clazz
                }
                .invoke()!!

    val chatroomService
        get() = getServiceImplByClass(classChatroomService.clazz.interfaces[0])

    val chatroomStorage
        get() =
            chatroomService
                .reflekt()
                .firstField {
                    type =
                        WeConversationApi.methodChatroomStorageGetMemberCount.method.declaringClass
                }
                .get()!!

    fun getServiceByClass(clazz: Class<*>): Any =
        methodServiceManagerGetService.method.invoke(null, clazz)!!

    fun getServiceImplByClass(clazz: Class<*>): Any =
        methodMmKernelGetServiceImpl.method.invoke(null, clazz)!!

    fun getApiByClass(apiManager: Any, clazz: Class<*>): Any =
        methodApiManagerGetApi.method.invoke(apiManager, clazz.interfaces[0])!!

    val imageInfoStorage
        get() =
            classImageFeatureService
                .reflekt()
                .firstMethod {
                    modifiers(Modifiers.STATIC)
                    returnType = classImageInfoStorage.clazz
                }
                .invoke()!!

    fun getImageMd5FromMsgInfo(msgInfo: MessageInfo): String =
        imageInfoStorage
            .reflekt()
            .firstMethod {
                returnType = String::class
                parameters(WeMessageApi.classMsgInfo.clazz)
            }
            .invoke(msgInfo.instance)!!
            as String

    val videoPathFeatureService
        get() =
            getServiceByClass(
                methodVideoPathFeatureServiceRestoreMp4Path.method.declaringClass.interfaces[0],
            )

    fun getVideoMp4PathFromMsgInfo(msgInfo: MessageInfo): String =
        methodVideoPathFeatureServiceRestoreMp4Path.method.invoke(
            videoPathFeatureService,
            msgInfo.imagePath!!,
        ) as String

    private val methodVideoPathFeatureServiceRestoreMp4Path by dexMethodFrom {
        val results =
            DexResolutionContext.findMethods {
                    usingEqString("MicroMsg.C2CVideoPathFeatureService")
                    usingEqString("success restore file, from ")
                    usingEqString(".mp4")
                }
                .ifEmpty {
                    DexResolutionContext.findMethods {
                        usingEqString("MicroMsg.VideoInfoStorage")
                        usingEqString("success restore file, from ")
                        usingEqString(".mp4")
                    }
                }
        results.single()
    }
}
