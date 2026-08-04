package dev.ujhhgtg.wekit.features.items.voip

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import java.lang.reflect.Modifier

@Feature(name = "移除通话时聊天限制", categories = ["聊天", "音视频通话"], description = "绕过正在通话时聊天限制")
object RemoveLimitsDuringCalls : SwitchFeature(), IResolveDex {

    override fun onEnable() {
        listOf(
            methodIsDuringCall,
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

    private val methodIsDuringCall by dexMethod {
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
    private val methodIsMultiTalking by dexMethod {
        matcher {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            usingEqStrings("MicroMsg.DeviceOccupy", "isMultiTalking")
            paramCount = 1
        }
    }

    //    private val methodIsMultiTalking2 by dexMethod()
    private val methodIsCameraUsing by dexMethod {
        matcher {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            usingEqStrings("MicroMsg.DeviceOccupy", "isCameraUsing", "")
        }
    }
    private val methodIsCameraUsing2 by dexMethod {
        matcher {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            usingEqStrings("MicroMsg.DeviceOccupy", "isCameraUsing", "isLiving %b isAnchor %b isAudioMicing %s isVideoMicing %s")
        }
    }
    private val methodIsVoiceUsing by dexMethod {
        matcher {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
            paramCount = 1
        }
    }
    private val methodIsVoiceUsing2 by dexMethod {
        matcher {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
            paramCount = 2
        }
    }
    private val methodCheckAppBrandVoiceUsing by dexMethod {
        matcher {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            usingEqStrings("MicroMsg.DeviceOccupy", "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b")
            paramCount = 1
        }
    }
    private val methodCheckAppBrandVoiceUsing2 by dexMethod {
        matcher {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            usingEqStrings("MicroMsg.DeviceOccupy", "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b")
            paramCount = 2
        }
    }
}
