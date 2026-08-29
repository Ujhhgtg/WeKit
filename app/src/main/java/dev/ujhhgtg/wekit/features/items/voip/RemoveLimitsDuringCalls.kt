package dev.ujhhgtg.wekit.features.items.voip

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
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
            methodFlutterLinkVoipCallBack
        ).forEach {
            if (it.isPlaceholder) return@forEach
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

    private val methodIsMultiTalking2 by dexMethod {
        matcher {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            usingEqStrings("MicroMsg.DeviceOccupy", "isMultiTalking")
            paramCount = 2
        }
    }
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

    // 8.0.77 moved the multitalk action listener out of
    // com.tencent.mm.plugin.multitalk.model (MultiTalkManager$18) into
    // com.tencent.mm.voipmp.v2 (VoIPMPCoreV2$multiTalkActionListener$1),
    // so this resolver has no result on 8.0.77 and the next one has no
    // result before it.
    private val methodMultiTalkCallBack by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.multitalk.model")
        matcher {
            declaredClass {
                addAnnotation {
                    type("dalvik.annotation.Signature")
                    addElement {
                        name = "value"
                        arrayValue {
                            add { stringValue("Lcom/tencent/mm/sdk/event/IListener<") }
                            add { stringValue("Lcom/tencent/mm/autogen/events/MultiTalkActionEvent;") }
                            add { stringValue(">;") }
                        }
                    }
                }
            }
            name = "callback"
            paramCount = 1
            returnType = "boolean"
        }
    }

    private val methodMultiTalkCallBackV2 by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.voipmp.v2")
        // searchPackages is prefix-matched and would also hit the
        // MultiTalkSelectContactUI listener in the multitalk.ui subpackage,
        // which must not be hooked (its pre-8.0.77 counterpart in
        // com.tencent.mm.plugin.multitalk.ui was never matched either).
        excludePackages("com.tencent.mm.voipmp.v2.multitalk")
        matcher {
            declaredClass {
                addAnnotation {
                    type("dalvik.annotation.Signature")
                    addElement {
                        name = "value"
                        arrayValue {
                            add { stringValue("Lcom/tencent/mm/sdk/event/IListener<") }
                            add { stringValue("Lcom/tencent/mm/autogen/events/MultiTalkActionEvent;") }
                            add { stringValue(">;") }
                        }
                    }
                }
            }
            name = "callback"
            paramCount = 1
            returnType = "boolean"
        }
    }

    private val methodVoipCallBack by dexMethod {
        searchPackages("com.tencent.mm.plugin.voip.model")
        matcher {
            declaredClass {
                addAnnotation {
                    type("dalvik.annotation.Signature")
                    addElement {
                        name = "value"
                        arrayValue {
                            add { stringValue("Lcom/tencent/mm/sdk/event/IListener<") }
                            add { stringValue("Lcom/tencent/mm/autogen/events/VoipCheckIsDeviceUsingEvent;") }
                            add { stringValue(">;") }
                        }
                    }
                }
            }
            name = "callback"
            paramCount = 1
            returnType = "boolean"
        }
    }

    private val methodIpCallCallBack by dexMethod {
        searchPackages("com.tencent.mm.plugin.ipcall")
        matcher {
            declaredClass {
                addAnnotation {
                    type("dalvik.annotation.Signature")
                    addElement {
                        name = "value"
                        arrayValue {
                            add { stringValue("Lcom/tencent/mm/sdk/event/IListener<") }
                            add { stringValue("Lcom/tencent/mm/autogen/events/VoipCheckIsDeviceUsingEvent;") }
                            add { stringValue(">;") }
                        }
                    }
                }
            }
            name = "callback"
            paramCount = 1
            returnType = "boolean"
        }
    }

    private val methodFlutterLinkVoipCallBack by dexMethod {
        searchPackages("com.tencent.mm.voipmp.helper", "com.tencent.mm.plugin_flutter_ilinkvoip.helper")
        matcher {
            declaredClass {
                addAnnotation {
                    type("dalvik.annotation.Signature")
                    addElement {
                        name = "value"
                        arrayValue {
                            add { stringValue("Lcom/tencent/mm/sdk/event/IListener<") }
                            add { stringValue("Lcom/tencent/mm/autogen/events/VoipCheckIsDeviceUsingEvent;") }
                            add { stringValue(">;") }
                        }
                    }
                }
            }
            name = "callback"
            paramCount = 1
            returnType = "boolean"
        }
    }
}
