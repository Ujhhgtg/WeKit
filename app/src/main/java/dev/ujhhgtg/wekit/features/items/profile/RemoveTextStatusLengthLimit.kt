package dev.ujhhgtg.wekit.features.items.profile

import dev.ujhhgtg.dexsql.meta.FieldUsingType
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.dexFieldFrom
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object RemoveTextStatusLengthLimit : SwitchFeature() {
    override val technicalId = "解除状态词长度限制"
    override val nameRes = R.string.feature_remove_text_status_length_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.PROFILE)
    override val descriptionRes = R.string.feature_remove_text_status_length_limit_description

    private val methodStatusTextChanged by dexMethod {
        searchPackages("com.tencent.mm.plugin.textstatus.ui")
        name = "afterTextChanged"
        returnType = "void"
        paramTypes("android.text.Editable")
        usingEqStrings(
            "MicroMsg.TextStatus.TextStatusDoWhatActivityV2",
            "afterTextChanged inputCount:",
        )
    }
    private val fieldStatusTextLengthLimit by dexFieldFrom {
        methodStatusTextChanged.data.usingFields
            .filter { it.usingType == FieldUsingType.Read }
            .map { it.field }
            .distinctBy { it.descriptor }
            .single {
                it.className == STATUS_EDITOR_CLASS && it.typeName == "int"
            }
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
