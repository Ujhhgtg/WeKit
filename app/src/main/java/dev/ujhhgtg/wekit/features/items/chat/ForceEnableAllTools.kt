package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.dexClassFrom
import dev.ujhhgtg.wekit.dex.dsl.dexFieldFrom
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.dex.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object ForceEnableAllTools : SwitchFeature() {
    override val technicalId = "强制启用所有功能"
    override val nameRes = R.string.feature_force_enable_all_tools_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_force_enable_all_tools_description

    override fun onEnable() {
        methodRefreshAppPanel.hookBefore {
            val appPanel = thisObject ?: return@hookBefore
            val config = fieldAppPanelConfig.field.get(appPanel) ?: return@hookBefore
            config
                .reflekt()
                .fields {
                    type = fieldFirstFlag.field.type
                }
                .forEach {
                    fieldFlagEnabled.field.setBoolean(it.get()!!, true)
                }
        }
    }

    private val methodRefreshAppPanel by dexMethod {
        searchPackages(APP_PANEL_PACKAGE)
        declaredClass = APP_PANEL_CLASS
        usingEqString("MicroMsg.AppPanel")
        usingEqString("roomEnable:%s, hideRoomLive:%s")
        paramCount = 0
        returnType = "void"
    }
    private val fieldFirstFlag by dexFieldFrom {
        val usedFields =
            methodRefreshAppPanel.data.usingFields.map { it.field }.distinctBy { it.descriptor }
        // The previous resolver skipped non-final fields before resolving their type, so an
        // external or
        // primitive type (classInfoByName -> null) must count as "no boolean fields".
        usedFields.first { field ->
            field.modifiers and java.lang.reflect.Modifier.FINAL != 0 &&
                DexResolutionContext.metadata.classInfoByName(field.typeName)?.fields?.count {
                    it.typeName == "boolean"
                } == 1
        }
    }
    private val classAppPanelConfig by dexClassFrom {
        fieldFirstFlag.data.declaredClass!!
    }
    private val fieldAppPanelConfig by dexFieldFrom {
        methodRefreshAppPanel.data.usingFields
            .map { it.field }
            .distinctBy { it.descriptor }
            .single { field ->
                field.className == APP_PANEL_CLASS &&
                    field.typeName == classAppPanelConfig.data.name
            }
    }
    private val fieldFlagEnabled by dexFieldFrom {
        DexResolutionContext.metadata
            .classInfoByName(fieldFirstFlag.data.typeName)!!
            .fields
            .single {
                it.typeName == "boolean"
            }
    }

    private const val APP_PANEL_PACKAGE = "com.tencent.mm.pluginsdk.ui.chat"
    private const val APP_PANEL_CLASS = "$APP_PANEL_PACKAGE.AppPanel"
}
