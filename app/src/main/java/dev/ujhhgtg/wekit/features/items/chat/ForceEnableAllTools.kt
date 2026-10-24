package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object ForceEnableAllTools : SwitchFeature(), IResolveDex {
    override val technicalId = "强制启用所有功能"
    override val nameRes = R.string.feature_force_enable_all_tools_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_force_enable_all_tools_description

    private val classAppPanelConfig by dexClass()
    private val fieldAppPanelConfig by dexField()
    private val fieldFirstFlag by dexField()
    private val fieldFlagEnabled by dexField()
    private val methodRefreshAppPanel by dexMethod()

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

    override fun resolveDex() {
        val refreshMethod =
            DexResolutionContext.findMethods {
                    searchPackages(APP_PANEL_PACKAGE)
                    declaredClass = APP_PANEL_CLASS
                    usingEqString("MicroMsg.AppPanel")
                    usingEqString("roomEnable:%s, hideRoomLive:%s")
                    paramCount = 0
                    returnType = "void"
                }
                .single()
        methodRefreshAppPanel.setDescriptor(refreshMethod)

        val usedFields = refreshMethod.usingFields.map { it.field }.distinctBy { it.descriptor }
        // The previous resolver skipped non-final fields before resolving their type, so an
        // external or
        // primitive type (classInfoByName -> null) must count as "no boolean fields".
        val firstFlag = usedFields.first { field ->
            field.modifiers and java.lang.reflect.Modifier.FINAL != 0 &&
                DexResolutionContext.metadata.classInfoByName(field.typeName)?.fields?.count {
                    it.typeName == "boolean"
                } == 1
        }
        val configClass = firstFlag.declaredClass!!
        val appPanelConfig = usedFields.single { field ->
            field.className == APP_PANEL_CLASS && field.typeName == configClass.name
        }
        val flagEnabled =
            DexResolutionContext.metadata.classInfoByName(firstFlag.typeName)!!.fields.single {
                it.typeName == "boolean"
            }

        classAppPanelConfig.setDescriptor(configClass)
        fieldAppPanelConfig.setDescriptor(appPanelConfig)
        fieldFirstFlag.setDescriptor(firstFlag)
        fieldFlagEnabled.setDescriptor(flagEnabled)
    }

    private const val APP_PANEL_PACKAGE = "com.tencent.mm.pluginsdk.ui.chat"
    private const val APP_PANEL_CLASS = "$APP_PANEL_PACKAGE.AppPanel"
}
