package dev.ujhhgtg.wekit.i18n

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import dev.ujhhgtg.wekit.R

internal object MeowResourceFilter {
    private val weKitPackageId = R.string.res_inject_success ushr 24

    fun isWeKitResource(id: Int): Boolean = id ushr 24 == weKitPackageId
}

@Suppress("DEPRECATION")
internal class MeowResources(
    private val delegate: Resources,
) : Resources(delegate.assets, delegate.displayMetrics, delegate.configuration) {
    override fun getText(id: Int): CharSequence =
        delegate.getText(id).let { text ->
            if (MeowResourceFilter.isWeKitResource(id)) MeowTextTransformer.transform(text) else text
        }

    override fun getText(id: Int, def: CharSequence?): CharSequence? {
        if (!MeowResourceFilter.isWeKitResource(id)) return delegate.getText(id, def)
        return try {
            MeowTextTransformer.transform(delegate.getText(id))
        } catch (_: NotFoundException) {
            def
        }
    }

    override fun getString(id: Int): String =
        delegate.getString(id).let { text ->
            if (MeowResourceFilter.isWeKitResource(id)) MeowTextTransformer.transform(text) else text
        }

    override fun getString(id: Int, vararg formatArgs: Any): String =
        delegate.getString(id, *formatArgs).let { text ->
            if (MeowResourceFilter.isWeKitResource(id)) MeowTextTransformer.transform(text) else text
        }

    override fun getQuantityText(id: Int, quantity: Int): CharSequence =
        delegate.getQuantityText(id, quantity).let { text ->
            if (MeowResourceFilter.isWeKitResource(id)) MeowTextTransformer.transform(text) else text
        }

    override fun getQuantityString(id: Int, quantity: Int): String =
        delegate.getQuantityString(id, quantity).let { text ->
            if (MeowResourceFilter.isWeKitResource(id)) MeowTextTransformer.transform(text) else text
        }

    override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any): String =
        delegate.getQuantityString(id, quantity, *formatArgs).let { text ->
            if (MeowResourceFilter.isWeKitResource(id)) MeowTextTransformer.transform(text) else text
        }

    override fun getTextArray(id: Int): Array<CharSequence> =
        delegate.getTextArray(id).let { texts ->
            if (MeowResourceFilter.isWeKitResource(id)) {
                texts.map(MeowTextTransformer::transform).toTypedArray()
            } else {
                texts
            }
        }

    override fun getStringArray(id: Int): Array<String> =
        delegate.getStringArray(id).let { texts ->
            if (MeowResourceFilter.isWeKitResource(id)) {
                texts.map(MeowTextTransformer::transform).toTypedArray()
            } else {
                texts
            }
        }
}

internal class MeowResourcesContext(
    base: Context,
) : ContextWrapper(base) {
    private val meowResources = MeowResources(base.resources)

    override fun getResources(): Resources = meowResources
}
