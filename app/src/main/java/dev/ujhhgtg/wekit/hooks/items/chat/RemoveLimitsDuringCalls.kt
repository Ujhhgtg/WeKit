package dev.ujhhgtg.wekit.hooks.items.chat

import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

@HookItem(path = "聊天/移除通话时聊天限制", description = "绕过正在通话时聊天限制")
object RemoveLimitsDuringCalls : SwitchHookItem(), IResolvesDex {

    override fun onEnable() {
        listOf(methodIsDuringCall,
            methodIsMultiTalking,
            methodIsMultiTalking,
            methodIsCameraUsing,
            methodIsCameraUsing2,
            methodIsVoiceUsing,
            methodIsVoiceUsing2,
            methodCheckAppBrandVoiceUsing,
            methodCheckAppBrandVoiceUsing2
        ).forEach {
            it.hookBefore {
                result = false
            }
        }
    }

    private val methodIsDuringCall by dexMethod()
    private val methodIsMultiTalking by dexMethod()
    private val methodIsMultiTalking2 by dexMethod()
    private val methodIsCameraUsing by dexMethod()
    private val methodIsCameraUsing2 by dexMethod()
    private val methodIsVoiceUsing by dexMethod()
    private val methodIsVoiceUsing2 by dexMethod()
    private val methodCheckAppBrandVoiceUsing by dexMethod()
    private val methodCheckAppBrandVoiceUsing2 by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodIsDuringCall.find(dexKit) {
            matcher {
                declaredClass {
                    modifiers(Modifier.ABSTRACT)
                }

                modifiers(Modifier.STATIC)
                paramCount = 0
                returnType = "boolean"

                addInvoke {
                    declaredClass = "com.tencent.mm.autogen.events.MultiTalkActionEvent"
                }
            }
        }

        methodIsMultiTalking.find(dexKit) {
            matcher {
                declaredClass(methodIsDuringCall.method.declaringClass)
                usingEqStrings("MicroMsg.DeviceOccupy", "isMultiTalking")
                paramCount = 1
            }
        }

        methodIsMultiTalking2.find(dexKit) {
            matcher {
                declaredClass(methodIsDuringCall.method.declaringClass)
                usingEqStrings("MicroMsg.DeviceOccupy", "isMultiTalking")
                paramCount = 2
            }
        }

        methodIsCameraUsing.find(dexKit) {
            matcher {
                declaredClass(methodIsDuringCall.method.declaringClass)
                usingEqStrings("MicroMsg.DeviceOccupy", "isCameraUsing", "")
            }
        }

        methodIsCameraUsing2.find(dexKit) {
            matcher {
                declaredClass(methodIsDuringCall.method.declaringClass)
                usingEqStrings("MicroMsg.DeviceOccupy", "isCameraUsing", "isLiving %b isAnchor %b isAudioMicing %s isVideoMicing %s")
            }
        }

        methodIsVoiceUsing.find(dexKit) {
            matcher {
                declaredClass(methodIsDuringCall.method.declaringClass)
                usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
                paramCount = 1
            }
        }

        methodIsVoiceUsing2.find(dexKit) {
            matcher {
                declaredClass(methodIsDuringCall.method.declaringClass)
                usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
                paramCount = 2
            }
        }

        methodCheckAppBrandVoiceUsing.find(dexKit) {
            matcher {
                declaredClass(methodIsDuringCall.method.declaringClass)
                usingEqStrings("MicroMsg.DeviceOccupy", "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b")
                paramCount = 1
            }
        }

        methodCheckAppBrandVoiceUsing2.find(dexKit) {
            matcher {
                declaredClass(methodIsDuringCall.method.declaringClass)
                usingEqStrings("MicroMsg.DeviceOccupy", "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b")
                paramCount = 2
            }
        }
    }
}
