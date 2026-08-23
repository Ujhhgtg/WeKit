package dev.ujhhgtg.wekit.features.items.chat

import android.widget.ImageView
import androidx.core.net.toUri
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger

/**
 * Blocks read-receipt tracking pixels embedded as Finder feed thumbnails.
 *
 * [ReadReceipts] places its pixel URL in both thumbUrl and coverUrl. WeChat passes the selected
 * URL through FinderLoaderApi before loading or prefetching it, so skipping this method prevents
 * either path from reaching the tracking server.
 */
object AntiReadReceipts : SwitchFeature(), IResolveDex {

    override val technicalId = "反已读追踪"
    override val nameRes = R.string.feature_anti_read_receipts_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_anti_read_receipts_description

    private val methodLoadFinderImage by dexMethod {
        matcher {
            usingEqStrings("FinderLoaderApi", "#loadImage url=")
            paramTypes(String::class.java, ImageView::class.java, null)
            returnType("void")
        }
    }

    override fun onEnable() {
        methodLoadFinderImage.hookBefore {
            val url = args[0] as? String? ?: return@hookBefore
            if (url.toUri().path?.endsWith("/pixel") == true) {
                WeLogger.d("AntiReadReceipts", "blocked request to $url")
                result = null
            }
        }
    }
}
