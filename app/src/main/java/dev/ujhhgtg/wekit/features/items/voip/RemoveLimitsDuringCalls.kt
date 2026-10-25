package dev.ujhhgtg.wekit.features.items.voip

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.abc.IResolveDex
import dev.ujhhgtg.wekit.dex.dsl.data
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.dex.matcher.AnnotationVisibility
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import java.lang.reflect.Modifier

object RemoveLimitsDuringCalls : SwitchFeature(), IResolveDex {
    override val technicalId = "移除通话时聊天限制"
    override val nameRes = R.string.feature_remove_limits_during_calls_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT, FeatureCategoryIds.VOIP)
    override val descriptionRes = R.string.feature_remove_limits_during_calls_description

    override fun onEnable() {
        listOf(
                methodIsDuringCall,
                methodIsMultiTalking,
                methodIsMultiTalking2,
                methodIsCameraUsing,
                methodIsCameraUsing2,
                methodIsVoiceUsing,
                methodIsVoiceUsing2,
                methodCheckAppBrandVoiceUsing,
                methodCheckAppBrandVoiceUsing2,
                methodMultiTalkCallBack,
                methodMultiTalkCallBackV2,
                methodVoipCallBack,
                methodIpCallCallBack,
                methodFlutterLinkVoipCallBack,
            )
            .forEach {
                if (!it.isFound) return@forEach
                it.hookBefore {
                    result = false
                }
            }
    }

    private val methodIsDuringCall by dexMethod {
        declaredClass {
            modifiers = Modifier.ABSTRACT
        }
        modifiers = Modifier.STATIC
        paramCount = 0
        returnType = "boolean"
        invokeMethod {
            declaredClass {
                className = "com.tencent.mm.autogen.events.MultiTalkActionEvent"
            }
        }
    }
    private val methodIsMultiTalking by dexMethod {
        declaredClass(methodIsDuringCall.data.declaredClassName)
        paramCount = 1
        usingEqStrings("MicroMsg.DeviceOccupy", "isMultiTalking")
    }

    private val methodIsMultiTalking2 by dexMethod {
        declaredClass(methodIsDuringCall.data.declaredClassName)
        paramCount = 2
        usingEqStrings("MicroMsg.DeviceOccupy", "isMultiTalking")
    }
    private val methodIsCameraUsing by dexMethod {
        declaredClass(methodIsDuringCall.data.declaredClassName)
        usingEqStrings("MicroMsg.DeviceOccupy", "isCameraUsing", "")
    }
    private val methodIsCameraUsing2 by dexMethod {
        declaredClass(methodIsDuringCall.data.declaredClassName)
        usingEqStrings(
            "MicroMsg.DeviceOccupy",
            "isCameraUsing",
            "isLiving %b isAnchor %b isAudioMicing %s isVideoMicing %s",
        )
    }
    private val methodIsVoiceUsing by dexMethod {
        declaredClass(methodIsDuringCall.data.declaredClassName)
        paramCount = 1
        usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
    }
    private val methodIsVoiceUsing2 by dexMethod {
        declaredClass(methodIsDuringCall.data.declaredClassName)
        paramCount = 2
        usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
    }
    private val methodCheckAppBrandVoiceUsing by dexMethod {
        declaredClass(methodIsDuringCall.data.declaredClassName)
        paramCount = 1
        usingEqStrings(
            "MicroMsg.DeviceOccupy",
            "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b",
        )
    }
    private val methodCheckAppBrandVoiceUsing2 by dexMethod {
        declaredClass(methodIsDuringCall.data.declaredClassName)
        paramCount = 2
        usingEqStrings(
            "MicroMsg.DeviceOccupy",
            "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b",
        )
    }

    // 8.0.77 moved the multitalk action listener out of
    // com.tencent.mm.plugin.multitalk.model (MultiTalkManager$18) into
    // com.tencent.mm.voipmp.v2 (VoIPMPCoreV2$multiTalkActionListener$1),
    // so this resolver has no result on 8.0.77 and the next one has no
    // result before it.
    private val methodMultiTalkCallBack by
        dexMethod(allowFailure = true) {
            searchPackages("com.tencent.mm.plugin.multitalk.model")
            declaredClass {
                annotation {
                    type = "dalvik.annotation.Signature"
                    visibility = AnnotationVisibility.System
                    elements(
                        "value",
                        "Lcom/tencent/mm/sdk/event/IListener<",
                        "Lcom/tencent/mm/autogen/events/MultiTalkActionEvent;",
                        ">;",
                    )
                }
            }
            name = "callback"
            paramCount = 1
            returnType = "boolean"
        }

    private val methodMultiTalkCallBackV2 by
        dexMethod(allowFailure = true) {
            searchPackages("com.tencent.mm.voipmp.v2")
            // searchPackages is prefix-matched, so it also hits the nested listener
            // MultiTalkSelectContactUI$3 in multitalk.ui; that one must not be hooked (its
            // pre-8.0.77 counterpart in com.tencent.mm.plugin.multitalk.ui was never matched).
            excludePackages("com.tencent.mm.voipmp.v2.multitalk")
            declaredClass {
                annotation {
                    type = "dalvik.annotation.Signature"
                    visibility = AnnotationVisibility.System
                    elements(
                        "value",
                        "Lcom/tencent/mm/sdk/event/IListener<",
                        "Lcom/tencent/mm/autogen/events/MultiTalkActionEvent;",
                        ">;",
                    )
                }
            }
            name = "callback"
            paramCount = 1
            returnType = "boolean"
        }

    private val methodVoipCallBack by dexMethod {
        searchPackages("com.tencent.mm.plugin.voip.model")
        declaredClass {
            annotation {
                type = "dalvik.annotation.Signature"
                visibility = AnnotationVisibility.System
                elements(
                    "value",
                    "Lcom/tencent/mm/sdk/event/IListener<",
                    "Lcom/tencent/mm/autogen/events/VoipCheckIsDeviceUsingEvent;",
                    ">;",
                )
            }
        }
        name = "callback"
        paramCount = 1
        returnType = "boolean"
    }

    private val methodIpCallCallBack by dexMethod {
        searchPackages("com.tencent.mm.plugin.ipcall")
        declaredClass {
            annotation {
                type = "dalvik.annotation.Signature"
                visibility = AnnotationVisibility.System
                elements(
                    "value",
                    "Lcom/tencent/mm/sdk/event/IListener<",
                    "Lcom/tencent/mm/autogen/events/VoipCheckIsDeviceUsingEvent;",
                    ">;",
                )
            }
        }
        name = "callback"
        paramCount = 1
        returnType = "boolean"
    }

    private val methodFlutterLinkVoipCallBack by dexMethod {
        searchPackages(
            "com.tencent.mm.voipmp.helper",
            "com.tencent.mm.plugin_flutter_ilinkvoip.helper",
        )
        declaredClass {
            annotation {
                type = "dalvik.annotation.Signature"
                visibility = AnnotationVisibility.System
                elements(
                    "value",
                    "Lcom/tencent/mm/sdk/event/IListener<",
                    "Lcom/tencent/mm/autogen/events/VoipCheckIsDeviceUsingEvent;",
                    ">;",
                )
            }
        }
        name = "callback"
        paramCount = 1
        returnType = "boolean"
    }
}
