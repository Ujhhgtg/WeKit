package dev.ujhhgtg.wekit.features.items.profile

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType

object RemoveTextStatusLengthLimit : SwitchFeature(), IResolveDex {

    override val technicalId = "解除状态词长度限制"
    override val nameRes = R.string.feature_remove_text_status_length_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.PROFILE)
    override val descriptionRes = R.string.feature_remove_text_status_length_limit_description

    private val methodStatusTextChanged by dexMethod("""SELECT DISTINCT m.descriptor AS method FROM methods m JOIN method_strings ms0 ON ms0.method = m.descriptor JOIN method_strings ms1 ON ms1.method = m.descriptor WHERE ms0.value = 'MicroMsg.TextStatus.TextStatusDoWhatActivityV2' AND ms1.value = 'afterTextChanged inputCount:' AND m.name = 'afterTextChanged' AND m.return_descriptor = 'V' AND m.param_descriptors = 'Landroid/text/Editable;' AND (m.class_descriptor LIKE 'Lcom/tencent/mm/plugin/textstatus/ui/%') ORDER BY m.descriptor;""")
    private val fieldStatusTextLengthLimit by dexField()

    override fun resolveDex(dexKit: DexKitBridge) {
        fieldStatusTextLengthLimit.setDescriptor(
            methodStatusTextChanged.data.usingFields
                .filter { it.usingType == FieldUsingType.Read }
                .map { it.field }
                .distinctBy { it.descriptor }
                .single {
                    it.className == STATUS_EDITOR_CLASS && it.typeName == "int"
                }
        )
    }

    override fun onEnable() {
        val limitField = fieldStatusTextLengthLimit.field
        limitField.declaringClass.reflekt().constructors().forEach { constructor ->
            constructor.hookAfter {
                limitField.setInt(thisObject!!, MAX_STATUS_TEXT_LENGTH)
            }
        }
    }

    private const val STATUS_EDITOR_CLASS =
        "com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivityV2"
    private const val MAX_STATUS_TEXT_LENGTH = 2000
}
