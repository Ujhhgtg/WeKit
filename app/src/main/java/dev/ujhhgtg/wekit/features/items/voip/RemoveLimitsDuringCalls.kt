package dev.ujhhgtg.wekit.features.items.voip

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClassQuery
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethodQuery
import dev.ujhhgtg.wekit.dexkit.matcher.AnnotationElementSpec
import dev.ujhhgtg.wekit.dexkit.matcher.AnnotationSpec
import dev.ujhhgtg.wekit.dexkit.matcher.AnnotationValue
import dev.ujhhgtg.wekit.dexkit.matcher.AnnotationVisibility
import dev.ujhhgtg.wekit.dexkit.matcher.ClassSpec
import dev.ujhhgtg.wekit.dexkit.matcher.MethodSpec
import dev.ujhhgtg.wekit.dexkit.matcher.NameSpec
import dev.ujhhgtg.wekit.dexkit.sql.sqlMethod
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
            if (!it.isFound) return@forEach
            it.hookBefore {
                result = false
            }
        }
    }

    private val methodIsDuringCall by dexMethodQuery {
        uniqueMethod(
            MethodSpec(
                declaredClass = ClassSpec(modifiers = java.lang.reflect.Modifier.ABSTRACT),
                modifiers = java.lang.reflect.Modifier.STATIC,
                paramCount = 0..0,
                returnType = ClassSpec(className = NameSpec("boolean")),
                invokeMethods = listOf(
                    MethodSpec(
                        declaredClass = ClassSpec(
                            className = NameSpec("com.tencent.mm.autogen.events.MultiTalkActionEvent"),
                        ),
                    ),
                ),
            ),
        )
    }
    private val methodIsMultiTalking by dexMethod(
        sqlMethod {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            paramCount = 1
            usingEqStrings("MicroMsg.DeviceOccupy", "isMultiTalking")
        },
    )

    private val methodIsMultiTalking2 by dexMethod(
        sqlMethod {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            paramCount = 2
            usingEqStrings("MicroMsg.DeviceOccupy", "isMultiTalking")
        },
    )
    private val methodIsCameraUsing by dexMethod(
        sqlMethod {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            usingEqStrings("MicroMsg.DeviceOccupy", "isCameraUsing", "")
        },
    )
    private val methodIsCameraUsing2 by dexMethod(
        sqlMethod {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            usingEqStrings(
                "MicroMsg.DeviceOccupy",
                "isCameraUsing",
                "isLiving %b isAnchor %b isAudioMicing %s isVideoMicing %s",
            )
        },
    )
    private val methodIsVoiceUsing by dexMethod(
        sqlMethod {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            paramCount = 1
            usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
        },
    )
    private val methodIsVoiceUsing2 by dexMethod(
        sqlMethod {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            paramCount = 2
            usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
        },
    )
    private val methodCheckAppBrandVoiceUsing by dexMethod(
        sqlMethod {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            paramCount = 1
            usingEqStrings(
                "MicroMsg.DeviceOccupy",
                "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b",
            )
        },
    )
    private val methodCheckAppBrandVoiceUsing2 by dexMethod(
        sqlMethod {
            declaredClass(methodIsDuringCall.data.declaredClassName)
            paramCount = 2
            usingEqStrings(
                "MicroMsg.DeviceOccupy",
                "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b",
            )
        },
    )

    // 8.0.77 moved the multitalk action listener out of
    // com.tencent.mm.plugin.multitalk.model (MultiTalkManager$18) into
    // com.tencent.mm.voipmp.v2 (VoIPMPCoreV2$multiTalkActionListener$1),
    // so this resolver has no result on 8.0.77 and the next one has no
    // result before it.
    private val methodMultiTalkCallBack by dexMethodQuery(allowFailure = true) {
        uniqueMethod(
            MethodSpec(
                declaredClass = ClassSpec(
            annotations = listOf(
                AnnotationSpec(
                    type = NameSpec("dalvik.annotation.Signature"),
                    visibility = AnnotationVisibility.System,
                    elements = listOf(
                        AnnotationElementSpec(
                            name = NameSpec("value"),
                            value = AnnotationValue.ListValue(
                                listOf(
                                    AnnotationValue.Text("Lcom/tencent/mm/sdk/event/IListener<"),
        AnnotationValue.Text("Lcom/tencent/mm/autogen/events/MultiTalkActionEvent;"),
        AnnotationValue.Text(">;")
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
                name = NameSpec("callback"),
                paramCount = 1..1,
                returnType = ClassSpec(className = NameSpec("boolean")),
            ),
            packages = listOf("com.tencent.mm.plugin.multitalk.model"),
        )
    }

    private val methodMultiTalkCallBackV2 by dexMethodQuery(allowFailure = true) {
        uniqueMethod(
            MethodSpec(
                declaredClass = ClassSpec(
            annotations = listOf(
                AnnotationSpec(
                    type = NameSpec("dalvik.annotation.Signature"),
                    visibility = AnnotationVisibility.System,
                    elements = listOf(
                        AnnotationElementSpec(
                            name = NameSpec("value"),
                            value = AnnotationValue.ListValue(
                                listOf(
                                    AnnotationValue.Text("Lcom/tencent/mm/sdk/event/IListener<"),
        AnnotationValue.Text("Lcom/tencent/mm/autogen/events/MultiTalkActionEvent;"),
        AnnotationValue.Text(">;")
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
                name = NameSpec("callback"),
                paramCount = 1..1,
                returnType = ClassSpec(className = NameSpec("boolean")),
            ),
            packages = listOf("com.tencent.mm.voipmp.v2"),
            excludePackages = listOf("com.tencent.mm.voipmp.v2.multitalk"),
        )
    }

    private val methodVoipCallBack by dexMethodQuery {
        uniqueMethod(
            MethodSpec(
                declaredClass = ClassSpec(
            annotations = listOf(
                AnnotationSpec(
                    type = NameSpec("dalvik.annotation.Signature"),
                    visibility = AnnotationVisibility.System,
                    elements = listOf(
                        AnnotationElementSpec(
                            name = NameSpec("value"),
                            value = AnnotationValue.ListValue(
                                listOf(
                                    AnnotationValue.Text("Lcom/tencent/mm/sdk/event/IListener<"),
        AnnotationValue.Text("Lcom/tencent/mm/autogen/events/VoipCheckIsDeviceUsingEvent;"),
        AnnotationValue.Text(">;")
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
                name = NameSpec("callback"),
                paramCount = 1..1,
                returnType = ClassSpec(className = NameSpec("boolean")),
            ),
            packages = listOf("com.tencent.mm.plugin.voip.model"),
        )
    }

    private val methodIpCallCallBack by dexMethodQuery {
        uniqueMethod(
            MethodSpec(
                declaredClass = ClassSpec(
            annotations = listOf(
                AnnotationSpec(
                    type = NameSpec("dalvik.annotation.Signature"),
                    visibility = AnnotationVisibility.System,
                    elements = listOf(
                        AnnotationElementSpec(
                            name = NameSpec("value"),
                            value = AnnotationValue.ListValue(
                                listOf(
                                    AnnotationValue.Text("Lcom/tencent/mm/sdk/event/IListener<"),
        AnnotationValue.Text("Lcom/tencent/mm/autogen/events/VoipCheckIsDeviceUsingEvent;"),
        AnnotationValue.Text(">;")
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
                name = NameSpec("callback"),
                paramCount = 1..1,
                returnType = ClassSpec(className = NameSpec("boolean")),
            ),
            packages = listOf("com.tencent.mm.plugin.ipcall"),
        )
    }

    private val methodFlutterLinkVoipCallBack by dexMethodQuery {
        uniqueMethod(
            MethodSpec(
                declaredClass = ClassSpec(
            annotations = listOf(
                AnnotationSpec(
                    type = NameSpec("dalvik.annotation.Signature"),
                    visibility = AnnotationVisibility.System,
                    elements = listOf(
                        AnnotationElementSpec(
                            name = NameSpec("value"),
                            value = AnnotationValue.ListValue(
                                listOf(
                                    AnnotationValue.Text("Lcom/tencent/mm/sdk/event/IListener<"),
        AnnotationValue.Text("Lcom/tencent/mm/autogen/events/VoipCheckIsDeviceUsingEvent;"),
        AnnotationValue.Text(">;")
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
                name = NameSpec("callback"),
                paramCount = 1..1,
                returnType = ClassSpec(className = NameSpec("boolean")),
            ),
            packages = listOf("com.tencent.mm.voipmp.helper", "com.tencent.mm.plugin_flutter_ilinkvoip.helper"),
        )
    }
}
