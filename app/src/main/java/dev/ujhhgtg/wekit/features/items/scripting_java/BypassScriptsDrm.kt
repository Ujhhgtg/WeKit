package dev.ujhhgtg.wekit.features.items.scripting_java

import bsh.Interpreter
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object BypassScriptsDrm : SwitchFeature() {

    override val technicalId = "绕过部分脚本验证"
    override val nameRes = R.string.feature_bypass_scripts_drm_name
    override val categoryIds = listOf(FeatureCategoryIds.SCRIPTING_JAVA)
    override val descriptionRes = R.string.feature_bypass_scripts_drm_description

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
