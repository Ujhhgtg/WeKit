package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.reflection.int
import java.lang.reflect.Modifier

object RemoveCustomStickersLimit : SwitchFeature(), IResolveDex {

    override val technicalId = "解除单个表情数量上限"
    override val nameRes = R.string.feature_remove_custom_stickers_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_remove_custom_stickers_limit_description

    private val methodGetCustomEmojiMaxSize by dexMethod {
        matcher {
            usingEqStrings("CustomEmojiMaxSize")
            returnType(int)
            paramCount(0)
            modifiers = Modifier.STATIC
        }
    }

    private val methodComputeEmojiStorageState by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.EmojiStorageState", "normal_custom_size")
            returnType(Void.TYPE)
            paramCount(1)
        }
    }

    private val classMmkv by dexClass {
        matcher {
            usingEqStrings("MicroMsg.MultiProcessMMKV", "getMMKV name is illegal")
        }
    }

    private val methodGetMmkv by dexMethod {
        matcher {
            declaredClass(classMmkv.data.name)
            usingEqStrings("MicroMsg.MultiProcessMMKV", "getMMKV name is illegal")
        }
    }

    private val classCgiBack by dexClass {
        matcher {
            usingEqStrings("CgiBack{errType=")
        }
    }

    private val methodCreateCgiBack by dexMethod {
        matcher {
            declaredClass(classCgiBack.data.name)
            modifiers = Modifier.STATIC
            paramCount(6)
            returnType(classCgiBack.data.name)
        }
    }

    private val methodAddEmojiOnSceneEnd by dexMethod {
        matcher {
            usingEqStrings("CgiBackupEmojiOperate onResult: errType=")
        }
    }

    private val methodNetSceneBackupEmojiOperateOnGYNetEnd by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.emoji.NetSceneBackupEmojiOperate", "errType:%d, errCode:%d")
            name = "onGYNetEnd"
        }
    }

    private val methodChattingUiEmoji by dexMethod {
        matcher {
            usingEqStrings("addToCustom. over max size.")
        }
    }

    private val methodConditionallyShowDialog by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("uninstallEmoticon Failed to send net scene: ")
            }
            usingEqStrings("custom_full")
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
            (classCgiBack.clazz as Class<Any>).reflekt().fields {
                type = int
            }.forEach {
                if (it.get(res) == -434) {
                    it.set(res, 0)
                }
            }
        }

        methodAddEmojiOnSceneEnd.hookBefore {
            val resp = args[0] ?: return@hookBefore
            @Suppress("UNCHECKED_CAST")
            (classCgiBack.clazz as Class<Any>).reflekt().fields {
                type = int
            }.forEach {
                if (it.get(resp) == -434) {
                    it.set(resp, 0)
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
            methodConditionallyShowDialog
        ).forEach {
            it.hookBefore {
                putCustomFullFalseInMmkv()
            }
            it.hookAfter {
                putCustomFullFalseInMmkv()
            }
        }
    }
}
