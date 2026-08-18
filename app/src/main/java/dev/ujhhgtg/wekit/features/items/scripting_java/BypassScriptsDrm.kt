package dev.ujhhgtg.wekit.features.items.scripting_java

import bsh.Interpreter
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    id = "绕过部分脚本验证",
    nameRes = "feature_bypass_scripts_drm_name",
    categoryIds = [FeatureCategoryIds.SCRIPTING_JAVA],
    descriptionRes = "feature_bypass_scripts_drm_description",
)
object BypassScriptsDrm : SwitchFeature() {
    private val hook = ScriptsDrmBypassHook()

    internal fun registerInterpreter(interpreter: Interpreter) {
        hook.registerInterpreter(interpreter)
    }

    internal fun unregisterInterpreter(interpreter: Interpreter) {
        hook.unregisterInterpreter(interpreter)
    }

    override fun onEnable() {
        Interpreter.bshHookManager.addHook(hook)
    }

    override fun onDisable() {
        Interpreter.bshHookManager.removeHook(hook)
    }
}
