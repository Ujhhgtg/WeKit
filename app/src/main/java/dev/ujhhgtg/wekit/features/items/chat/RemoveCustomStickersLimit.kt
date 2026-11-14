package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.dexClass
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.reflection.int

object RemoveCustomStickersLimit : SwitchFeature() {
    override val technicalId = "解除单个表情数量上限"
    override val nameRes = R.string.feature_remove_custom_stickers_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_remove_custom_stickers_limit_description

    private val methodGetCustomEmojiMaxSize by dexMethod {
        returnType = "int"
        paramCount = 0
        modifiers = 8
        usingEqStrings("CustomEmojiMaxSize")
    }

    private val methodComputeEmojiStorageState by dexMethod {
        returnType = "void"
        paramCount = 1
        usingEqStrings("MicroMsg.EmojiStorageState", "normal_custom_size")
    }

    private val classMmkv by dexClass {
        usingEqStrings("MicroMsg.MultiProcessMMKV", "getMMKV name is illegal")
    }

    private val methodGetMmkv by dexMethod {
        declaredClass(classMmkv.data.name)
        usingEqStrings("MicroMsg.MultiProcessMMKV", "getMMKV name is illegal")
    }

    private val classCgiBack by dexClass {
        usingEqStrings("CgiBack{errType=")
    }

    private val methodCreateCgiBack by dexMethod {
        declaredClass(classCgiBack.data.name)
        returnType(classCgiBack.data.name)
        paramCount = 6
        modifiers = 8
    }

    private val methodAddEmojiOnSceneEnd by dexMethod {
        usingEqStrings("CgiBackupEmojiOperate onResult: errType=")
    }

    private val methodNetSceneBackupEmojiOperateOnGYNetEnd by dexMethod {
        name = "onGYNetEnd"
        usingEqStrings(
            "MicroMsg.emoji.NetSceneBackupEmojiOperate",
            "errType:%d, errCode:%d",
        )
    }

    private val methodChattingUiEmoji by dexMethod {
        usingEqStrings("addToCustom. over max size.")
    }

    private val methodConditionallyShowDialog by dexMethod {
        usingEqStrings("custom_full")
        declaredClass {
            usingEqStrings("uninstallEmoticon Failed to send net scene: ")
        }
    }

    private fun putCustomFullFalseInMmkv() {
        runCatching {
            val mmkv = methodGetMmkv.method.invoke(null, "emoji_stg", 2, null)
            mmkv.reflekt().invokeMethod("putBoolean", "custom_full", false)
        }
    }

    override fun onEnable() {
        methodGetCustomEmojiMaxSize.hookBefore {
            result = Int.MAX_VALUE
        }

        methodCreateCgiBack.hookBefore {
            if (args[1] as? Int == -434) {
                args[0] = 0
                args[1] = 0
                args[2] = ""
            }
        }

        methodCreateCgiBack.hookAfter {
            val res = result ?: return@hookAfter
            @Suppress("UNCHECKED_CAST")
            (classCgiBack.clazz as Class<Any>)
                .reflekt()
                .fields {
                    type = int
                }
                .forEach {
                    // Bound, so a static int field is read/written like before instead of failing
                    // arity.
                    val field = it.of(res)
                    if (field.get() == -434) {
                        field.set(0)
                    }
                }
        }

        methodAddEmojiOnSceneEnd.hookBefore {
            val resp = args[0] ?: return@hookBefore
            @Suppress("UNCHECKED_CAST")
            (classCgiBack.clazz as Class<Any>)
                .reflekt()
                .fields {
                    type = int
                }
                .forEach {
                    // Bound, so a static int field is read/written like before instead of failing
                    // arity.
                    val field = it.of(resp)
                    if (field.get() == -434) {
                        field.set(0)
                    }
                }
        }

        methodNetSceneBackupEmojiOperateOnGYNetEnd.hookBefore {
            if (args[2] as? Int == -434) {
                args[1] = 0
                args[2] = 0
            }
        }

        listOf(
                methodComputeEmojiStorageState,
                methodChattingUiEmoji,
                methodConditionallyShowDialog,
            )
            .forEach {
                it.hookBefore {
                    putCustomFullFalseInMmkv()
                }
                it.hookAfter {
                    putCustomFullFalseInMmkv()
                }
            }
    }
}
